package io.embodit.abalonepush.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Design verification "三档 AI 单调": hard beats medium and medium beats easy in >= 70 % of a fixed-seed set, a random
 * mover still beats easy >= 35 % (B27: a beginner can win), every move within the level's time cap, reproducible.
 * Games use the app's own end rules (six off, third repetition, 120 plies without a drop -> more marbles off wins).
 * The game count comes from -Dab.games (default 12 for the regular test run; the 50-game run is recorded in ui-audit).
 */
class AiTest {
    private val games = (System.getProperty("ab.games") ?: System.getenv("AB_GAMES") ?: "12").toInt()

    /** Plays one game; returns the winner (null = draw) and the slowest move in ms per level. */
    private fun play(black: Int?, white: Int?, seed: Int, slow: MutableMap<Int, Long>): Int? {
        var pos = Abalone.START
        val seen = HashMap<String, Int>()
        var noDrop = 0
        var side = Abalone.BLACK
        val rnd = Random(seed)
        // a few random opening plies so the seed set is not 50 copies of the same game
        repeat(2 + seed % 3) {
            val ms = Abalone.legalMoves(pos, side)
            pos = Abalone.apply(pos, ms[rnd.nextInt(ms.size)]); side = 1 - side
        }
        for (ply in 0 until 400) {
            Abalone.winner(pos)?.let { return it }
            val key = "$pos|$side"
            val n = (seen[key] ?: 0) + 1; seen[key] = n
            if (n >= Abalone.REPEAT_DRAW || noDrop >= Abalone.NO_DROP_PLIES) return when { pos.score[0] > pos.score[1] -> 0; pos.score[1] > pos.score[0] -> 1; else -> null }
            val level = if (side == Abalone.BLACK) black else white
            val t0 = System.nanoTime()
            val m = if (level == null) Abalone.legalMoves(pos, side).let { it[rnd.nextInt(it.size)] } else Ai.choose(pos, side, level, Random(seed * 1000 + ply))!!
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (level != null) slow[level] = maxOf(slow[level] ?: 0, ms)
            noDrop = if (m.drops) 0 else noDrop + 1
            pos = Abalone.apply(pos, m); side = 1 - side
        }
        return null
    }

    private fun match(a: Int?, b: Int?, label: String): Double {
        val slow = HashMap<Int, Long>()
        var wins = 0; var draws = 0
        for (g in 0 until games) {
            // a plays black in even games, white in odd ones
            val w = if (g % 2 == 0) play(a, b, 100 + g, slow) else play(b, a, 100 + g, slow)
            val aSide = if (g % 2 == 0) 0 else 1
            if (w == aSide) wins++ else if (w == null) draws++
        }
        // score rate: a win 1, a draw 1/2 (the usual tournament count; the 120-ply rule ends many weak games 0 : 0)
        val rate = (wins + draws / 2.0) / games
        println("AI $label: score ${"%.0f".format(rate * 100)} % ($wins wins, $draws draws / $games), slowest move per level (ms) $slow")
        slow[Ai.HARD]?.let { assertTrue("hard move $it ms", it <= 800) }
        slow[Ai.MEDIUM]?.let { assertTrue("medium move $it ms", it <= 450) }
        return rate
    }

    @Test fun hardBeatsMedium() { assertTrue(match(Ai.HARD, Ai.MEDIUM, "hard vs medium") >= 0.70) }

    @Test fun mediumBeatsEasy() { assertTrue(match(Ai.MEDIUM, Ai.EASY, "medium vs easy") >= 0.70) }

    @Test fun randomStillBeatsEasySometimes() { assertTrue(match(null, Ai.EASY, "random vs easy") >= 0.35) }

    @Test fun fixedSeedIsReproducible() {
        val p = Abalone.apply(Abalone.START, Abalone.legalMoves(Abalone.START, 0)[5])
        val a = Ai.choose(p, 1, Ai.EASY, Random(42)); val b = Ai.choose(p, 1, Ai.EASY, Random(42))
        assertEquals(a, b)
        val c = Ai.choose(p, 1, Ai.MEDIUM, Random(42)); val d = Ai.choose(p, 1, Ai.MEDIUM, Random(42))
        assertEquals(c, d)
    }

    @Test fun takesTheWinningPushWhenItIsThere() {
        val cells = ByteArray(Hex.N)
        listOf("E5", "E6", "E7").forEach { cells[Hex.parse(it)!!] = 1 }
        listOf("E8", "E9", "I9").forEach { cells[Hex.parse(it)!!] = 2 }
        val p = Pos(cells, intArrayOf(5, 0))
        for (level in Ai.LEVELS) assertTrue(Ai.choose(p, 0, level, Random(1))!!.drops)
    }
}
