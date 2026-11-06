package io.embodit.abalonepush.domain

enum class Mode { AI, TWO_PLAYER }

/** New-game seat choice: play black (moves first), white, or random. */
enum class SeatPick { BLACK, WHITE, RANDOM }

enum class Language { SYSTEM, ZH, EN }

/** One game's setup. In AI mode [humanSide] is the user's colour; black always moves first (official rules). */
data class GameConfig(
    val mode: Mode = Mode.AI,
    val aiLevel: Int = Ai.MEDIUM,
    val humanSide: Int = Abalone.BLACK,
) {
    fun isHuman(side: Int): Boolean = mode == Mode.TWO_PLAYER || side == humanSide
    val aiSide: Int get() = Abalone.other(humanSide)
}

/** The new-game page's choices (remembered; a random seat only goes into the game, D30). */
data class GameSetup(
    val mode: Mode = Mode.AI,
    val aiLevel: Int = Ai.MEDIUM,
    val seat: SeatPick = SeatPick.BLACK,
)

/** App settings (SharedPreferences). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val coach: Boolean = true,
    val firstRunDone: Boolean = false,
    val lastSetup: GameSetup = GameSetup(),
)

enum class EndReason { SIX, REPETITION, NO_DROP, RESIGN }

/** What a game looks like on disk (files/game.txt): the config and the move list; the position is a replay. */
data class SavedGame(
    val config: GameConfig,
    val moves: List<Move>,
    val redo: List<Move> = emptyList(),
    val elapsedMs: Long = 0,
    val hintOn: Boolean = false,
    val hintsUsed: Int = 0,
) {
    /** Worth offering again: moves were made, or the AI moves first (killed while it was thinking, S06 -> S16 -> S06). */
    val resumable: Boolean get() = moves.isNotEmpty() || (config.mode == Mode.AI && config.humanSide != Abalone.BLACK)
}

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Line codec `ab1|mode|level|humanSide|elapsed|hint|hintsUsed|<moves>|<redo>`; a move is `side.cells.dir` with the
 * cells as indices joined by `-` (the push run and the drop are recomputed on replay, so a bad file can't smuggle in an
 * illegal move: replay stops at the first one).
 */
object SaveCodec {
    fun move(m: Move): String = "${m.side}.${m.group.joinToString("-")}.${m.dir}"

    private fun parseMoves(s: String): List<Triple<Int, List<Int>, Int>> =
        if (s.isBlank()) emptyList() else s.split(";").map { t ->
            val f = t.split(".")
            Triple(f[0].toInt(), f[1].split("-").map { it.toInt() }, f[2].toInt())
        }

    fun encode(g: SavedGame): String = listOf(
        "ab1", g.config.mode.name, g.config.aiLevel.toString(), g.config.humanSide.toString(), g.elapsedMs.toString(),
        if (g.hintOn) "1" else "0", g.hintsUsed.toString(), g.moves.joinToString(";") { move(it) }, g.redo.joinToString(";") { move(it) },
    ).joinToString("|")

    /** Replays raw moves from the opening; null if any is illegal. */
    fun replay(raw: List<Triple<Int, List<Int>, Int>>, from: Pos = Abalone.START): Pair<List<Move>, Pos>? {
        var pos = from
        val out = ArrayList<Move>()
        for ((side, cells, d) in raw) {
            val m = Abalone.moveOf(pos, side, cells, d) ?: return null
            out += m
            pos = Abalone.apply(pos, m)
        }
        return out to pos
    }

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size < 9 || f[0] != "ab1") return null
        val config = GameConfig(Mode.valueOf(f[1]), f[2].toInt().coerceIn(1, 3), f[3].toInt().coerceIn(0, 1))
        val (moves, pos) = replay(parseMoves(f[7])) ?: return null
        // redo moves are checked lazily (they replay from the current position when used)
        val redoRaw = parseMoves(f[8])
        val redo = replay(redoRaw, pos)?.first ?: emptyList()
        SavedGame(config, moves, redo, f[4].toLong(), f[5] == "1", f[6].toInt())
    }.getOrNull()
}
