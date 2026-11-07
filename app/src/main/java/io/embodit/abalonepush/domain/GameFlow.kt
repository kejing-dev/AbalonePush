package io.embodit.abalonepush.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Named states of design-package states[] that live in the game (S01 / S08 / S11 / S12 / S13 / S16 are overlays or pages). */
enum class Phase(val id: String) {
    LOADING("S00"), SETUP("S02"), IDLE("S03"), SELECTED("S04"), MOVING("S05"), THINKING("S06"), DROP("S17"), RESULT("S09"),
}

enum class Sound { SELECT, SLIDE, CLACK, DROP, ILLEGAL, WIN, LOSE }

/** S08: a one-liner + a red flash on [cells] (marbles) or on the grey pad of [dir]. */
data class Alert(val reason: Reason, val cells: List<Int>, val dir: Int?, val at: Long)

/** The move in flight (S05, then S17 when a marble goes over the rim). [before] = the position the tags keep showing. */
data class MoveAnim(val move: Move, val before: Pos, val startedAt: Long, val slideMs: Long, val dropMs: Long)

enum class ConfirmKind { RESTART, RESIGN }

/** The one sentence under "whose turn" in the window and on the counter plate's second line (D38 / D45: one source). */
enum class StatusKey { NONE, PICK, COACH_1, COACH_2, COACH_3, ADD_MORE, CHOOSE_ARROW, ALERT, THINKING, MOVING, PUSHED_OFF, RESTORED, OVER }

data class Status(val key: StatusKey, val side: Int = 0, val n: Int = 0, val reason: Reason? = null)

/** The one state every surface reads (board scene, control window, the counter plate, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val startPos: Pos = Abalone.START,
    val startSide: Int = Abalone.BLACK,
    val moves: List<Move> = emptyList(),
    /** positions[i] = the position after i moves (positions[0] = startPos). */
    val positions: List<Pos> = listOf(Abalone.START),
    val selection: List<Int> = emptyList(),
    val pads: List<Pad> = emptyList(),
    val anim: MoveAnim? = null,
    val alert: Alert? = null,
    val winner: Int? = null,
    val endReason: EndReason? = null,
    val hintOn: Boolean = false,
    val hint: Move? = null,
    val hintsUsed: Int = 0,
    val confirm: ConfirmKind? = null,
    val redo: List<Move> = emptyList(),
    val elapsedMs: Long = 0,
    val resumedAt: Long = 0,
    val welcome: Boolean = false,
    val coachOn: Boolean = false,
    val restored: Boolean = false,
    val savedAvailable: Boolean = false,
    val savedMoves: Int = 0,
    /** The marble that just went over the rim (S17 line: "推下去了，n 颗了"), cleared on the next turn. */
    val lastDropBy: Int? = null,
    /** Debug hold: animations stop this many ms after their start and notices hold. */
    val freezeMs: Long? = null,
    val freezeAt: Long = 0,
) {
    val pos: Pos get() = positions.last()
    val side: Int get() = (startSide + moves.size) % 2
    val humansTurn: Boolean get() = config?.isHuman(side) == true
    val inGame: Boolean get() = config != null && phase in setOf(Phase.IDLE, Phase.SELECTED, Phase.MOVING, Phase.THINKING, Phase.DROP)
    val hasProgress: Boolean get() = inGame && moves.isNotEmpty()
    /** What the plate and the counters show while a move slides (S05): the position before it; from S17 on the new score. */
    val shownPos: Pos get() = anim?.takeIf { phase == Phase.MOVING }?.before ?: pos
    /** Whose turn the plate / header ring shows: the mover until its move has landed. */
    val shownSide: Int get() = anim?.move?.side ?: side
    val lastMove: Move? get() = moves.lastOrNull()
    val draw: Boolean get() = phase == Phase.RESULT && winner == null
    val noDropPlies: Int get() {
        var n = 0
        for (i in moves.indices.reversed()) { if (moves[i].drops) break; n++ }
        return n
    }
    /** Moves to take back for one undo (an AI game takes the AI's reply along), 0 when there is none. */
    fun undoCount(): Int {
        val cfg = config ?: return 0
        if (moves.isEmpty()) return 0
        if (cfg.mode == Mode.TWO_PLAYER) return 1
        var k = 0
        var s = side
        // pop until it is the human's turn again and at least one human move went
        while (k < moves.size) {
            k++
            s = Abalone.other(s)
            if (s == cfg.humanSide) return k
        }
        return 0
    }
    val canUndo: Boolean get() = humansTurn && phase in setOf(Phase.IDLE, Phase.SELECTED) && undoCount() > 0
    val canRedo: Boolean get() = redo.isNotEmpty() && humansTurn && phase in setOf(Phase.IDLE, Phase.SELECTED)

    fun status(): Status {
        val cfg = config ?: return Status(StatusKey.NONE)
        alert?.let { return Status(StatusKey.ALERT, side, reason = it.reason) }
        return when (phase) {
            Phase.RESULT -> Status(StatusKey.OVER, winner ?: -1)
            Phase.THINKING -> if (humansTurn) Status(StatusKey.PICK, side) else Status(StatusKey.THINKING, side)
            Phase.DROP -> Status(StatusKey.PUSHED_OFF, anim?.move?.side ?: side, pos.score[anim?.move?.side ?: side])
            Phase.MOVING -> Status(StatusKey.MOVING, anim?.move?.side ?: side)
            Phase.SELECTED -> when {
                coachOn && selection.size == 1 -> Status(StatusKey.COACH_2, side)
                coachOn -> Status(StatusKey.COACH_3, side)
                selection.size == 1 -> Status(StatusKey.ADD_MORE, side)
                else -> Status(StatusKey.CHOOSE_ARROW, side)
            }
            Phase.IDLE -> when {
                lastDropBy != null && cfg.mode == Mode.AI && lastDropBy != cfg.humanSide -> Status(StatusKey.PUSHED_OFF, lastDropBy, pos.score[lastDropBy])
                coachOn -> Status(StatusKey.COACH_1, side)
                restored -> Status(StatusKey.RESTORED, side)
                else -> Status(StatusKey.PICK, side)
            }
            else -> Status(StatusKey.NONE)
        }
    }
}

/**
 * Turn sequencing (rules - state - render: this is the middle layer). Main-thread confined: every public call and every
 * coroutine it launches runs on [scope]'s dispatcher; the AI runs on [engineDispatcher]. Debug commands call the same
 * functions the volume and the window call (profile D29).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
    seed: Int = 20261105,
) {
    companion object {
        const val SLIDE_MS = 220L
        const val PUSH_MS = 260L
        /** A pushed marble starts this much later (design animation.push: you can see who pushes whom). */
        const val PUSH_LAG_MS = 30L
        const val FLIP_MS = 350L
        const val ROLL_MS = 250L
        const val REDUCED_MS = 150L
        const val ALERT_MS = 1500L
        const val FLASH_MS = 300L
        const val MIN_THINK_MS = 400L
        const val AI_SHOW_MS = 300L
        /** How many human moves of the first game carry the coach lines. */
        const val COACH_MOVES = 3
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    private var rng = Random(seed)
    var aiSeed: Int = seed
    var holdThinking: Boolean = false
    /** A window-only confirmation (exit / new game) is open: the volume is locked too. */
    @Volatile var uiLocked: Boolean = false
    /** Debug: the AI's think time cap (null = the level's own). */
    var aiTimeMs: Long? = null

    private var turnJob: Job? = null
    private var alertJob: Job? = null
    private var hintJob: Job? = null
    private var humanMovesThisGame = 0

    private fun set(next: PlayState) { _state.value = next }
    private val reduced: Boolean get() = settings().reducedMotion

    // ---- lifecycle ----

    /** S00 exits: first run -> the default game + rules page (S01); a save -> S16; otherwise S02. */
    fun start() {
        val saved = store.load()
        if (!settings().firstRunDone) {
            newGame(GameConfig(Mode.AI, Ai.MEDIUM, Abalone.BLACK))
            set(s.copy(welcome = true))
            log("STATE S01 (default game, rules page open)")
            return
        }
        if (saved != null && saved.resumable) restore(saved) else { set(PlayState(phase = Phase.SETUP)); log("STATE S02") }
    }

    /** [开始] on the welcome rules page / the first tap on a marble: the welcome is over (the game was already running). */
    fun welcomeDone() {
        if (!s.welcome && settings().firstRunDone) return
        onSettings(settings().copy(firstRunDone = true))
        set(s.copy(welcome = false))
        log("WELCOME done")
    }

    fun continueSaved(): Boolean {
        val saved = store.load() ?: return false
        restore(saved)
        return true
    }

    private fun positionsOf(start: Pos, moves: List<Move>): List<Pos> {
        val out = ArrayList<Pos>(moves.size + 1)
        out += start
        for (m in moves) out += Abalone.apply(out.last(), m)
        return out
    }

    private fun restore(saved: SavedGame) {
        cancelJobs()
        val positions = positionsOf(Abalone.START, saved.moves)
        set(PlayState(
            phase = Phase.IDLE, config = saved.config, moves = saved.moves, positions = positions, redo = saved.redo,
            elapsedMs = saved.elapsedMs, resumedAt = clock(), hintOn = saved.hintOn, hintsUsed = saved.hintsUsed,
            restored = true, savedAvailable = true, savedMoves = saved.moves.size,
        ))
        log("GAME restored moves=${saved.moves.size} side=${s.side} S16")
        humanMovesThisGame = COACH_MOVES
        if (finishIfOver()) return
        nextTurn()
    }

    fun newGame(setup: GameSetup) {
        val human = when (setup.seat) { SeatPick.BLACK -> Abalone.BLACK; SeatPick.WHITE -> Abalone.WHITE; SeatPick.RANDOM -> if (rng.nextBoolean()) Abalone.BLACK else Abalone.WHITE }
        onSettings(settings().copy(lastSetup = setup))
        newGame(GameConfig(setup.mode, setup.aiLevel.coerceIn(1, 3), human))
    }

    fun newGame(config: GameConfig, start: Pos = Abalone.START, startSide: Int = Abalone.BLACK) {
        cancelJobs()
        humanMovesThisGame = 0
        set(PlayState(
            phase = Phase.IDLE, config = config, startPos = start, startSide = startSide, positions = listOf(start), resumedAt = clock(),
            hintOn = s.hintOn, welcome = s.welcome, coachOn = settings().coach, freezeMs = s.freezeMs, freezeAt = s.freezeAt,
        ))
        log("GAME new mode=${config.mode} level=${config.aiLevel} human=${config.humanSide}")
        persist()
        nextTurn()
    }

    /** [再来一局]: the same setup straight away (S03 / S06). */
    fun again() { s.config?.let { newGame(it) } }

    fun toMenu() {
        cancelJobs()
        val saved = store.load()
        set(PlayState(phase = Phase.SETUP, savedAvailable = saved?.resumable == true, savedMoves = saved?.moves?.size ?: 0, hintOn = s.hintOn))
        log("STATE S02")
    }

    /** Going to the background: stop the AI, land a move in flight, keep the game. */
    fun onBackground() {
        if (s.anim != null) { turnJob?.cancel(); afterMove() }
        if (s.phase == Phase.THINKING) turnJob?.cancel()
        persist()
        log("BACKGROUND phase=${s.phase.id}")
    }

    fun onForeground() {
        if (s.config == null || turnJob?.isActive == true) return
        if (s.phase == Phase.THINKING && !s.humansTurn) aiTurn()
    }

    // ---- input (volume entities, panel buttons, debug: one path) ----

    private fun locked(): Boolean {
        if (s.confirm != null || uiLocked) { log("LOCKED by confirm (S13)"); return true }
        return false
    }

    /** A pinch / trigger on a marble or a board cell (the same thing: a cell index). */
    fun tapCell(c: Int) {
        val st = s
        log("TAP cell ${Hex.name(c)} owner=${st.pos.owner(c)} phase=${st.phase.id} sel=${st.selection.map { Hex.name(it) }}")
        if (locked() || st.config == null) return
        when (st.phase) {
            Phase.THINKING -> if (!st.humansTurn) { alert(Reason.THINKING, listOf(c), null, sound = Sound.SELECT); return }
            Phase.IDLE, Phase.SELECTED -> Unit
            else -> return
        }
        if (!st.humansTurn) return
        if (st.welcome) welcomeDone()
        val owner = st.pos.owner(c)
        // a cell under a pad (the target marble / cell) means that pad: the ray may hit the marble below it
        if (st.phase == Phase.SELECTED) {
            padAt(st, c)?.let { tapPad(it.dir); return }
        }
        if (owner == st.side) { selectMarble(c); return }
        if (st.phase == Phase.SELECTED) { cancelSelection(); return }
        alert(if (owner >= 0) Reason.NOT_YOURS else Reason.EMPTY, listOf(c), null)
    }

    private fun padAt(st: PlayState, c: Int): Pad? =
        st.pads.firstOrNull { it.anchorCell == c } ?: st.pads.firstOrNull { p -> p.broadside.any { Hex.next(it, p.dir) == c } }

    /** A pinch on a direction pad (or the matching panel key). Lit = move; grey = the reason, nothing moves. */
    fun tapPad(dir: Int) {
        val st = s
        log("TAP pad d$dir phase=${st.phase.id}")
        if (locked()) return
        if (st.phase != Phase.SELECTED || !st.humansTurn) return
        val pad = st.pads.firstOrNull { it.dir == dir } ?: run { log("PAD d$dir not offered"); return }
        if (!pad.lit) { alert(pad.reason ?: Reason.EDGE, emptyList(), dir); return }
        val m = Abalone.moveOf(st.pos, st.side, st.selection, dir) ?: return
        commit(m)
    }

    /** A pinch on the board frame / gutter / off the cells: drop the selection. */
    fun tapEmpty() {
        if (locked()) return
        log("TAP empty phase=${s.phase.id}")
        if (s.phase == Phase.SELECTED && s.humansTurn) cancelSelection()
    }

    /** Design gameSpec.selection. */
    private fun selectMarble(c: Int) {
        val st = s
        val sel = st.selection
        val next: List<Int>? = when {
            sel.isEmpty() -> listOf(c)
            c in sel -> {
                if (sel.size == 1) emptyList()
                else {
                    val axis = Abalone.axisOf(sel)
                    val ordered = Abalone.orderAlong(sel, axis)!!
                    if (c == ordered.first() || c == ordered.last()) ordered - c else listOf(c)
                }
            }
            sel.size < 3 && Abalone.isGroup(st.pos, st.side, sel + c) -> sel + c
            sel.size == 2 && sel.any { s0 -> (0..5).any { Hex.next(s0, it) == c } } -> {
                alert(Reason.NOT_LINE, sel + c, null); null
            }
            else -> listOf(c)
        }
        if (next == null) return
        if (next.isEmpty()) { cancelSelection(); return }
        val pads = Abalone.pads(st.pos, st.side, next)
        set(st.copy(phase = Phase.SELECTED, selection = next, pads = pads, alert = null, restored = false, lastDropBy = null))
        onSound(Sound.SELECT)
        log("SELECT ${next.map { Hex.name(it) }} pads=${pads.joinToString(",") { "d${it.dir}:${it.kind}${if (it.kind == PadKind.PUSH) it.pushCount else ""}${if (it.drops) "!" else ""}" }} S04")
        refreshHint()
    }

    fun cancelSelection() {
        if (s.phase != Phase.SELECTED) return
        set(s.copy(phase = Phase.IDLE, selection = emptyList(), pads = emptyList()))
        onSound(Sound.SELECT)
        log("CANCEL -> S03")
    }

    private fun alert(reason: Reason, cells: List<Int>, dir: Int?, sound: Sound = Sound.ILLEGAL) {
        val at = clock()
        set(s.copy(alert = Alert(reason, cells, dir, at)))
        onSound(sound)
        log("ALERT $reason cells=${cells.map { Hex.name(it) }} dir=$dir S08")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            awaitUnfrozen()
            if (s.alert?.at == at) set(s.copy(alert = null))
        }
    }

    // ---- moving ----

    private fun commit(m: Move) {
        val st = s
        val before = st.pos
        val after = Abalone.apply(before, m)
        val slide = if (reduced) REDUCED_MS else if (m.isPush) PUSH_MS + PUSH_LAG_MS else SLIDE_MS
        val drop = if (m.drops) (if (reduced) 0L else FLIP_MS + ROLL_MS) else 0L
        val human = st.humansTurn
        if (human) humanMovesThisGame++
        set(st.copy(
            phase = Phase.MOVING, moves = st.moves + m, positions = st.positions + after, selection = emptyList(), pads = emptyList(),
            hint = null, alert = null, anim = MoveAnim(m, before, clock(), slide, drop), lastDropBy = null,
            redo = if (human) emptyList() else st.redo, restored = false,
            coachOn = st.coachOn && humanMovesThisGame < COACH_MOVES,
        ))
        log("MOVE ${m.text()} by=${if (human) "human" else "ai"} score=${after.score.toList()} S05")
        // committed and saved before the animation (resume lands on the stable state after it)
        persist()
        onSound(Sound.SLIDE)
        turnJob?.cancel()
        turnJob = scope.launch {
            if (m.isPush && !reduced) { delay(PUSH_LAG_MS); onSound(Sound.CLACK); delay(slide - PUSH_LAG_MS) } else delay(slide)
            // a debug hold inside the slide (S05) keeps the state at S05 too
            if (s.freezeMs?.let { it < slide } == true) awaitUnfrozen()
            if (m.drops) {
                // S17: the counter shows the new score from here; a debug hold freezes the flight, not the state
                set(s.copy(phase = Phase.DROP))
                log("DROP by=${m.side} score=${after.score[m.side]} S17")
                if (!reduced) delay(FLIP_MS)
                onSound(Sound.DROP)
                if (!reduced) delay(ROLL_MS)
            }
            awaitUnfrozen()
            afterMove()
        }
    }

    private fun afterMove() {
        val st = s
        val m = st.anim?.move ?: return
        set(st.copy(anim = null, lastDropBy = if (m.drops) m.side else null))
        if (finishIfOver()) return
        nextTurn()
    }

    /** Six off, or a factory draw rule (third repetition / 120 plies without a marble off). */
    private fun finishIfOver(): Boolean {
        val st = s
        Abalone.winner(st.pos)?.let { finish(it, EndReason.SIX); return true }
        val key = st.pos
        val reps = st.positions.indices.count { i -> st.positions[i] == key && (st.startSide + i) % 2 == st.side }
        if (reps >= Abalone.REPEAT_DRAW) { finishByCount(EndReason.REPETITION); return true }
        if (st.noDropPlies >= Abalone.NO_DROP_PLIES) { finishByCount(EndReason.NO_DROP); return true }
        return false
    }

    private fun finishByCount(reason: EndReason) {
        val sc = s.pos.score
        finish(when { sc[0] > sc[1] -> 0; sc[1] > sc[0] -> 1; else -> null }, reason)
    }

    private fun nextTurn() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT) return
        if (cfg.isHuman(st.side)) {
            set(st.copy(phase = Phase.IDLE, selection = emptyList(), pads = emptyList()))
            log("STATE S03 side=${st.side}")
            refreshHint()
        } else aiTurn()
    }

    // ---- AI ----

    private fun aiTurn() {
        val st = s
        val cfg = st.config ?: return
        set(st.copy(phase = Phase.THINKING, selection = emptyList(), pads = emptyList()))
        log("THINK side=${st.side} level=${cfg.aiLevel} S06")
        val pos = st.pos
        val side = st.side
        val ply = st.moves.size
        turnJob?.cancel()
        turnJob = scope.launch {
            val t0 = clock()
            val pick = withContext(engineDispatcher) { Ai.choose(pos, side, cfg.aiLevel, Random(aiSeed + ply * 31), aiTimeMs) } ?: return@launch
            val waited = clock() - t0
            log("AI ${pick.text()} level=${cfg.aiLevel} in ${waited} ms")
            if (waited < MIN_THINK_MS) delay(MIN_THINK_MS - waited)
            while (holdThinking) delay(100)
            if (s.phase != Phase.THINKING || s.pos != pos) return@launch
            // show its line lifted with the one pad it will take, then go (you can see who pushes whom)
            val pad = Abalone.classify(pos, side, pick.group, pick.dir)
            set(s.copy(selection = pick.group, pads = listOf(pad)))
            delay(if (reduced) 0 else AI_SHOW_MS)
            if (s.phase != Phase.THINKING || s.pos != pos) return@launch
            commit(pick)
        }
    }

    // ---- hint, undo / redo ----

    private fun refreshHint() {
        val st = s
        hintJob?.cancel()
        if (!st.hintOn || !st.humansTurn || st.phase !in setOf(Phase.IDLE, Phase.SELECTED)) { if (st.hint != null) set(st.copy(hint = null)); return }
        if (st.hint != null && st.hint.side == st.side && Abalone.moveOf(st.pos, st.side, st.hint.group, st.hint.dir) != null) return
        val pos = st.pos; val side = st.side; val ply = st.moves.size
        hintJob = scope.launch {
            val h = withContext(engineDispatcher) { Ai.choose(pos, side, Ai.MEDIUM, Random(aiSeed + ply), 300L) }
            if (s.pos != pos || !s.hintOn) return@launch
            set(s.copy(hint = h, hintsUsed = s.hintsUsed + 1))
            log("HINT ${h?.text()}")
            persist()
        }
    }

    fun setHint(on: Boolean) {
        set(s.copy(hintOn = on, hint = if (on) s.hint else null))
        log("HINT ${if (on) "on" else "off"}")
        refreshHint()
        persist()
    }

    /** [悔一步]: one whole round (with the AI's reply in an AI game). */
    fun undo() {
        val st = s
        if (!st.canUndo) { log("UNDO not available"); return }
        val k = st.undoCount()
        cancelJobs()
        val keep = st.moves.size - k
        val taken = st.moves.drop(keep)
        set(st.copy(
            moves = st.moves.take(keep), positions = st.positions.take(keep + 1), redo = taken + st.redo, selection = emptyList(), pads = emptyList(),
            alert = null, hint = null, anim = null, lastDropBy = null, phase = Phase.IDLE,
        ))
        log("UNDO $k -> moves=${keep} redo=${s.redo.size}")
        persist()
        nextTurn()
    }

    /** [重做]: replays the next round from the redo list (stops if a move no longer fits). */
    fun redo() {
        val st = s
        if (!st.canRedo) return
        cancelJobs()
        var moves = st.moves; var positions = st.positions; var redo = st.redo
        val cfg = st.config!!
        var steps = 0
        while (redo.isNotEmpty()) {
            val r = redo.first()
            val side = (st.startSide + moves.size) % 2
            val m = Abalone.moveOf(positions.last(), side, r.group, r.dir) ?: run { redo = emptyList(); null } ?: break
            moves = moves + m; positions = positions + Abalone.apply(positions.last(), m); redo = redo.drop(1); steps++
            val nextSide = (st.startSide + moves.size) % 2
            if (cfg.isHuman(nextSide)) break
        }
        set(st.copy(moves = moves, positions = positions, redo = redo, selection = emptyList(), pads = emptyList(), alert = null, hint = null, phase = Phase.IDLE))
        log("REDO $steps -> moves=${moves.size}")
        persist()
        if (finishIfOver()) return
        nextTurn()
    }

    // ---- confirm dialogs ----

    fun requestConfirm(kind: ConfirmKind) { set(s.copy(confirm = kind)); log("CONFIRM $kind S13") }

    fun cancelConfirm() { set(s.copy(confirm = null)); log("CONFIRM cancel") }

    fun confirm() {
        val kind = s.confirm ?: return
        set(s.copy(confirm = null))
        log("CONFIRM yes $kind")
        when (kind) {
            ConfirmKind.RESIGN -> {
                val cfg = s.config ?: return
                val loser = if (cfg.mode == Mode.AI) cfg.humanSide else s.side
                finish(Abalone.other(loser), EndReason.RESIGN)
            }
            ConfirmKind.RESTART -> { cancelJobs(); store.clear(); toMenu() }
        }
    }

    private fun finish(winner: Int?, reason: EndReason) {
        cancelJobs()
        val st = s
        set(st.copy(phase = Phase.RESULT, winner = winner, endReason = reason, elapsedMs = st.elapsedMs + (clock() - st.resumedAt),
            anim = null, selection = emptyList(), pads = emptyList(), hint = null, confirm = null, alert = null))
        val cfg = st.config
        // the opponent's win is not a fanfare (D44)
        val aiWon = cfg != null && cfg.mode == Mode.AI && winner != null && winner != cfg.humanSide
        if (winner != null) onSound(if (aiWon) Sound.LOSE else Sound.WIN)
        log("RESULT winner=$winner reason=$reason moves=${st.moves.size} score=${st.pos.score.toList()} S09")
        store.clear()
    }

    // ---- debug ----

    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms, freezeAt = clock())); log("FREEZE ${ms ?: "off"}") }

    private suspend fun awaitUnfrozen() { while (coroutineContext.isActive && s.freezeMs != null) delay(50) }

    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    fun reseed(seed: Int) { rng = Random(seed); aiSeed = seed; log("SEED $seed") }

    /** Debug `select`: tap these cells in order (the same path as pinches). */
    fun selectCells(cells: List<Int>) {
        if (s.phase == Phase.SELECTED) cancelSelection()
        cells.forEach { tapCell(it) }
    }

    fun dump(): String {
        val st = s
        return "phase=${st.phase.id} cfg=${st.config} side=${st.side} moves=${st.moves.size} score=${st.pos.score.toList()} sel=${st.selection.map { Hex.name(it) }} " +
            "pads=${st.pads.joinToString(",") { "d${it.dir}:${it.kind}" }} hint=${st.hint?.text()} alert=${st.alert?.reason} confirm=${st.confirm} " +
            "redo=${st.redo.size} welcome=${st.welcome} coach=${st.coachOn} winner=${st.winner} reason=${st.endReason} noDrop=${st.noDropPlies} freeze=${st.freezeMs} pos=${st.pos}"
    }

    private fun persist() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT || st.phase == Phase.SETUP) return
        if (st.startPos != Abalone.START || st.startSide != Abalone.BLACK) return // debug setups are not saved
        store.save(SavedGame(cfg, st.moves, st.redo, st.elapsedMs + (clock() - st.resumedAt), st.hintOn, st.hintsUsed))
    }

    private fun cancelJobs() {
        turnJob?.cancel(); alertJob?.cancel(); hintJob?.cancel()
    }
}
