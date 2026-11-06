package io.embodit.abalonepush.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.abalonepush.domain.Abalone
import io.embodit.abalonepush.domain.Ai
import io.embodit.abalonepush.domain.ConfirmKind
import io.embodit.abalonepush.domain.GameConfig
import io.embodit.abalonepush.domain.GameFlow
import io.embodit.abalonepush.domain.GameSetup
import io.embodit.abalonepush.domain.Hex
import io.embodit.abalonepush.domain.Language
import io.embodit.abalonepush.domain.Mode
import io.embodit.abalonepush.domain.Pos
import io.embodit.abalonepush.domain.SeatPick
import io.embodit.abalonepush.domain.Settings
import io.embodit.abalonepush.ui.Page
import io.embodit.abalonepush.ui.Ui
import io.embodit.abalonepush.ui.board.SceneHolder
import io.embodit.abalonepush.ui.board.route
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.abalonepush.DEBUG -p io.embodit.abalonepush --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S17 [variant]   reach a named state (AGENTS.md lists them; S05 / S08 / S17 are held by freeze)
 *   tap <A1..I9 | q,r | pad:d | cell:i | board>   a pinch on that collider          sel <cells,…>   tap them in order
 *   push <d0..d5 | 0..5>      the pad of that direction      move "<cells> <d>"   select + push
 *   new "black|white|random ai|2p 1|2|3"   newmode ai|2p (new-game page only)   setup "b:E5,E6 w:E8 s:0,2 side:b [2p]"   level 1..3
 *   undo · redo · hint on|off · resign · restart · confirm · no · exitask · page play|new|rules|settings
 *   lang zh|en|system · sound on|off · volume 0-2 · reduced on|off · coach on|off · seed N · aitime MS|off
 *   freeze MS|off · hold S17|on|off (hold S17 = the next drop stops in mid-air) · s00hold on|off · welcome
 *   autoplay on|off (both sides AI, hard) · reopen · closepanel · hover <collider> · dump|state · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    companion object {
        private var autoJob: Job? = null
        /** S04 / S08 / S17 sample position (design sampleFirstFrame second screen): black E5-E7 against white E8-E9. */
        const val PUSH_SPEC = "b:E5,E6,E7,A1,A2,A3,B1,B2,B3,C2,C3,D3 w:E8,E9,F7,D7,I5,I6,I7,I8,H5,H6,H7,G6,G7,F8 s:0,2 side:b"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun cells(s: String): List<Int> = s.split(",", " ").filter { it.isNotBlank() }.mapNotNull { Hex.parse(it) }
        fun dir(s: String): Int? = s.trim().removePrefix("d").toIntOrNull()?.takeIf { it in 0..5 }
        fun defaultGame(human: Int = Abalone.BLACK, mode: Mode = Mode.AI) = flow.newGame(GameConfig(mode, Ai.MEDIUM, human))
        fun setup(spec: String) {
            val f = spec.split(" ").filter { it.isNotBlank() }
            val cellsArr = ByteArray(Hex.N)
            var score = intArrayOf(0, 0)
            var side = Abalone.BLACK
            var mode = Mode.AI
            for (t in f) when {
                t.startsWith("b:") -> cells(t.drop(2)).forEach { cellsArr[it] = 1 }
                t.startsWith("w:") -> cells(t.drop(2)).forEach { cellsArr[it] = 2 }
                t.startsWith("s:") -> t.drop(2).split(",").map { it.toInt() }.let { score = intArrayOf(it[0], it[1]) }
                t.startsWith("side:") -> side = if (t.endsWith("w")) Abalone.WHITE else Abalone.BLACK
                t == "2p" -> mode = Mode.TWO_PLAYER
            }
            val cfg = GameConfig(mode, flow.state.value.config?.aiLevel ?: Ai.MEDIUM, if (mode == Mode.AI) side else Abalone.BLACK)
            flow.newGame(cfg, Pos(cellsArr, score), side)
        }
        when (cmd) {
            "goto" -> {
                val f = a.split(" ")
                flow.freeze(null); flow.holdThinking = false
                when (f[0].uppercase()) {
                    "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: s00hold on, force-stop and start, then capture")
                    "S01" -> { settings { it.copy(firstRunDone = false) }; flow.start() }
                    "S02" -> { flow.toMenu(); Ui.page = Page.NEW }
                    "S03" -> { defaultGame(); Ui.page = Page.PLAY }
                    "S04" -> {
                        Ui.page = Page.PLAY
                        when (f.getOrNull(1)) {
                            "1" -> { defaultGame(); flow.selectCells(cells("C5")) }
                            "2" -> { defaultGame(); flow.selectCells(cells("C4,C5")) }
                            "3" -> { defaultGame(); flow.selectCells(cells("C3,C4,C5")) }
                            else -> { setup(PUSH_SPEC); flow.selectCells(cells("E5,E6,E7")) }
                        }
                    }
                    "S05" -> { setup(PUSH_SPEC); Ui.page = Page.PLAY; flow.selectCells(cells("E5,E6,E7")); flow.freeze(f.getOrNull(1)?.toLongOrNull() ?: 150L); flow.tapPad(0) }
                    "S06" -> { flow.holdThinking = true; defaultGame(Abalone.WHITE); Ui.page = Page.PLAY }
                    "S08" -> {
                        setup(PUSH_SPEC); Ui.page = Page.PLAY; flow.selectCells(cells("E5,E6,E7"))
                        flow.freeze(60_000)
                        when (f.getOrNull(1)) { "line" -> { flow.cancelSelection(); flow.selectCells(cells("A1,A2,B3")) }; else -> flow.tapPad(1) }
                    }
                    "S09" -> { setup(PUSH_SPEC.replace("s:0,2", "s:5,2")); Ui.page = Page.PLAY; flow.selectCells(cells("E5,E6,E7")); flow.tapPad(0) }
                    "S11" -> Ui.page = Page.SETTINGS
                    "S12" -> Ui.page = Page.RULES
                    "S13" -> { Ui.page = Page.PLAY; flow.requestConfirm(if (f.getOrNull(1) == "resign") ConfirmKind.RESIGN else ConfirmKind.RESTART) }
                    "S16" -> { Log.i(TAG, "restore=${flow.continueSaved()}"); Ui.page = Page.PLAY }
                    "S17" -> {
                        setup(PUSH_SPEC); Ui.page = Page.PLAY; flow.selectCells(cells("E5,E6,E7"))
                        flow.freeze(f.getOrNull(1)?.toLongOrNull() ?: (GameFlow.PUSH_MS + GameFlow.PUSH_LAG_MS + 170L)); flow.tapPad(0)
                    }
                    else -> Log.w(TAG, "unknown state $a")
                }
            }
            "tap" -> {
                val c = Hex.parse(a)
                if (c != null) flow.tapCell(c) else route(graph, a)
            }
            "sel" -> flow.selectCells(cells(a))
            "push" -> dir(a)?.let { flow.tapPad(it) }
            "move" -> { val f = a.split(" "); flow.selectCells(cells(f[0])); f.getOrNull(1)?.let { dir(it) }?.let { flow.tapPad(it) } }
            "hover" -> SceneHolder.scene?.setHover(a.ifBlank { null }?.takeIf { it != "off" })
            "new" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                flow.newGame(GameSetup(
                    mode = if ("2p" in f) Mode.TWO_PLAYER else Mode.AI,
                    aiLevel = f.firstNotNullOfOrNull { it.removePrefix("ai=").toIntOrNull() }?.coerceIn(1, 3) ?: Ai.MEDIUM,
                    seat = when { "white" in f -> SeatPick.WHITE; "random" in f -> SeatPick.RANDOM; else -> SeatPick.BLACK },
                ))
                Ui.page = Page.PLAY
            }
            "setup" -> { setup(a); Ui.page = Page.PLAY }
            "newmode" -> settings { it.copy(lastSetup = it.lastSetup.copy(mode = if (a == "2p") Mode.TWO_PLAYER else Mode.AI)) }
            "level" -> a.toIntOrNull()?.let { l -> flow.debugState { it.copy(config = it.config?.copy(aiLevel = l.coerceIn(1, 3))) } }
            "undo" -> flow.undo()
            "redo" -> flow.redo()
            "hint" -> flow.setHint(on)
            "resign" -> flow.requestConfirm(ConfirmKind.RESIGN)
            "restart" -> flow.requestConfirm(ConfirmKind.RESTART)
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "newask" -> { Ui.page = Page.NEW; if (flow.state.value.hasProgress) Ui.newConfirm = true }
            "page" -> Ui.page = when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "coach" -> { settings { it.copy(coach = on) }; flow.debugState { it.copy(coachOn = on) } }
            "seed" -> a.toIntOrNull()?.let { flow.reseed(it) }
            "aitime" -> flow.aiTimeMs = a.toLongOrNull()
            "freeze" -> flow.freeze(if (a == "off") null else a.toLongOrNull())
            "hold" -> when (a.uppercase()) {
                "S17" -> flow.freeze(GameFlow.PUSH_MS + GameFlow.PUSH_LAG_MS + 170L)
                "OFF" -> { flow.freeze(null); flow.holdThinking = false }
                else -> flow.holdThinking = on
            }
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "welcome" -> flow.welcomeDone()
            "autoplay" -> {
                autoJob?.cancel()
                if (on) {
                    flow.newGame(GameConfig(Mode.TWO_PLAYER, Ai.HARD, Abalone.BLACK))
                    // both sides are "human" in 2P mode: the loop below plays each turn with the hard AI through tapCell / tapPad
                    autoJob = graph.scope.launch {
                        var n = 0
                        while (isActive) {
                            val st = flow.state.value
                            if (st.phase == io.embodit.abalonepush.domain.Phase.RESULT) { Log.i(TAG, "AUTOPLAY done moves=${st.moves.size} winner=${st.winner} reason=${st.endReason}"); break }
                            if (st.phase == io.embodit.abalonepush.domain.Phase.IDLE) {
                                val m = Ai.choose(st.pos, st.side, Ai.HARD, kotlin.random.Random(n++), 300L)
                                if (m == null) break
                                flow.selectCells(m.group); flow.tapPad(m.dir)
                            }
                            delay(150)
                        }
                    }
                }
            }
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
