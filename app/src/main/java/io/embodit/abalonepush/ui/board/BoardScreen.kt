package io.embodit.abalonepush.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.abalonepush.R
import io.embodit.abalonepush.domain.Abalone
import io.embodit.abalonepush.domain.Layout
import io.embodit.abalonepush.domain.Phase
import io.embodit.abalonepush.domain.PlayState
import io.embodit.abalonepush.platform.Graph
import io.embodit.abalonepush.platform.TAG
import io.embodit.abalonepush.ui.board.input.BoardInputConfig
import io.embodit.abalonepush.ui.board.input.BoardInputListener
import io.embodit.abalonepush.ui.board.input.InputSource
import io.embodit.abalonepush.ui.board.input.TargetResolver
import io.embodit.abalonepush.ui.board.input.boardInput
import io.embodit.abalonepush.ui.statusText
import io.embodit.abalonepush.ui.theme.AbColors
import io.embodit.abalonepush.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
}

private const val PLATE = "counterPlate"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the board,
 * and routes every pinch / trigger on a collider (marble, cell, pad, board) to [GameFlow] through [route].
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // D50: the factory board-input template (taps + short drags the system reports as drags, targets resolved up
            // the parent chain) for hand ray, controller ray and eye-hand alike.
            .boardInput(remember(scene) { BoardTargets(scene) }, remember(graph) { BoardListener(graph, scene) }, BoardInputConfig(logTag = TAG)),
        attachments = {
            AttachmentPanel(PLATE) { PlateRoot(graph) }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧兜底 平六角板"), removed when board.glb is in
                    val slab = MeshResource.createBox(Vector3(2 * Layout.BOARD_R * 0.86f, Layout.BOARD_T, 2 * Layout.BOARD_APOTHEM), cornerRadius = 0.02f)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.42f, 0.29f, 0.17f, 1f)); setRoughness(0.8f) }
                    slab to mat
                }.getOrNull()
            }
            quick?.let { (s, m) -> scene.showFallback(s, m) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            suspend fun load(n: String): Entity = Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) }
            val models = runCatching {
                val pads = LinkedHashMap<String, Entity>()
                for (v in listOf("blue", "amber1", "amber2", "amber1red", "amber2red", "grey")) pads[v] = load("pad_$v")
                Models(load("board"), listOf(load("marble_black"), load("marble_white")), pads)
            }.onFailure { Log.e(TAG, "MODELS failed", it) }.getOrNull()
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            Log.i(TAG, "MODELS loaded=${models != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            if (models != null) scene.install(resources, models, attachments.entity(PLATE))
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings)
        },
    )
}

/** Hit entity (or an ancestor) -> the collider name the game knows: cells (marbles carry their cell's name), pads, board. */
internal class BoardTargets(@Suppress("unused") private val scene: BoardScene) : TargetResolver<String> {
    override fun resolve(entity: Entity): String? = runCatching { entity.getName() }.getOrNull()?.let { BoardNames.resolveName(it) }

    fun resolveName(entity: Entity?): String? = io.embodit.abalonepush.ui.board.input.resolveUp(entity, this).first
}

/** Every resolved tap goes through [route] (the same calls the panel and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        Log.i(TAG, "PINCH target=$target src=${source.label}")
        route(graph, target)
    }

    /** D50 hover: a light ring on the cell / a 4 mm lift of the pad the pointer rests on (never changes state). */
    override fun onHover(target: String?, source: InputSource) = scene.setHover(target)
}

/** Collider name -> the one flow call (the same calls the debug broadcast makes). */
internal fun route(graph: Graph, name: String?) = BoardNames.dispatch(graph.flow, name)

/** The plate is its own composition: it reads state + language itself so a language switch always reaches it. */
@Composable
private fun PlateRoot(graph: Graph) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) { CounterPlate(st, graph) }
    }
}

@Composable
private fun plateText(text: String, tag: String, size: Int, weight: FontWeight = FontWeight.SemiBold, color: Color = AbColors.Ink) {
    Text(text, color = color, fontSize = size.sp, fontWeight = weight, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") })
}

/** A small marble dot; [lit] = a ring of the primary colour around it (whose turn). */
@Composable
internal fun MarbleDot(side: Int, size: Int, lit: Boolean = false) {
    Box(
        Modifier.size((size + if (lit) 6 else 0).dp).clip(CircleShape).background(if (lit) AbColors.Tertiary else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(if (side == Abalone.BLACK) AbColors.MarbleBlack else AbColors.MarbleWhite).border(1.dp, AbColors.Ink, CircleShape))
    }
}

/**
 * Counter plate (design spatialLayout.attachments, 160 x 60 mm, two lines): the score "● 2 · ○ 1 · first to 6" with the
 * side to move ringed, then the same status sentence as the window (D38 / D45). No buttons except "open panel" after ×.
 */
@Composable
private fun CounterPlate(st: PlayState, graph: Graph) {
    val window = graph.window
    Box(
        Modifier.size(Layout.PLATE_W_DP.dp, Layout.PLATE_H_DP.dp).clip(RoundedCornerShape(14.dp)).background(AbColors.TagFace),
        contentAlignment = Alignment.Center,
    ) {
        when {
            window.levelHint -> plateText(stringResource(R.string.level_hint), "plate/level", 14)
            window.panelState == "ON_DESTROY" -> Button(onClick = { window.request("plate") }) {
                // design-style: inherited-content-color Button
                Text(stringResource(R.string.open_panel), fontSize = 14.sp)
            }
            else -> Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val pos = st.shownPos
                val turn = st.shownSide.takeIf { st.config != null && st.phase != Phase.RESULT && st.phase != Phase.SETUP }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarbleDot(Abalone.BLACK, 14, lit = turn == Abalone.BLACK)
                    Spacer(Modifier.width(4.dp))
                    plateText(pos.score[Abalone.BLACK].toString(), "plate/b", 22, FontWeight.Bold)
                    plateText("  ·  ", "plate/sep", 18)
                    MarbleDot(Abalone.WHITE, 14, lit = turn == Abalone.WHITE)
                    Spacer(Modifier.width(4.dp))
                    plateText(pos.score[Abalone.WHITE].toString(), "plate/w", 22, FontWeight.Bold)
                    plateText("  ·  " + stringResource(R.string.first_to_6), "plate/goal", 18)
                }
                val line = statusText(st, short = true)
                plateText(line, "plate/status", 16, FontWeight.Medium, if (st.alert != null) AbColors.ErrorText else AbColors.Ink)
            }
        }
    }
}
