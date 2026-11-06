package io.embodit.abalonepush.domain

/**
 * Abalone rules (design gameSpec.moves / specialRules, official 1990 rules): lines of 1-3 own marbles, inline moves
 * and Sumito pushes (3 v 2, 3 v 1, 2 v 1; the cell behind the pushed run must be empty or off the board), broadside
 * moves into empty cells only, a marble pushed over the rim is out; six out wins. Pure Kotlin, unit tested.
 */
object Abalone {
    const val BLACK = 0
    const val WHITE = 1
    const val TO_WIN = 6
    const val PER_SIDE = 14
    /** Factory draw rules (gameSpec.specialRules, N10): the same position + side to move a third time, or 120 plies with no marble out. */
    const val REPEAT_DRAW = 3
    const val NO_DROP_PLIES = 120

    fun other(side: Int) = 1 - side

    /** Standard opening: black = near row (r = +4, 5) + r = +3 (6) + the middle three of r = +2; white mirrored. */
    val START: Pos = run {
        val cells = ByteArray(Hex.N)
        for (c in 0 until Hex.N) {
            val r = Hex.R[c]; val q = Hex.Q[c]
            fun mid(rr: Int) = r == rr && Hex.x(q.toFloat(), r.toFloat()).let { kotlin.math.abs(it) < Hex.P * 1.01f }
            when {
                r == 4 || r == 3 || mid(2) -> cells[c] = 1
                r == -4 || r == -3 || mid(-2) -> cells[c] = 2
            }
        }
        Pos(cells, intArrayOf(0, 0))
    }

    /** The axis (0..2 = direction d and d + 3) a 2-3 cell group lies on, or -1 when it is not a straight contiguous line. */
    fun axisOf(cells: List<Int>): Int {
        if (cells.size < 2) return -1
        for (a in 0..2) {
            val sorted = orderAlong(cells, a) ?: continue
            if (sorted.size == cells.size) return a
        }
        return -1
    }

    /** The cells in order along axis direction [a] (each the d=a neighbour of the previous), or null when not contiguous. */
    fun orderAlong(cells: List<Int>, a: Int): List<Int>? {
        val set = cells.toSet()
        val start = cells.firstOrNull { c -> Hex.next(c, Hex.opposite(a)).let { it < 0 || it !in set } } ?: return null
        val out = ArrayList<Int>()
        var c = start
        while (c >= 0 && c in set) { out += c; c = Hex.next(c, a) }
        return if (out.size == cells.size) out else null
    }

    /** True when [cells] is a valid group of [side]: 1-3 own marbles, contiguous on one line. */
    fun isGroup(pos: Pos, side: Int, cells: List<Int>): Boolean {
        if (cells.isEmpty() || cells.size > 3 || cells.toSet().size != cells.size) return false
        if (cells.any { pos.owner(it) != side }) return false
        return cells.size == 1 || axisOf(cells) >= 0
    }

    /**
     * Classifies moving [group] (a valid group of [side]) in direction [d] (design input.directionPads): MOVE (blue),
     * PUSH (amber, 1-2 dots, drops = red edge), BLOCKED (grey, with a reason) or OWN (no pad: an own marble is the target).
     */
    fun classify(pos: Pos, side: Int, group: List<Int>, d: Int): Pad {
        val n = group.size
        val axis = if (n >= 2) axisOf(group) else -1
        val inline = n == 1 || axis == d || axis == Hex.opposite(d)
        if (inline) {
            val front = if (n == 1) group[0] else orderAlong(group, d)!!.last()
            val target = Hex.next(front, d)
            if (target < 0) return Pad(d, PadKind.BLOCKED, reason = Reason.EDGE, anchorCell = -1, anchorFrom = front)
            val o = pos.owner(target)
            if (o == side) return Pad(d, PadKind.OWN, anchorCell = target)
            if (o < 0) return Pad(d, PadKind.MOVE, anchorCell = target)
            // a run of opponents
            if (n == 1) return Pad(d, PadKind.BLOCKED, reason = Reason.FEWER, anchorCell = target)
            var k = 0
            var c = target
            while (c >= 0 && pos.owner(c) == other(side) && k <= 3) { k++; c = Hex.next(c, d) }
            if (k >= n) return Pad(d, PadKind.BLOCKED, reason = if (k == 3 && n == 3) Reason.THREE else Reason.FEWER, anchorCell = target)
            if (c >= 0 && pos.owner(c) >= 0) return Pad(d, PadKind.BLOCKED, reason = Reason.SANDWICHED, anchorCell = target)
            return Pad(d, PadKind.PUSH, pushCount = k, drops = c < 0, anchorCell = target)
        }
        // broadside
        val targets = group.map { Hex.next(it, d) }
        if (targets.any { it >= 0 && pos.owner(it) == side }) return Pad(d, PadKind.OWN, anchorCell = targets.first { it >= 0 && pos.owner(it) == side })
        val ordered = orderAlong(group, axis)!!
        val mid = ordered[ordered.size / 2]
        if (targets.any { it < 0 }) return Pad(d, PadKind.BLOCKED, reason = Reason.EDGE, anchorCell = -1, anchorFrom = mid, broadside = ordered)
        if (targets.any { pos.owner(it) >= 0 }) return Pad(d, PadKind.BLOCKED, reason = Reason.SIDEWAYS, anchorCell = -1, anchorFrom = mid, broadside = ordered)
        return Pad(d, PadKind.MOVE, anchorCell = -1, anchorFrom = mid, broadside = ordered)
    }

    /** The pads of a selection: every direction whose target is not an own marble (design blocker 1). */
    fun pads(pos: Pos, side: Int, group: List<Int>): List<Pad> =
        if (!isGroup(pos, side, group)) emptyList() else (0..5).map { classify(pos, side, group, it) }.filter { it.kind != PadKind.OWN }

    /** The legal move of [group] in [d], or null. */
    fun moveOf(pos: Pos, side: Int, group: List<Int>, d: Int): Move? {
        if (!isGroup(pos, side, group)) return null
        val p = classify(pos, side, group, d)
        if (!p.lit) return null
        val n = group.size
        val axis = if (n >= 2) axisOf(group) else -1
        val inline = n == 1 || axis == d || axis == Hex.opposite(d)
        val ordered = if (n == 1) group else orderAlong(group, if (inline) d else axis)!!
        val pushed = ArrayList<Int>()
        if (p.kind == PadKind.PUSH) {
            var c = Hex.next(ordered.last(), d)
            repeat(p.pushCount) { pushed += c; c = Hex.next(c, d) }
        }
        return Move(side, ordered, d, inline, pushed, p.drops)
    }

    /** Every legal move of [side] (each group once: lines are enumerated along the three positive axes only). */
    fun legalMoves(pos: Pos, side: Int): List<Move> {
        val out = ArrayList<Move>(96)
        val v = (side + 1).toByte()
        for (c in 0 until Hex.N) {
            if (pos.cells[c] != v) continue
            for (d in 0..5) moveOf(pos, side, listOf(c), d)?.let { out += it }
            for (a in 0..2) {
                val c2 = Hex.next(c, a)
                if (c2 < 0 || pos.cells[c2] != v) continue
                val g2 = listOf(c, c2)
                for (d in 0..5) moveOf(pos, side, g2, d)?.let { out += it }
                val c3 = Hex.next(c2, a)
                if (c3 < 0 || pos.cells[c3] != v) continue
                val g3 = listOf(c, c2, c3)
                for (d in 0..5) moveOf(pos, side, g3, d)?.let { out += it }
            }
        }
        return out
    }

    /** Applies a legal move. The pushed-off marble (if any) adds one to the mover's score. */
    fun apply(pos: Pos, m: Move): Pos {
        val cells = pos.cells.copyOf()
        val mine = (m.side + 1).toByte()
        val theirs = (other(m.side) + 1).toByte()
        // clear the movers and the pushed run, then place them one step on
        for (c in m.group) cells[c] = 0
        for (c in m.pushed) cells[c] = 0
        for (c in m.pushed) { val t = Hex.next(c, m.dir); if (t >= 0) cells[t] = theirs }
        for (c in m.group) cells[Hex.next(c, m.dir)] = mine
        val score = pos.score.copyOf()
        if (m.drops) score[m.side]++
        return Pos(cells, score)
    }

    fun winner(pos: Pos): Int? = when {
        pos.score[BLACK] >= TO_WIN -> BLACK
        pos.score[WHITE] >= TO_WIN -> WHITE
        else -> null
    }

    /** The marble moves of [m] for the animation: (from cell, to cell or -1 = off the board). Movers first. */
    fun steps(m: Move): List<Pair<Int, Int>> = m.group.map { it to Hex.next(it, m.dir) } + m.pushed.map { it to Hex.next(it, m.dir) }
}

enum class PadKind { MOVE, PUSH, BLOCKED, OWN }

/** Why a grey pad can't go (S08 one-liners). */
enum class Reason { EDGE, FEWER, THREE, SANDWICHED, SIDEWAYS, NOT_LINE, THINKING, NOT_YOURS, EMPTY }

/**
 * One direction of a selection. Anchor: [anchorCell] (the target cell: empty, the first opponent, or -1), else the axial
 * point one step from [anchorFrom] (off-board inline target, or the middle of a broadside group) in [dir].
 */
data class Pad(
    val dir: Int,
    val kind: PadKind,
    val pushCount: Int = 0,
    val drops: Boolean = false,
    val reason: Reason? = null,
    val anchorCell: Int = -1,
    val anchorFrom: Int = -1,
    val broadside: List<Int> = emptyList(),
) {
    val lit: Boolean get() = kind == PadKind.MOVE || kind == PadKind.PUSH

    /** Axial (q, r) of the pad centre: the target cell, or the broadside group's target centre, or one step off the board. */
    fun anchorQR(): Pair<Float, Float> {
        if (broadside.isNotEmpty()) {
            val q = broadside.map { Hex.Q[it] + Hex.DQ[dir] }.average().toFloat()
            val r = broadside.map { Hex.R[it] + Hex.DR[dir] }.average().toFloat()
            return q to r
        }
        if (anchorCell >= 0) return Hex.Q[anchorCell].toFloat() to Hex.R[anchorCell].toFloat()
        return (Hex.Q[anchorFrom] + Hex.DQ[dir]).toFloat() to (Hex.R[anchorFrom] + Hex.DR[dir]).toFloat()
    }
}

/**
 * A move: [group] in order (inline: back to front along [dir]; broadside: along the line), [pushed] = the opponent run
 * from the contact outward, [drops] = the last pushed marble leaves the board.
 */
data class Move(val side: Int, val group: List<Int>, val dir: Int, val inline: Boolean, val pushed: List<Int> = emptyList(), val drops: Boolean = false) {
    val isPush: Boolean get() = pushed.isNotEmpty()
    fun text(): String = group.joinToString("-") { Hex.name(it) } + ">" + "d$dir" + (if (isPush) " push${pushed.size}" else "") + (if (drops) " OFF" else "")
}

/** A position: 61 cells (0 empty, 1 black, 2 white) and the marbles each side has pushed off ([score]). */
class Pos(val cells: ByteArray, val score: IntArray) {
    fun owner(c: Int): Int = cells[c] - 1
    fun count(side: Int): Int = cells.count { it.toInt() == side + 1 }
    fun cellsOf(side: Int): List<Int> = (0 until Hex.N).filter { cells[it].toInt() == side + 1 }

    override fun equals(other: Any?): Boolean = other is Pos && other.cells.contentEquals(cells) && other.score.contentEquals(score)
    override fun hashCode(): Int = cells.contentHashCode() * 31 + score.contentHashCode()
    /** "b"/"w"/"." per cell in index order, then the two scores. */
    override fun toString(): String = cells.joinToString("") { when (it.toInt()) { 1 -> "b"; 2 -> "w"; else -> "." } } + ":" + score[0] + ":" + score[1]

    companion object {
        fun parse(s: String): Pos? = runCatching {
            val f = s.split(":")
            require(f[0].length == Hex.N)
            Pos(ByteArray(Hex.N) { i -> when (f[0][i]) { 'b' -> 1; 'w' -> 2; else -> 0 } }, intArrayOf(f[1].toInt(), f[2].toInt()))
        }.getOrNull()
    }
}
