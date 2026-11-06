package io.embodit.abalonepush.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A small vector for layout maths (metres). Kept free of SDK types so it can be unit tested. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun len(): Float = sqrt(x * x + y * y + z * z)
}

/** An axis-aligned box. */
data class Box3(val min: V3, val max: V3) {
    fun contains(p: V3, eps: Float = 0f) = p.x > min.x - eps && p.x < max.x + eps && p.y > min.y - eps && p.y < max.y + eps && p.z > min.z - eps && p.z < max.z + eps

    /** Does the segment a-b pass through the box (slab test)? */
    fun hitBySegment(a: V3, b: V3): Boolean {
        var t0 = 0f; var t1 = 1f
        val d = b - a
        val ad = floatArrayOf(a.x, a.y, a.z); val dd = floatArrayOf(d.x, d.y, d.z)
        val lo = floatArrayOf(min.x, min.y, min.z); val hi = floatArrayOf(max.x, max.y, max.z)
        for (i in 0..2) {
            if (abs(dd[i]) < 1e-9f) { if (ad[i] < lo[i] || ad[i] > hi[i]) return false; continue }
            var ta = (lo[i] - ad[i]) / dd[i]; var tb = (hi[i] - ad[i]) / dd[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    companion object {
        fun around(c: V3, size: V3) = Box3(c - size * 0.5f, c + size * 0.5f)
    }
}

/**
 * Where everything sits in the board volume (design spatialLayout, container.volume 0.66 x 0.36 x 0.56 m). Scene space:
 * origin on the volume floor, centred in x and z, +Z toward the user, +Y up.
 *
 *   sceneRoot (volume floor)
 *   └ tiltRoot  at the board's near-bottom edge, tipped [TILT_DEG] about X (far edge up)
 *     └ boardRoot  board top centre (board-local: +X right, +Z toward the user, +Y the board normal)
 *       cells, marbles, pads, rings, the gutter: all board-local
 *   counter plate: scene space, standing upright (faces +Z) above the far rim
 */
object Layout {
    const val TILT_DEG = 20
    val THETA: Float = Math.toRadians(TILT_DEG.toDouble()).toFloat()

    /** Board: hexagon circumradius 0.30 (corners left / right), 3 cm thick; the field (cells) up to circumradius 0.235. */
    const val BOARD_R = 0.30f
    const val BOARD_T = 0.03f
    val BOARD_APOTHEM = BOARD_R * sqrt(3f) / 2f
    const val FIELD_R = 0.235f
    const val GUTTER_IN = 0.235f
    const val GUTTER_OUT = 0.28f
    const val GUTTER_DEPTH = 0.012f
    const val DIMPLE_R = 0.019f
    const val DIMPLE_DEPTH = 0.006f
    const val MARBLE_R = 0.017f
    /** Marble centre above the board top when resting in a dimple (sphere of the dimple R = (a² + h²) / 2h). */
    val MARBLE_Y: Float = run {
        val big = (DIMPLE_R * DIMPLE_R + DIMPLE_DEPTH * DIMPLE_DEPTH) / (2 * DIMPLE_DEPTH)
        -DIMPLE_DEPTH + big - (big - MARBLE_R)
    }
    const val LIFT = 0.008f
    /** Direction pads float with their underside this high over the board top (design: 4 cm). */
    const val PAD_Y = 0.040f
    const val PAD_T = 0.004f
    const val PAD_W = 0.032f
    const val PAD_D = 0.028f
    /** Pad collider: a flat square in board-local space, sized so the six n = 1 pads (5.2 cm apart, 60° steps) never overlap. */
    const val PAD_HIT = 0.038f
    /** Cell collider (thin box on the board top, 0.92 x pitch is the design's disc; a square 0.040 keeps the 60° neighbours apart). */
    const val CELL_HIT = 0.040f
    const val ARC = 0.04f
    const val ROLL = 0.06f

    val VOLUME = V3(0.66f, 0.36f, 0.56f)
    /** The near-bottom board edge sits 3 cm inside the volume's front face. */
    val TILT_ORIGIN = V3(0f, 0f, VOLUME.z / 2 - 0.03f)
    val BOARD_OFFSET = V3(0f, BOARD_T, -BOARD_APOTHEM)

    /** Counter plate (AttachmentPanel 160 x 60 mm, measured dp -> m below): upright above the far rim, bottom 4 cm over the board. */
    const val PLATE_W_DP = 232
    const val PLATE_H_DP = 87
    /** Measured on the emulator for AttachmentPanels (royal-ur v0.1.0: ~0.00069 m per dp). */
    const val DP_M = 0.00069f
    val PLATE_W: Float get() = PLATE_W_DP * DP_M
    val PLATE_H: Float get() = PLATE_H_DP * DP_M
    /** Plate: board-local z of its foot (far rim, 1 cm inside the outer edge) and its gap above the board surface there. */
    val PLATE_FOOT_LOCAL = V3(0f, 0f, -(BOARD_APOTHEM - 0.01f))
    const val PLATE_GAP = 0.04f

    private fun rotX(v: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }

    fun toScene(local: V3): V3 = TILT_ORIGIN + rotX(BOARD_OFFSET + local, THETA)
    fun toLocal(scene: V3): V3 = rotX(scene - TILT_ORIGIN, -THETA) - BOARD_OFFSET

    /** Board-local centre of a marble resting on cell [c] (+[lift] along the board normal). */
    fun marble(c: Int, lift: Float = 0f): V3 = V3(Hex.x(c), MARBLE_Y + lift, Hex.z(c))

    /** Board-local point of any axial position (cells, off-board steps, broadside centres). */
    fun axial(q: Float, r: Float, y: Float = 0f): V3 = V3(Hex.x(q, r), y, Hex.z(r))

    /** Board-local pad centre. */
    fun pad(p: Pad): V3 { val (q, r) = p.anchorQR(); return axial(q, r, PAD_Y + PAD_T / 2) }

    /** Board-local cell top (for rings / colliders). */
    fun cell(c: Int, y: Float = 0f): V3 = V3(Hex.x(c), y, Hex.z(c))

    // ---------------------------------------------------------------- gutter

    /** Centre circumradius of the gutter trough; 36 slots, six per edge. */
    val GUTTER_MID = (GUTTER_IN + GUTTER_OUT) / 2f
    const val SLOTS = 36
    /** Marble centre height when lying in the gutter trough. */
    val GUTTER_Y = -GUTTER_DEPTH + MARBLE_R

    /** Hexagon corner k (0 = right, counter-clockwise seen from above with +Z toward the user: k = 1 is far-right). */
    private fun corner(k: Int, rad: Float): Pair<Float, Float> {
        val a = Math.toRadians(-60.0 * k).toFloat()
        return rad * cos(a) to rad * sin(a)
    }

    fun slot(i: Int): V3 {
        val edge = i / 6
        val t = (i % 6 + 0.5f) / 6f
        val (x0, z0) = corner(edge, GUTTER_MID)
        val (x1, z1) = corner(edge + 1, GUTTER_MID)
        return V3(x0 + (x1 - x0) * t, GUTTER_Y, z0 + (z1 - z0) * t)
    }

    /** The gutter slot nearest to board-local point [p] among the free ones. */
    fun nearestFreeSlot(p: V3, taken: Set<Int>): Int =
        (0 until SLOTS).filter { it !in taken }.minByOrNull { val s = slot(it); (s.x - p.x) * (s.x - p.x) + (s.z - p.z) * (s.z - p.z) } ?: 0

    /** Where the lip is crossed by a marble leaving cell [c] in direction [d] (board-local, at the board top). */
    fun lipExit(c: Int, d: Int): V3 {
        val step = 0.026f / Hex.P
        return axial(Hex.Q[c] + Hex.DQ[d] * step, Hex.R[c] + Hex.DR[d] * step, MARBLE_Y)
    }

    /**
     * Every marble in the gutter: (colour side, slot) in the order they went out, replayed from the move list so a
     * restored game puts them back where they fell. Marbles already off in a debug start position go to the far / near
     * edge of their pusher.
     */
    fun gutter(start: Pos, moves: List<Move>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val taken = HashSet<Int>()
        for (side in 0..1) repeat(start.score[side]) {
            val colour = Abalone.other(side)
            val s = nearestFreeSlot(V3(0f, 0f, if (colour == Abalone.WHITE) -0.3f else 0.3f), taken)
            taken += s; out += colour to s
        }
        for (m in moves) {
            if (!m.drops) continue
            val last = m.pushed.last()
            val s = nearestFreeSlot(lipExit(last, m.dir), taken)
            taken += s; out += Abalone.other(m.side) to s
        }
        return out
    }

    /** A parabola from a to b at t peaking [arc] above the chord (board-local +Y). */
    fun arc(a: V3, b: V3, t: Float, arc: Float): V3 {
        val k = t.coerceIn(0f, 1f)
        return a.lerp(b, k) + V3(0f, 4f * arc * k * (1f - k), 0f)
    }

    /** Ease-out (decelerating) 0..1. */
    fun easeOut(t: Float): Float { val k = t.coerceIn(0f, 1f); return 1f - (1f - k) * (1f - k) * (1f - k) }

    // ---------------------------------------------------------------- plate + occlusion (D35)

    /** Scene centre of the counter plate: above the far rim, its bottom [PLATE_GAP] over the board surface there. */
    fun plateCentre(): V3 {
        val foot = toScene(PLATE_FOOT_LOCAL)
        return foot + V3(0f, PLATE_GAP + PLATE_H / 2, 0f)
    }

    fun plateCorners(): List<V3> {
        val c = plateCentre(); val w = PLATE_W / 2; val h = PLATE_H / 2
        return listOf(c + V3(-w, -h, 0f), c + V3(w, -h, 0f), c + V3(w, h, 0f), c + V3(-w, h, 0f))
    }

    /** Board slab (board-local box: the hexagon's bounding box, top at 0). */
    fun boardBoxLocal(): Box3 = Box3(V3(-BOARD_R, -BOARD_T, -BOARD_APOTHEM), V3(BOARD_R, 0.002f, BOARD_APOTHEM))

    /** A marble's bounding box (board-local) on cell [c], lifted when selected. */
    fun marbleBoxLocal(c: Int, lift: Float = 0f): Box3 = Box3.around(marble(c, lift), V3(2 * MARBLE_R, 2 * MARBLE_R, 2 * MARBLE_R))

    /** Does the eye -> target segment cross the plate rectangle (a thin box in scene space)? */
    fun plateBlocks(eye: V3, target: V3): Boolean {
        val c = plateCentre()
        return Box3.around(c, V3(PLATE_W, PLATE_H, 0.004f)).hitBySegment(eye, target)
    }

    /** Scene bounding box of a board-local box (8 corners transformed). */
    fun sceneBox(local: Box3): Box3 {
        val pts = ArrayList<V3>()
        for (x in listOf(local.min.x, local.max.x)) for (y in listOf(local.min.y, local.max.y)) for (z in listOf(local.min.z, local.max.z)) pts += toScene(V3(x, y, z))
        return Box3(V3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }), V3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z }))
    }

    fun volume(): Box3 = Box3(V3(-VOLUME.x / 2, 0f, -VOLUME.z / 2), V3(VOLUME.x / 2, VOLUME.y, VOLUME.z / 2))

    /**
     * Eyes for the occlusion checks: the measured emulator reset (eye about level with the volume centre, 0.7 / 1.2 / 1.9 / 2.5 m
     * in front, azimuth 0 / ±30°) plus the design's sit (0.45 m high, 0.6 m behind the near edge), sit low / high and stand.
     */
    fun eyes(): Map<String, V3> {
        val out = LinkedHashMap<String, V3>()
        for (d in listOf(0.7f, 1.2f, 1.9f, 2.5f)) for (az in listOf(0, -30, 30)) {
            val a = Math.toRadians(az.toDouble())
            out["reset d=$d az=$az"] = V3((d * sin(a)).toFloat(), VOLUME.y / 2, (d * cos(a)).toFloat())
        }
        val near = TILT_ORIGIN.z
        out["sit"] = V3(0f, 0.45f, near + 0.6f)
        out["sit low"] = V3(0f, 0.35f, near + 0.5f)
        out["sit high"] = V3(0f, 0.50f, near + 0.5f)
        out["stand"] = V3(0f, 0.90f, near + 0.5f)
        return out
    }
}
