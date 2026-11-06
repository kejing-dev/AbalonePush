package io.embodit.abalonepush.domain

import kotlin.random.Random

/**
 * Three levels (design gameSpec.ai, A12 cut from the template's five):
 *   1 入门 nine moves in ten are any legal move, otherwise depth 1, a random pick among the best 5, half the time
 *     passing up a push (B27: a beginner can win; a random mover still wins >= 35 % against it);
 *   2 中级 depth 2, a random pick among the best 2, 300 ms cap;
 *   3 高级 iterative deepening to depth 4, 720 ms search cap (<= 800 ms per move).
 * Negamax + alpha-beta, pushes and drops ordered first. Evaluation (symmetric): pushed-off difference x 1000 + centre
 * pull x 10 + own neighbour pairs x 3 - rim marbles with two or more opponents lined up behind them x 15.
 * Runs on Dispatchers.Default; deterministic for a given Random.
 */
object Ai {
    const val EASY = 1
    const val MEDIUM = 2
    const val HARD = 3
    val LEVELS = listOf(EASY, MEDIUM, HARD)

    private const val WIN = 1_000_000

    class Budget(val ms: Long, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
        private val start = clock()
        var nodes = 0L
        fun out(): Boolean = (++nodes and 255L) == 0L && clock() - start > ms
        fun hardOut(): Boolean = clock() - start > ms
    }

    private class Timeout : RuntimeException() { override fun fillInStackTrace(): Throwable = this }

    fun choose(pos: Pos, side: Int, level: Int, rnd: Random, timeMs: Long? = null): Move? {
        val moves = Abalone.legalMoves(pos, side)
        if (moves.isEmpty()) return null
        Abalone.legalMoves(pos, side).firstOrNull { it.drops && Abalone.winner(Abalone.apply(pos, it)) == side }?.let { return it }
        return when (level) {
            EASY -> easy(pos, side, moves, rnd)
            MEDIUM -> ranked(pos, side, moves, 2, timeMs ?: 300L, rnd, top = 2)
            else -> deepening(pos, side, moves, 4, timeMs ?: 720L, rnd)
        }
    }

    /** Share of 入门 moves that are just any legal move (B27 / design: a random mover still wins >= 35 % against it). */
    const val EASY_LOOSE = 0.9

    private fun easy(pos: Pos, side: Int, moves: List<Move>, rnd: Random): Move {
        if (rnd.nextDouble() < EASY_LOOSE) return moves[rnd.nextInt(moves.size)]
        var pool = moves
        if (moves.any { it.isPush } && moves.any { !it.isPush } && rnd.nextBoolean()) pool = moves.filter { !it.isPush }
        val scored = pool.map { it to evaluate(Abalone.apply(pos, it), side) }.sortedByDescending { it.second }
        return scored[rnd.nextInt(minOf(5, scored.size))].first
    }

    /** Depth-[depth] scores of every root move; a random pick among the best [top] (ties broken by the shuffle). */
    private fun ranked(pos: Pos, side: Int, moves: List<Move>, depth: Int, ms: Long, rnd: Random, top: Int): Move {
        val budget = Budget(ms)
        val order = ordered(moves).shuffled(rnd).sortedByDescending { prio(it) }
        val scored = ArrayList<Pair<Move, Int>>()
        for (m in order) {
            val v = try { -negamax(Abalone.apply(pos, m), Abalone.other(side), depth - 1, -WIN, WIN, budget) } catch (_: Timeout) { break }
            scored += m to v
        }
        if (scored.isEmpty()) return order.first()
        scored.sortByDescending { it.second }
        val best = scored.first().second
        val near = scored.take(top).filter { best - it.second < 400 }
        return near[rnd.nextInt(near.size)].first
    }

    private fun deepening(pos: Pos, side: Int, moves: List<Move>, maxDepth: Int, ms: Long, rnd: Random): Move {
        val budget = Budget(ms)
        var order = ordered(moves).shuffled(rnd).sortedByDescending { prio(it) }
        var best = order.first()
        for (depth in 1..maxDepth) {
            var alpha = -WIN
            var bestHere: Move? = null
            val scores = HashMap<Move, Int>()
            try {
                for (m in order) {
                    val v = -negamax(Abalone.apply(pos, m), Abalone.other(side), depth - 1, -WIN, -alpha, budget)
                    scores[m] = v
                    if (v > alpha || bestHere == null) { alpha = v; bestHere = m }
                }
            } catch (_: Timeout) {
                if (bestHere != null && depth > 1) best = bestHere
                break
            }
            best = bestHere ?: best
            order = order.sortedByDescending { scores[it] ?: -WIN }
            if (alpha > WIN / 2) break
        }
        return best
    }

    private fun prio(m: Move): Int = (if (m.drops) 100 else 0) + m.pushed.size * 10 + m.group.size

    private fun ordered(moves: List<Move>): List<Move> = moves

    private fun negamax(pos: Pos, side: Int, depth: Int, a0: Int, beta: Int, budget: Budget): Int {
        if (budget.out()) throw Timeout()
        Abalone.winner(pos)?.let { return if (it == side) WIN - 1 else -(WIN - 1) }
        if (depth == 0) return evaluate(pos, side)
        val moves = Abalone.legalMoves(pos, side)
        if (moves.isEmpty()) return evaluate(pos, side)
        var alpha = a0
        var best = -WIN
        for (m in moves.sortedByDescending { prio(it) }) {
            val v = -negamax(Abalone.apply(pos, m), Abalone.other(side), depth - 1, -beta, -alpha, budget)
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    /** Static evaluation from [side]'s point of view. */
    fun evaluate(pos: Pos, side: Int): Int = sideScore(pos, side) - sideScore(pos, Abalone.other(side))

    private fun sideScore(pos: Pos, side: Int): Int {
        val v = (side + 1).toByte()
        val o = (Abalone.other(side) + 1).toByte()
        var s = pos.score[side] * 1000
        for (c in 0 until Hex.N) {
            if (pos.cells[c] != v) continue
            val ring = Hex.ring(c)
            s += (4 - ring) * 10
            for (d in 0..2) { val n = Hex.next(c, d); if (n >= 0 && pos.cells[n] == v) s += 3 }
            if (ring == 4) {
                // on the rim: an opponent line of two or more right behind it, pushing toward the edge
                for (d in 0..5) {
                    if (Hex.next(c, d) >= 0) continue
                    val b1 = Hex.next(c, Hex.opposite(d))
                    val b2 = if (b1 >= 0) Hex.next(b1, Hex.opposite(d)) else -1
                    if (b1 >= 0 && b2 >= 0 && pos.cells[b1] == o && pos.cells[b2] == o) { s -= 15; break }
                }
            }
        }
        return s
    }
}
