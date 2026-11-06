package io.embodit.abalonepush.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.abalonepush.BuildConfig
import io.embodit.abalonepush.R
import io.embodit.abalonepush.domain.Abalone
import io.embodit.abalonepush.domain.Ai
import io.embodit.abalonepush.domain.ConfirmKind
import io.embodit.abalonepush.domain.EndReason
import io.embodit.abalonepush.domain.GameSetup
import io.embodit.abalonepush.domain.Hex
import io.embodit.abalonepush.domain.Language
import io.embodit.abalonepush.domain.Mode
import io.embodit.abalonepush.domain.Pad
import io.embodit.abalonepush.domain.PadKind
import io.embodit.abalonepush.domain.Phase
import io.embodit.abalonepush.domain.PlayState
import io.embodit.abalonepush.domain.SeatPick
import io.embodit.abalonepush.domain.Settings
import io.embodit.abalonepush.platform.Graph
import io.embodit.abalonepush.platform.TAG
import io.embodit.abalonepush.ui.Page
import io.embodit.abalonepush.ui.Ui
import io.embodit.abalonepush.ui.board.MarbleDot
import io.embodit.abalonepush.ui.sideName
import io.embodit.abalonepush.ui.statusText
import io.embodit.abalonepush.ui.theme.AbColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer to the right of the board): all 2D UI, four pages switched by the native
 * SpatialUI TabBar (D8): Game / New / Rules / Settings. Every page has the same header with [静音]; its content fills the
 * window (blank <= 25 %); confirmations are a whole-page card and lock the volume. The game page mirrors the six 3D
 * direction pads as six keys (D43: the core action has a panel key; same GameFlow.tapPad call as a pinch on a pad).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the rules page opens beside the running default game; tapping a marble ends the welcome.
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.SETUP -> Ui.page = Page.NEW
            Phase.SELECTED, Phase.MOVING -> if (Ui.page == Page.RULES && !state.welcome || Ui.page == Page.NEW && !Ui.newConfirm) Ui.page = Page.PLAY
            Phase.IDLE, Phase.THINKING -> if (Ui.page == Page.NEW && !Ui.newConfirm) Ui.page = Page.PLAY
            else -> Unit
        }
    }
    LaunchedEffect(Ui.exitConfirm, Ui.newConfirm) { graph.flow.uiLocked = Ui.exitConfirm || Ui.newConfirm }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
        if (Ui.page != Page.NEW) Ui.newConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.NEW -> NewPage(state, settings, graph)
            Page.RULES -> RulesPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        listOf(
            Triple(Page.PLAY, R.drawable.ic_tab_play, R.string.tab_play),
            Triple(Page.NEW, R.drawable.ic_tab_new, R.string.tab_new),
            Triple(Page.RULES, R.drawable.ic_tab_rules, R.string.tab_rules),
            Triple(Page.SETTINGS, R.drawable.ic_tab_settings, R.string.tab_settings),
        ).forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = stringResource(label)) },
                supportContent = { Txt(stringResource(label), "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

/** Every page: header at the top, last row at the bottom, blocks share the height in between (blank <= 25 %). */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

/** Page header (56): a title, a small line under it and [静音] on every page. */
@Composable
private fun Header(title: String, tag: String, settings: Settings, graph: Graph, sub: String? = null, side: Int? = null, thinking: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (side != null) { MarbleDot(side, 22, lit = true); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge)
            if (sub != null) Txt(sub, "$tag/sub", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        }
        if (thinking) { CircularProgressIndicator(modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)) }
        ToggleButton(stringResource(R.string.btn_mute), "$tag/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } })
    }
}

@Composable
private fun levelName(level: Int): String = stringResource(when (level) { Ai.EASY -> R.string.level_1_sub; Ai.MEDIUM -> R.string.level_2_sub; else -> R.string.level_3_sub })

@Composable
private fun modeLine(state: PlayState): String {
    val cfg = state.config ?: return ""
    return if (cfg.mode == Mode.TWO_PLAYER) stringResource(R.string.mode_2p) else stringResource(R.string.mode_ai_level, levelName(cfg.aiLevel))
}

@Composable
private fun turnTitle(state: PlayState): String {
    val cfg = state.config ?: return ""
    val side = state.shownSide
    val name = sideName(side)
    return if (cfg.mode == Mode.TWO_PLAYER) stringResource(R.string.turn_of, name)
    else stringResource(R.string.turn_of_who, name, stringResource(if (side == cfg.humanSide) R.string.who_you else R.string.who_ai))
}

// ------------------------------------------------------------------ Game

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    if (state.config == null || state.phase in setOf(Phase.SETUP, Phase.LOADING)) {
        PageColumn {
            Header(stringResource(R.string.tab_play), "play", settings, graph)
            RulesDiagram(Modifier.fillMaxWidth().height(200.dp))
            Txt(stringResource(R.string.new_note), "play/empty", maxLines = 2, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            PrimaryButton(stringResource(R.string.btn_start), "play/new", onClick = { Ui.page = Page.NEW }, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val confirm = state.confirm
    if (confirm != null) { ConfirmPage(confirm, settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() }); return }
    if (state.phase == Phase.RESULT) { ResultPage(state, settings, graph); return }
    PageColumn {
        Header(turnTitle(state), "play", settings, graph, sub = modeLine(state), side = state.shownSide, thinking = state.phase == Phase.THINKING)
        ScoreRow(state)
        StatusCard(state)
        DirectionKeys(state, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.canRedo) SecondaryButton(stringResource(R.string.btn_redo), "play/redo", onClick = { flow.redo() }, icon = R.drawable.ic_redo, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, enabled = state.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            ToggleButton(stringResource(if (state.hintOn) R.string.hint_on else R.string.hint_off), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, modifier = Modifier.weight(1.1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_resign), "play/resign", onClick = { flow.requestConfirm(ConfirmKind.RESIGN) }, icon = R.drawable.ic_flag, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_restart), "play/restart", onClick = { flow.requestConfirm(ConfirmKind.RESTART) }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f))
        }
    }
}

/** "● Black 2 · ○ White 1 · first to 6" (the same numbers as the counter plate, D38). */
@Composable
private fun ScoreRow(state: PlayState) {
    val pos = state.shownPos
    Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(AbColors.TagFace).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(stringResource(R.string.score_line), "play/score-label", style = PicoTheme.typography.bodyMedium, color = AbColors.InkSecondary)
        Spacer(Modifier.width(12.dp))
        for (side in listOf(Abalone.BLACK, Abalone.WHITE)) {
            MarbleDot(side, 18, lit = side == state.shownSide)
            Spacer(Modifier.width(6.dp))
            Txt(sideName(side) + " " + pos.score[side], "play/score-$side", style = PicoTheme.typography.titleMedium, color = AbColors.Ink)
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.weight(1f))
        Txt(stringResource(R.string.first_to_6), "play/goal", style = PicoTheme.typography.bodyMedium, color = AbColors.InkSecondary)
    }
}

/** The one sentence (long form; the plate carries the short form of the same status). */
@Composable
private fun StatusCard(state: PlayState) {
    Box(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(18.dp)).background(AbColors.TagFace).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        Txt(statusText(state, short = false), "play/status", style = PicoTheme.typography.titleMedium, color = if (state.alert != null) AbColors.ErrorText else AbColors.Ink, maxLines = 2)
    }
}

/** Screen rotation of the arrow icon per board direction (far = up). */
private val DIR_DEG = floatArrayOf(90f, 30f, -30f, -90f, -150f, 150f)
/** Compass layout: far-left / far-right, left / right, near-left / near-right. */
private val DIR_ROWS = listOf(listOf(2, 1), listOf(3, 0), listOf(4, 5))
private val DIR_LABEL = intArrayOf(R.string.dir_0, R.string.dir_1, R.string.dir_2, R.string.dir_3, R.string.dir_4, R.string.dir_5)

/**
 * The six pads as keys (D43). Same colours as the 3D pads; a grey key still answers with the reason (S08); a direction
 * whose target is an own marble has no pad and its key is off. Before a marble is picked: one line + the colour legend.
 */
@Composable
private fun DirectionKeys(state: PlayState, graph: Graph) {
    val active = state.phase == Phase.SELECTED && state.humansTurn
    Column(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(18.dp)).background(AbColors.TagFace.copy(alpha = 0.55f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(stringResource(if (active) R.string.dir_title else R.string.dir_none), "play/dir-title", style = PicoTheme.typography.labelLarge, color = AbColors.Ink, maxLines = 1)
        DIR_ROWS.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { d ->
                    val pad = state.pads.firstOrNull { it.dir == d }.takeIf { active }
                    DirKey(d, pad, active, Modifier.weight(1f).fillMaxHeight()) { graph.flow.tapPad(d) }
                }
            }
        }
        Txt(stringResource(R.string.legend), "play/legend", style = PicoTheme.typography.bodySmall, color = AbColors.InkSecondary, maxLines = 1)
    }
}

@Composable
private fun DirKey(d: Int, pad: Pad?, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (bg, fg) = when (pad?.kind) {
        PadKind.MOVE -> AbColors.Primary to AbColors.OnPrimary
        PadKind.PUSH -> AbColors.Tertiary to AbColors.OnTertiary
        PadKind.BLOCKED -> Color(0xFFD9D6D0) to AbColors.InkSecondary
        else -> Color(0xFFEDEAE4) to AbColors.InkSecondary
    }
    Button(
        onClick = onClick, enabled = active && pad != null, modifier = modifier.then(if (pad?.drops == true) Modifier.border(3.dp, AbColors.Error, RoundedCornerShape(16.dp)) else Modifier),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 80.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
    ) {
        Icon(painter = painterResource(R.drawable.ic_arrow), contentDescription = null, modifier = Modifier.size(20.dp).rotate(DIR_DEG[d]), tint = fg)
        Spacer(Modifier.width(6.dp))
        val dots = if (pad?.kind == PadKind.PUSH) " " + "●".repeat(pad.pushCount) else ""
        // design-style: inherited-content-color Button
        Txt(stringResource(DIR_LABEL[d]) + dots, "play/dir-$d", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** S13: a whole-page confirmation card; the volume is locked while it is open (GameFlow ignores 3D taps). */
@Composable
private fun ConfirmPage(kind: ConfirmKind?, settings: Settings, graph: Graph, onYes: () -> Unit, onNo: () -> Unit, exit: Boolean = false, newGame: Boolean = false) {
    val (q, line, yes) = when {
        newGame -> Triple(R.string.confirm_new_game, R.string.confirm_new_line, R.string.confirm_yes_new)
        exit -> Triple(R.string.confirm_exit, R.string.confirm_exit_line, R.string.confirm_yes_exit)
        kind == ConfirmKind.RESIGN -> Triple(R.string.confirm_resign, R.string.confirm_resign_line, R.string.confirm_yes_resign)
        else -> Triple(R.string.confirm_restart, R.string.confirm_restart_line, R.string.confirm_yes_restart)
    }
    val icon = when { exit -> R.drawable.ic_exit; kind == ConfirmKind.RESIGN && !newGame -> R.drawable.ic_flag; else -> R.drawable.ic_replay }
    PageColumn {
        Header(stringResource(when { exit -> R.string.settings_title; newGame -> R.string.new_title; else -> R.string.tab_play }), "confirm", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(AbColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp), tint = AbColors.Ink)
            Txt(stringResource(q), "confirm/q", style = PicoTheme.typography.titleLarge.copy(fontSize = 30.sp), color = AbColors.Ink, maxLines = 2)
            Txt(stringResource(line), "confirm/line", style = PicoTheme.typography.bodyLarge, color = AbColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(72.dp))
                SecondaryButton(stringResource(yes), "confirm/yes", danger = true, onClick = onYes, modifier = Modifier.weight(1f).height(72.dp))
            }
        }
        Txt(stringResource(R.string.lock_line), "confirm/lock", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** S09: the result card (whole page, [静音] still in the header). */
@Composable
private fun ResultPage(state: PlayState, settings: Settings, graph: Graph) {
    val cfg = state.config ?: return
    val w = state.winner
    val title = stringResource(when {
        w == null -> R.string.result_draw
        cfg.mode == Mode.AI && w == cfg.humanSide -> R.string.result_you_win
        cfg.mode == Mode.AI -> R.string.result_you_lose
        w == Abalone.BLACK -> R.string.result_black_win
        else -> R.string.result_white_win
    })
    val reason = stringResource(when (state.endReason) { EndReason.REPETITION -> R.string.reason_rep; EndReason.NO_DROP -> R.string.reason_nodrop; EndReason.RESIGN -> R.string.reason_resign; else -> R.string.reason_six })
    val secs = state.elapsedMs / 1000
    val time = "%d:%02d".format(secs / 60, secs % 60)
    PageColumn {
        Header(stringResource(R.string.tab_play), "result", settings, graph, sub = modeLine(state))
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp).clip(RoundedCornerShape(28.dp)).background(AbColors.TagFace).padding(22.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Txt(title, "result/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold), color = AbColors.Ink)
            Txt(reason, "result/reason", style = PicoTheme.typography.bodyLarge, color = AbColors.InkSecondary)
            val sc = state.pos.score
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(stringResource(R.string.res_score), "result/score-label", style = PicoTheme.typography.bodyMedium, color = AbColors.InkSecondary)
                MarbleDot(Abalone.BLACK, 18); Txt("${sideName(Abalone.BLACK)} ${sc[0]}", "result/b", style = PicoTheme.typography.titleMedium, color = AbColors.Ink)
                Txt(":", "result/colon", style = PicoTheme.typography.titleMedium, color = AbColors.Ink)
                MarbleDot(Abalone.WHITE, 18); Txt("${sideName(Abalone.WHITE)} ${sc[1]}", "result/w", style = PicoTheme.typography.titleMedium, color = AbColors.Ink)
            }
            val cells = listOf(
                R.string.res_moves to state.moves.size.toString(), R.string.res_time to time, R.string.res_hints to state.hintsUsed.toString(),
                R.string.res_level to if (cfg.mode == Mode.TWO_PLAYER) stringResource(R.string.mode_2p) else levelName(cfg.aiLevel),
            )
            cells.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, value) -> StatCell(stringResource(label), value, Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_menu), "result/menu", onClick = { graph.flow.toMenu() }, modifier = Modifier.weight(1f).height(56.dp))
            }
        }
        Txt(stringResource(R.string.result_footnote), "result/footnote", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).border(1.dp, AbColors.InkSecondary.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "result/stat", style = PicoTheme.typography.bodyMedium, color = AbColors.InkSecondary, modifier = Modifier.weight(1f))
        Txt(value, "result/stat-v", style = PicoTheme.typography.titleMedium, color = AbColors.Ink)
    }
}

// ------------------------------------------------------------------ New game

@Composable
private fun NewPage(state: PlayState, settings: Settings, graph: Graph) {
    val setup = settings.lastSetup
    fun set(change: (GameSetup) -> GameSetup) = graph.settings.update { it.copy(lastSetup = change(it.lastSetup)) }
    fun start() { Ui.newConfirm = false; graph.flow.newGame(setup); Ui.page = Page.PLAY }
    if (Ui.newConfirm) {
        // D44 ③: starting over drops the running game, ask first (whole-page card).
        ConfirmPage(null, settings, graph, onYes = { start() }, onNo = { Ui.newConfirm = false }, newGame = true)
        return
    }
    PageColumn {
        Header(stringResource(R.string.new_title), "new", settings, graph)
        if (state.savedAvailable && state.phase == Phase.SETUP) {
            SecondaryButton(stringResource(R.string.btn_continue, state.savedMoves), "new/continue", onClick = { graph.flow.continueSaved(); Ui.page = Page.PLAY }, modifier = Modifier.fillMaxWidth().height(56.dp))
        }
        Group(stringResource(R.string.new_who), "new/opponent") {
            // gate:allow tabs-segment same-page option of the new game (titled group: opponent / seat / level), not a page switch
            Segments(listOf(Mode.AI to stringResource(R.string.vs_ai), Mode.TWO_PLAYER to stringResource(R.string.mode_2p)), setup.mode, "new/opponent", { m -> set { it.copy(mode = m) } }, Modifier.fillMaxWidth())
        }
        if (setup.mode == Mode.AI) {
            Group(stringResource(R.string.new_seat), "new/seat") {
                // gate:allow tabs-segment same-page option of the new game (titled group: opponent / seat / level), not a page switch
                Segments(listOf(SeatPick.BLACK to stringResource(R.string.seat_black), SeatPick.WHITE to stringResource(R.string.seat_white), SeatPick.RANDOM to stringResource(R.string.seat_random)),
                    setup.seat, "new/seat", { v -> set { it.copy(seat = v) } }, Modifier.fillMaxWidth())
            }
            Group(stringResource(R.string.new_level), "new/level") {
                // gate:allow tabs-segment same-page option of the new game (titled group: opponent / seat / level), not a page switch
                Segments(listOf(Ai.EASY to stringResource(R.string.level_1), Ai.MEDIUM to stringResource(R.string.level_2), Ai.HARD to stringResource(R.string.level_3)),
                    setup.aiLevel, "new/level", { l -> set { it.copy(aiLevel = l) } }, Modifier.fillMaxWidth(),
                    subs = listOf(stringResource(R.string.level_1_sub), stringResource(R.string.level_2_sub), stringResource(R.string.level_3_sub)))
            }
        } else {
            Column(Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(18.dp)).background(AbColors.TagFace).padding(18.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarbleDot(Abalone.BLACK, 22); Spacer(Modifier.width(8.dp)); Txt(stringResource(R.string.seat_black), "new/2p-b", style = PicoTheme.typography.titleMedium, color = AbColors.Ink)
                    Spacer(Modifier.width(24.dp))
                    MarbleDot(Abalone.WHITE, 22); Spacer(Modifier.width(8.dp)); Txt(stringResource(R.string.seat_white), "new/2p-w", style = PicoTheme.typography.titleMedium, color = AbColors.Ink)
                }
                Txt(stringResource(R.string.new_2p_note), "new/2p-note", style = PicoTheme.typography.bodyMedium, color = AbColors.InkSecondary, maxLines = 2)
            }
        }
        PrimaryButton(stringResource(R.string.btn_start), "new/start", onClick = {
            if (state.hasProgress) { Ui.newConfirm = true; Log.i(TAG, "CONFIRM NEW_GAME S13") } else start()
        }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.new_note), "new/note", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** A titled option group: title 20 + a full-width segment (same-page option switch, never a page switch). */
@Composable
private fun Group(title: String, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, "$tag/title", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) { content() }
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RulesDiagram(Modifier.size(width = 168.dp, height = 132.dp))
                Txt(stringResource(R.string.diagram_caption), "rules/caption", style = PicoTheme.typography.bodySmall, color = AbColors.InkSecondary, maxLines = 2, align = TextAlign.Center, modifier = Modifier.width(168.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(AbColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = AbColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line", style = PicoTheme.typography.bodySmall, maxLines = 5, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3).forEach { id ->
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(AbColors.TagFace).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, color = AbColors.Ink, maxLines = 2)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall.copy(fontSize = 11.sp), color = PicoTheme.colorScheme.labelSecondary, maxLines = 3, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            // S01 [开始]: the default game is already set up; this only goes to the game page and ends the welcome.
            PrimaryButton(stringResource(if (state.welcome) R.string.btn_start else R.string.btn_back_game), "rules/start", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY })
        }
    }
}

/** Three white marbles pushing two black ones along a row; the front black one drops over the rim into the gutter. */
@Composable
private fun RulesDiagram(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(AbColors.Primary)) {
        val r = size.height * 0.085f
        val pitch = r * 2.6f
        val cy = size.height * 0.48f
        val x0 = size.width * 0.12f
        // the gutter on the right
        drawRoundRect(AbColors.Walnut, topLeft = Offset(size.width * 0.86f, 0f), size = androidx.compose.ui.geometry.Size(size.width * 0.14f, size.height))
        for (i in 0 until 6) {
            val c = Offset(x0 + i * pitch, cy)
            drawCircle(AbColors.Wood, r * 1.18f, c, style = Stroke(r * 0.18f))
        }
        val colours = listOf(AbColors.MarbleWhite, AbColors.MarbleWhite, AbColors.MarbleWhite, AbColors.MarbleBlack, AbColors.MarbleBlack)
        colours.forEachIndexed { i, col -> drawCircle(col, r, Offset(x0 + (i + 1) * pitch, cy)) }
        // the dropping black marble over the rim, with its arc
        val drop = Offset(size.width * 0.93f, size.height * 0.78f)
        drawCircle(AbColors.MarbleBlack, r, drop)
        drawCircle(AbColors.Tertiary, r * 1.35f, drop, style = Stroke(r * 0.22f))
        // push arrow under the white line
        val ay = cy + r * 2.2f
        drawLine(AbColors.Tertiary, Offset(x0 + pitch * 0.6f, ay), Offset(x0 + pitch * 3.4f, ay), strokeWidth = r * 0.35f)
        drawLine(AbColors.Tertiary, Offset(x0 + pitch * 3.4f, ay), Offset(x0 + pitch * 3.0f, ay - r * 0.6f), strokeWidth = r * 0.35f)
        drawLine(AbColors.Tertiary, Offset(x0 + pitch * 3.4f, ay), Offset(x0 + pitch * 3.0f, ay + r * 0.6f), strokeWidth = r * 0.35f)
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    if (Ui.exitConfirm) {
        ConfirmPage(null, settings, graph, onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false }, exit = true)
        return
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Header(stringResource(R.string.settings_title), "settings", settings, graph)
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 140.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = 140.dp) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume", labelWidth = 140.dp) {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = 140.dp) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_coach), "settings/coach", labelWidth = 140.dp) {
            Segments(onOff, settings.coach, "settings/coach", { v -> upd { it.copy(coach = v) }; graph.flow.debugState { st -> st.copy(coachOn = v && st.moves.size < 6) } }, Modifier.weight(1f))
        }
        SecondaryButton(stringResource(R.string.settings_reopen), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.settings_reopen_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(140.dp))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // D26: quit is the last row; the confirmation is a whole-page card.
        SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

@Suppress("unused")
private fun cellName(c: Int) = Hex.name(c)
