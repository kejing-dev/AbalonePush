package io.embodit.abalonepush.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.abalonepush.domain.Abalone
import io.embodit.abalonepush.domain.GameFlow
import io.embodit.abalonepush.domain.Hex
import io.embodit.abalonepush.domain.Layout
import io.embodit.abalonepush.domain.Pad
import io.embodit.abalonepush.domain.PadKind
import io.embodit.abalonepush.domain.Phase
import io.embodit.abalonepush.domain.PlayState
import io.embodit.abalonepush.domain.Settings
import io.embodit.abalonepush.domain.V3
import io.embodit.abalonepush.platform.TAG
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sin

/** The GLB templates (tools/build_models.py). Pad variants: blue, amber 1 / 2 dots, amber with the red drop edge, grey. */
internal class Models(val board: Entity, val marbles: List<Entity>, val pads: Map<String, Entity>)

/**
 * Every entity of the board volume. Compose hands over [PlayState] + [Settings] through [apply]; everything that moves
 * (marbles sliding / pushed / dropping into the gutter, pads, rings, the gutter glow) is placed in [onFrame], driven by
 * [BoardFrameSystem] (never by Compose).
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ tiltRoot (near-bottom board edge, tipped 20° about X)
 *   │ └ boardRoot (board top centre): board.glb, collider "board", 61 cell colliders "cell:<i>", 28 marbles (collider child
 *   │     renamed "cell:<i>" to the cell they stand on), the pad pool (collider "pad:<d>"), rings, the gutter glow
 *   └ counter plate (AttachmentPanel, upright above the far rim), key light
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var plate: Entity? = null

    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f

    /** marbles[colour] = 14 entities each; colliders[colour][i] is the collider child of marbles[colour][i]. */
    private val marbles = List(2) { ArrayList<Entity>() }
    private val colliders = List(2) { ArrayList<Entity>() }
    private val colliderNames = HashMap<Entity, String>()
    /** Pad pool: variant -> 6 entities (a collider child each). */
    private val padPool = HashMap<String, List<Entity>>()
    private val padColliders = HashMap<Entity, Entity>()
    private val selRings = ArrayList<ModelEntity>()
    private val joinRings = ArrayList<ModelEntity>()
    private val hintRings = ArrayList<ModelEntity>()
    private val alertRings = ArrayList<ModelEntity>()
    private val lastRings = ArrayList<ModelEntity>()
    private var padAlertRing: ModelEntity? = null
    private var hoverRing: ModelEntity? = null
    private var glow: ModelEntity? = null

    @Volatile private var hover: String? = null
    private var hoverAt = 0L
    private var clock = 0f
    private var selKey: List<Int> = emptyList()
    private var selAt = 0L

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
        place(tiltRoot, Layout.TILT_ORIGIN)
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), Layout.THETA))
        place(boardRoot, Layout.BOARD_OFFSET)
    }

    val ready: Boolean get() = built

    /** D50 hover: the collider name under a resting pointer; light feedback only, gone 250 ms after the last event. */
    fun setHover(name: String?) {
        hoverAt = SystemClock.uptimeMillis()
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    /** The collider name of a marble entity (its collider child), for hits on the marble's GLB mesh. */
    fun colliderNameOf(e: Entity): String? {
        for (side in 0..1) marbles[side].forEachIndexed { i, m -> if (m === e) return colliderNames[colliders[side][i]] }
        for ((pad, c) in padColliders) if (pad === e) return c.getName()
        return null
    }

    fun setRegion(halfY: Float) {
        if (kotlin.math.abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    /** S00 stand-in: a plain dark slab where the board will be, shown until the models are in. */
    fun showFallback(slab: MeshResource, material: Material) {
        if (built || fallback.isNotEmpty()) return
        val board = ModelEntity(slab, material).apply { setName("fallbackBoard") }
        place(board, V3(0f, -Layout.BOARD_T / 2, 0f))
        fallback = listOf(board)
        fallback.forEach { boardRoot.addChild(it) }
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun collider(name: String, shape: ShapeResource, r: SceneResources, hoverEffect: Boolean = false): Entity = Entity().apply {
        setName(name)
        components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = r.physics))
        components.set(InteractableComponent())
        if (hoverEffect) runCatching { components.set(HoverEffectComponent()) }
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    private fun ring(mesh: MeshResource, m: Material, name: String): ModelEntity =
        ModelEntity(mesh, m).apply { setName(name); enabled = false; boardRoot.addChild(this) }

    fun install(r: SceneResources, models: Models, plateEntity: Entity?) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        boardRoot.addChild(clone(models.board).apply { setName("boardModel") })
        boardRoot.addChild(collider("board", r.boardShape, r).also { place(it, V3(0f, -Layout.BOARD_T / 2, 0f)) })
        for (c in 0 until Hex.N) boardRoot.addChild(collider("cell:$c", r.cellShape, r).also { place(it, Layout.cell(c, 0.0015f)) })
        for (colour in 0..1) repeat(Abalone.PER_SIDE) { i ->
            val e = clone(models.marbles[colour]).apply { setName("marble:$colour:$i") }
            runCatching { e.components.set(HoverEffectComponent()) }
            val c = collider("none", r.marbleShape, r)
            e.addChild(c)
            boardRoot.addChild(e)
            marbles[colour] += e
            colliders[colour] += c
        }
        for ((variant, template) in models.pads) {
            padPool[variant] = List(6) { i ->
                val e = clone(template).apply { setName("pad:$variant:$i"); enabled = false }
                val c = collider("pad:x", r.padShape, r)
                e.addChild(c)
                padColliders[e] = c
                boardRoot.addChild(e)
                e
            }
        }
        repeat(3) { i -> selRings += ring(r.ringMarble, r.cream, "selRing:$i") }
        repeat(6) { i -> joinRings += ring(r.ringSmall, r.joinable, "joinRing:$i") }
        repeat(3) { i -> hintRings += ring(r.ringMarble, r.amber, "hintRing:$i") }
        repeat(4) { i -> alertRings += ring(r.ringCell, r.red, "alertRing:$i") }
        repeat(3) { i -> lastRings += ring(r.ringCell, r.last, "lastRing:$i") }
        padAlertRing = ring(r.ringPad, r.red, "padAlertRing")
        hoverRing = ring(r.ringCell, r.hover, "hoverRing")
        glow = ring(r.glowPlane, r.glow, "gutterGlow")
        plate = plateEntity?.also { sceneRoot.addChild(it); place(it, Layout.plateCentre()) }
        buildLight()

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms plate=${Layout.plateCentre()} far=${Layout.toScene(V3(0f, 0f, -Layout.BOARD_APOTHEM))}")
        onFrame(0f)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front right, above (not over-exposed on the pearl marbles).
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.85f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (30 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-55 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    fun apply(next: PlayState, s: Settings) {
        state = next
        settings = s
        if (!built) return
        onFrame(0f)
    }

    // ---------------------------------------------------------------- per frame

    private fun setPos(e: Entity, v: V3) = place(e, v)

    private fun padVariant(p: Pad): String = when (p.kind) {
        PadKind.MOVE -> "blue"
        PadKind.PUSH -> "amber${p.pushCount.coerceIn(1, 2)}" + if (p.drops) "red" else ""
        else -> "grey"
    }

    /** Rotation about the board normal that turns the pad's +X arrow into direction [d]. */
    private fun dirQ(d: Int): Quat = Quat(Vector3(0f, 1f, 0f), atan2(-Hex.dirZ(d), Hex.dirX(d)))

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val reduced = settings.reducedMotion
        fun elapsed(start: Long): Long = (now - start).let { e -> st.freezeMs?.takeIf { start >= st.freezeAt }?.let { min(e, it) } ?: e }

        // ---------------- marbles
        val anim = st.anim
        val m = anim?.move
        val e = anim?.let { elapsed(it.startedAt) } ?: 0L
        val base = anim?.before ?: st.pos
        val moving = HashMap<Int, Int>()          // from cell -> to cell (-1 = off) for this move
        m?.let { Abalone.steps(it).forEach { (f, t) -> moving[f] = t } }
        val gutter = Layout.gutter(st.startPos, st.moves)
        val dropping = m != null && m.drops
        val staticGutter = if (dropping) gutter.dropLast(1) else gutter
        if (st.selection != selKey) { selKey = st.selection; selAt = now }
        val liftK = if (reduced) 1f else Layout.easeOut((now - selAt) / 120f)
        val lifted = st.selection.toSet().takeIf { st.phase == Phase.SELECTED || st.phase == Phase.THINKING } ?: emptySet()

        val used = intArrayOf(0, 0)
        fun put(colour: Int, at: V3, name: String) {
            val i = used[colour]++
            val ent = marbles[colour].getOrNull(i) ?: return
            ent.enabled = true
            setPos(ent, at)
            val c = colliders[colour][i]
            if (colliderNames[c] != name) { c.setName(name); colliderNames[c] = name }
        }
        for (c in 0 until Hex.N) {
            val colour = base.owner(c)
            if (colour < 0) continue
            val to = moving[c]
            if (m == null || to == null) {
                val lift = if (c in lifted) Layout.LIFT * liftK else 0f
                put(colour, Layout.marble(c, lift), "cell:$c")
                continue
            }
            val pushedMarble = colour != m.side
            val start = if (pushedMarble && !reduced) GameFlow.PUSH_LAG_MS else 0L
            val dur = if (reduced) GameFlow.REDUCED_MS else if (m.isPush) GameFlow.PUSH_MS else GameFlow.SLIDE_MS
            val t = Layout.easeOut((e - start).toFloat() / dur)
            val from = Layout.marble(c, if (!pushedMarble && !reduced) Layout.LIFT else 0f)
            if (to >= 0) { put(colour, from.lerp(Layout.marble(to), t), "moving"); continue }
            // the marble that goes over the rim: to the lip during the push, then a 350 ms arc into the gutter, then a short roll
            val slotIdx = gutter.last().second
            val slot = Layout.slot(slotIdx)
            val lip = Layout.lipExit(c, m.dir)
            if (reduced) { put(colour, slot, "gutter"); continue }
            val tail = Layout.slot(slotIdx).let { s ->
                // roll in from up to 6 cm before the slot, along the gutter toward it
                val k = (Layout.ROLL / Layout.GUTTER_MID).coerceAtMost(0.4f)
                V3(s.x + (lip.x - s.x) * k, s.y, s.z + (lip.z - s.z) * k)
            }
            val de = e - anim.slideMs
            val at = when {
                de < 0 -> from.lerp(lip, t)
                // a ballistic arc peaking 4 cm over the rim
                de < GameFlow.FLIP_MS -> Layout.arc(lip, tail, de.toFloat() / GameFlow.FLIP_MS, Layout.ARC)
                else -> {
                    val u = ((de - GameFlow.FLIP_MS).toFloat() / GameFlow.ROLL_MS).coerceIn(0f, 1f)
                    val hop = if (u < 0.35f) 0.004f * sin(u / 0.35f * PI.toFloat()) else 0f
                    tail.lerp(slot, Layout.easeOut(u)) + V3(0f, hop, 0f)
                }
            }
            put(colour, at, "dropping")
        }
        for ((colour, slotIdx) in staticGutter) put(colour, Layout.slot(slotIdx), "gutter")
        for (colour in 0..1) for (i in used[colour] until marbles[colour].size) {
            marbles[colour][i].enabled = false
        }

        // ---------------- direction pads (S04, and the AI's chosen line for a moment in S06)
        val showPads = (st.phase == Phase.SELECTED && st.humansTurn) || (st.phase == Phase.THINKING && st.selection.isNotEmpty())
        val padsUsed = HashMap<String, Int>()
        val hintPad = st.hint?.takeIf { it.group.toSet() == st.selection.toSet() }?.dir
        val drop = if (reduced) 0f else (1f - Layout.easeOut((now - selAt) / 150f)) * 0.02f
        val hoverPad = hover?.takeIf { it.startsWith("pad:") && now - hoverAt < 250 }?.substringAfter("pad:")?.toIntOrNull()
        if (showPads) for (p in st.pads) {
            val v = padVariant(p)
            val k = padsUsed.getOrDefault(v, 0)
            padsUsed[v] = k + 1
            val ent = padPool[v]?.getOrNull(k) ?: continue
            ent.enabled = true
            val lift = if (hoverPad == p.dir) 0.004f else 0f
            setPos(ent, Layout.pad(p) + V3(0f, drop + lift, 0f))
            ent.transform().setQuaternion(dirQ(p.dir))
            val breathe = if (hintPad == p.dir && !reduced) 1f + 0.08f * sin(clock * 5f) else 1f
            ent.transform().setScaleVector(Vector3(breathe, 1f, breathe))
            padColliders[ent]?.let { c -> val n = "pad:${p.dir}"; if (colliderNames[c] != n) { c.setName(n); colliderNames[c] = n } }
        }
        for ((v, pool) in padPool) for (i in padsUsed.getOrDefault(v, 0) until pool.size) pool[i].enabled = false

        // ---------------- rings
        val selLift = Layout.LIFT * liftK
        selRings.forEachIndexed { i, ring ->
            val c = st.selection.getOrNull(i)
            if (c != null && lifted.isNotEmpty()) { setPos(ring, Layout.cell(c, Layout.MARBLE_Y - Layout.MARBLE_R * 0.55f + selLift)); ring.enabled = true } else ring.enabled = false
        }
        val joinable: List<Int> = if (st.phase == Phase.SELECTED && st.humansTurn && st.selection.size in 1..2)
            st.pos.cellsOf(st.side).filter { it !in st.selection && Abalone.isGroup(st.pos, st.side, st.selection + it) } else emptyList()
        joinRings.forEachIndexed { i, ring ->
            val c = joinable.getOrNull(i)
            if (c != null) { setPos(ring, Layout.cell(c, 0.0012f)); ring.enabled = true } else ring.enabled = false
        }
        val hint = st.hint?.takeIf { st.hintOn && st.humansTurn && (st.phase == Phase.IDLE || st.phase == Phase.SELECTED) }
        val hintOn = hint != null && (reduced || ((now / 400) % 2 == 0L))
        hintRings.forEachIndexed { i, ring ->
            val c = hint?.group?.getOrNull(i)
            if (c != null && hintOn && c !in st.selection) { setPos(ring, Layout.cell(c, 0.0016f)); ring.enabled = true } else ring.enabled = false
        }
        val last = st.lastMove?.takeIf { st.anim == null && st.phase != Phase.RESULT && st.phase != Phase.SELECTED }
        lastRings.forEachIndexed { i, ring ->
            val c = last?.group?.getOrNull(i)?.let { Hex.next(it, last.dir) }
            if (c != null && c >= 0) { setPos(ring, Layout.cell(c, 0.0010f)); ring.enabled = true } else ring.enabled = false
        }

        // ---------------- S08 alert: red rings flash 300 ms on the marbles / the grey pad (held while frozen)
        val al = st.alert
        val flashing = al != null && (st.freezeMs != null || (now - al.at < GameFlow.FLASH_MS && ((now - al.at) / 75) % 2 == 0L))
        alertRings.forEachIndexed { i, ring ->
            val c = al?.cells?.getOrNull(i)
            if (flashing && c != null) { setPos(ring, Layout.cell(c, 0.0020f)); ring.enabled = true } else ring.enabled = false
        }
        padAlertRing?.let { ring ->
            val p = al?.dir?.let { d -> st.pads.firstOrNull { it.dir == d } }
            if (flashing && p != null) { setPos(ring, Layout.pad(p) + V3(0f, Layout.PAD_T, 0f)); ring.enabled = true } else ring.enabled = false
        }

        // ---------------- hover (D50): a faint white ring where the pointer rests; never changes state
        hoverRing?.let { ring ->
            val name = hover?.takeIf { now - hoverAt < 250 }
            val c = name?.takeIf { it.startsWith("cell:") }?.substringAfter("cell:")?.toIntOrNull()
            if (c != null) { setPos(ring, Layout.cell(c, 0.0008f)); ring.enabled = true } else ring.enabled = false
        }

        // ---------------- gutter glow (S17): the stretch of gutter where the marble lands, 300 ms
        glow?.let { g ->
            if (dropping && !reduced) {
                val de = e - anim!!.slideMs - GameFlow.FLIP_MS
                val slot = Layout.slot(gutter.last().second)
                if (de in 0..GameFlow.ROLL_MS + 300 || (st.freezeMs != null && de >= -GameFlow.FLIP_MS)) {
                    val edge = gutter.last().second / 6
                    val ang = (60.0 * edge + 120.0) * PI / 180.0
                    setPos(g, V3(slot.x, -Layout.GUTTER_DEPTH + 0.0015f, slot.z))
                    g.transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), ang.toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat())))
                    g.enabled = true
                } else g.enabled = false
            } else g.enabled = false
        }
    }

    fun evidence(): String {
        val pc = Layout.plateCentre()
        return "plate=$pc plateW=${Layout.PLATE_W} plateH=${Layout.PLATE_H} nearEdge=${Layout.toScene(V3(0f, 0f, Layout.BOARD_APOTHEM))} " +
            "farEdge=${Layout.toScene(V3(0f, 0f, -Layout.BOARD_APOTHEM))} E5=${Layout.toScene(Layout.marble(Hex.at(0, 0)))} halfY=$regionHalfY"
    }

    fun dispose() {
        res?.close()
        res = null
    }

    companion object {
        sealed interface Hit
        data class CellHit(val cell: Int) : Hit
        data class PadHit(val dir: Int) : Hit
        data object EmptyHit : Hit

        /** A collider name -> what was tapped ("cell:<i>", "pad:<d>"; "board" / "gutter" / anything else = empty). */
        fun hitOf(name: String?): Hit {
            if (name == null) return EmptyHit
            val f = name.split(":")
            return when (f[0]) {
                "cell" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until Hex.N }?.let { CellHit(it) } ?: EmptyHit
                "pad" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..5 }?.let { PadHit(it) } ?: EmptyHit
                else -> EmptyHit
            }
        }

        fun known(name: String): Boolean = hitOf(name) != EmptyHit || name == "board" || name == "boardModel"
    }
}
