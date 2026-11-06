package io.embodit.abalonepush.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Design verification: Sumito table, broadside, group legality, drops / score, opening, two independent move generators. */
class RulesTest {
    private fun c(n: String) = Hex.parse(n)!!

    /** A position from "b:E5,E6 w:E8" (scores 0). */
    private fun pos(spec: String, score: IntArray = intArrayOf(0, 0)): Pos {
        val cells = ByteArray(Hex.N)
        for (t in spec.split(" ").filter { it.isNotBlank() }) {
            val v: Byte = if (t.startsWith("b:")) 1 else 2
            t.drop(2).split(",").filter { it.isNotBlank() }.forEach { cells[c(it)] = v }
        }
        return Pos(cells, score)
    }

    @Test fun boardHas61CellsNineRowsAndNotationRoundTrips() {
        assertEquals(61, Hex.N)
        assertEquals(listOf(5, 6, 7, 8, 9, 8, 7, 6, 5), (-4..4).map { r -> (0 until Hex.N).count { Hex.R[it] == r } })
        for (i in 0 until Hex.N) assertEquals(i, Hex.parse(Hex.name(i)))
        assertEquals(0f, Hex.x(Hex.at(0, 0)), 1e-6f)
        assertEquals(0.045f, Hex.ROW, 0.0005f)
        // the near row is A (r = +4), widest row E has 9 cells E1..E9
        assertEquals(4, Hex.R[c("A1")]); assertEquals(-4, Hex.Q[c("E1")]); assertEquals(4, Hex.Q[c("E9")])
    }

    @Test fun standardOpeningIs14Each() {
        val p = Abalone.START
        assertEquals(14, p.count(Abalone.BLACK)); assertEquals(14, p.count(Abalone.WHITE))
        // C row (r = +2): only the middle three are black
        assertEquals(listOf("C3", "C4", "C5"), p.cellsOf(Abalone.BLACK).filter { Hex.R[it] == 2 }.map { Hex.name(it) })
        // centrally symmetric
        for (i in 0 until Hex.N) { val m = Hex.at(-Hex.Q[i], -Hex.R[i]); assertEquals(p.owner(i).let { if (it < 0) -1 else 1 - it }, p.owner(m)) }
    }

    @Test fun groupLegality() {
        val p = pos("b:E5,E6,E7,D5,E2 w:E8")
        assertTrue(Abalone.isGroup(p, 0, listOf(c("E5"))))
        assertTrue(Abalone.isGroup(p, 0, listOf(c("E7"), c("E5"), c("E6"))))
        assertFalse("not contiguous", Abalone.isGroup(p, 0, listOf(c("E5"), c("E7"))))
        assertFalse("not in a line", Abalone.isGroup(p, 0, listOf(c("E5"), c("E6"), c("D5"))))
        assertFalse("not own", Abalone.isGroup(p, 0, listOf(c("E7"), c("E8"))))
        assertFalse("four", Abalone.isGroup(pos("b:E2,E3,E4,E5"), 0, listOf(c("E2"), c("E3"), c("E4"), c("E5"))))
    }

    /**
     * The Sumito table on row E, pushing right (d0): n own in a line, then k opponents, then the cell behind them
     * (empty / off the board / own / one more opponent). Expected values from the official rules written out here.
     */
    @Test fun sumitoFullTable() {
        var cases = 0
        for (n in 1..3) for (k in 0..3) for (behind in listOf("empty", "off", "own", "opp")) {
            if (k == 0 && behind == "opp") continue
            val s = if (behind == "off") 10 - n - k else 9 - n - k
            if (s < 1) continue
            val own = (s until s + n).map { "E$it" }
            val opp = (s + n until s + n + k).map { "E$it" }
            val bc = "E${s + n + k}"
            val spec = "b:" + (own + if (behind == "own") listOf(bc) else emptyList()).joinToString(",") +
                " w:" + (opp + if (behind == "opp") listOf(bc) else emptyList()).joinToString(",")
            val pad = Abalone.classify(pos(spec), 0, own.map { c(it) }, 0)
            val label = "n=$n k=$k behind=$behind ($spec) -> $pad"
            cases++
            if (k == 0) {
                when (behind) {
                    "empty" -> assertEquals(label, PadKind.MOVE, pad.kind)
                    "off" -> assertEquals(label, Reason.EDGE, pad.reason)
                    "own" -> assertEquals(label, PadKind.OWN, pad.kind)
                }
                continue
            }
            val run = k + if (behind == "opp") 1 else 0
            when {
                n == 1 -> assertEquals(label, Reason.FEWER, pad.reason)
                run >= n -> assertEquals(label, if (run == 3 && n == 3) Reason.THREE else Reason.FEWER, pad.reason)
                behind == "own" -> assertEquals(label, Reason.SANDWICHED, pad.reason)
                else -> {
                    assertEquals(label, PadKind.PUSH, pad.kind)
                    assertEquals(label, run, pad.pushCount)
                    assertEquals(label, behind == "off" || behind == "opp", pad.drops)
                }
            }
        }
        println("sumito cases: $cases")
        assertTrue(cases >= 40)
    }

    @Test fun sumitoEdgeCasesWithDrops() {
        // 3 v 2 to the rim: E5-E7 push E8-E9, E9 goes off
        val p = pos("b:E5,E6,E7 w:E8,E9")
        val pad = Abalone.classify(p, 0, listOf(c("E5"), c("E6"), c("E7")), 0)
        assertEquals(PadKind.PUSH, pad.kind); assertEquals(2, pad.pushCount); assertTrue(pad.drops)
        val m = Abalone.moveOf(p, 0, listOf(c("E5"), c("E6"), c("E7")), 0)!!
        val q = Abalone.apply(p, m)
        assertEquals(1, q.score[0]); assertEquals(1, q.count(1)); assertEquals(1, q.owner(c("E9"))); assertEquals(0, q.owner(c("E8")))
        // 2 v 1 to the rim
        val p2 = pos("b:E7,E8 w:E9")
        assertTrue(Abalone.classify(p2, 0, listOf(c("E7"), c("E8")), 0).drops)
        // 3 v 3 never, 2 v 2 never, 1 never pushes
        assertEquals(Reason.THREE, Abalone.classify(pos("b:E1,E2,E3 w:E4,E5,E6"), 0, listOf(c("E1"), c("E2"), c("E3")), 0).reason)
        assertEquals(Reason.FEWER, Abalone.classify(pos("b:E1,E2 w:E3,E4"), 0, listOf(c("E1"), c("E2")), 0).reason)
        assertEquals(Reason.FEWER, Abalone.classify(pos("b:E1 w:E2"), 0, listOf(c("E1")), 0).reason)
        // sandwiched: own marble behind the pushed run, or a third opponent
        assertEquals(Reason.SANDWICHED, Abalone.classify(pos("b:E1,E2,E3,E5 w:E4"), 0, listOf(c("E1"), c("E2"), c("E3")), 0).reason)
        // you can't push yourself off: the line's own front at the rim, inline toward the rim = edge
        assertEquals(Reason.EDGE, Abalone.classify(pos("b:E7,E8,E9"), 0, listOf(c("E7"), c("E8"), c("E9")), 0).reason)
    }

    @Test fun broadsideNeverPushesAndNeedsEmptyTargets() {
        val g = listOf(c("E5"), c("E6"), c("E7"))
        assertEquals(PadKind.MOVE, Abalone.classify(pos("b:E5,E6,E7"), 0, g, 1).kind)
        assertEquals(Reason.SIDEWAYS, Abalone.classify(pos("b:E5,E6,E7 w:F7"), 0, g, 2).reason)
        assertEquals(PadKind.OWN, Abalone.classify(pos("b:E5,E6,E7,D5"), 0, g, 4).kind)
        // off the board sideways
        assertEquals(Reason.EDGE, Abalone.classify(pos("b:A1,A2,A3"), 0, listOf(c("A1"), c("A2"), c("A3")), 5).reason)
        val m = Abalone.moveOf(pos("b:E5,E6,E7"), 0, g, 1)!!
        assertFalse(m.inline)
        val q = Abalone.apply(pos("b:E5,E6,E7"), m)
        assertEquals(setOf(Hex.next(c("E5"), 1), Hex.next(c("E6"), 1), Hex.next(c("E7"), 1)), q.cellsOf(0).toSet())
    }

    @Test fun padsNeverSitOnOwnMarblesAcrossRandomPositions() {
        val rnd = Random(7)
        var pos = Abalone.START
        var checked = 0
        repeat(101) { round ->
            for (side in 0..1) for (c0 in pos.cellsOf(side)) {
                val groups = mutableListOf(listOf(c0))
                for (a in 0..2) {
                    val c1 = Hex.next(c0, a); if (c1 < 0 || pos.owner(c1) != side) continue
                    groups += listOf(c0, c1)
                    val c2 = Hex.next(c1, a); if (c2 >= 0 && pos.owner(c2) == side) groups += listOf(c0, c1, c2)
                }
                for (g in groups) for (p in Abalone.pads(pos, side, g)) {
                    checked++
                    if (p.anchorCell >= 0) assertTrue("pad on own marble $p", pos.owner(p.anchorCell) != side)
                    if (p.broadside.isNotEmpty()) p.broadside.forEach { val t = Hex.next(it, p.dir); if (t >= 0) assertTrue(pos.owner(t) != side) }
                }
            }
            val moves = Abalone.legalMoves(pos, round % 2)
            if (moves.isNotEmpty()) pos = Abalone.apply(pos, moves[rnd.nextInt(moves.size)])
        }
        assertTrue(checked > 5000)
    }

    /** Second, independent generator (every subset of 1-3 own cells x 6 directions, brute force) must agree. */
    @Test fun twoMoveGeneratorsAgreeOnOpeningAndRandomPositions() {
        fun brute(p: Pos, side: Int): Set<String> {
            val own = p.cellsOf(side)
            val out = HashSet<String>()
            val groups = ArrayList<List<Int>>()
            for (i in own.indices) {
                groups += listOf(own[i])
                for (j in i + 1 until own.size) {
                    groups += listOf(own[i], own[j])
                    for (k in j + 1 until own.size) groups += listOf(own[i], own[j], own[k])
                }
            }
            for (g in groups) {
                // independent legality: contiguous line
                if (g.size > 1) {
                    val ok = g.any { x -> (0..5).any { a ->
                        val y = Hex.next(x, a)
                        if (g.size == 2) y == g.first { it != x } || y in g && y != x
                        else y >= 0 && y in g && Hex.next(y, a).let { z -> z >= 0 && z in g && z != x }
                    } }
                    if (!ok) continue
                }
                for (d in 0..5) {
                    val dest = g.map { Hex.next(it, d) }
                    val inline = g.size == 1 || dest.any { it in g }
                    if (!inline) {
                        if (dest.all { it >= 0 && p.owner(it) < 0 }) out += g.sorted().joinToString("-") + ">" + d
                        continue
                    }
                    var front = g.first { Hex.next(it, d) !in g }
                    var t = Hex.next(front, d)
                    if (t < 0 || p.owner(t) == side) continue
                    var k = 0
                    while (t >= 0 && p.owner(t) == 1 - side) { k++; t = Hex.next(t, d) }
                    if (k == 0 || (k < g.size && (t < 0 || p.owner(t) < 0))) out += g.sorted().joinToString("-") + ">" + d
                    @Suppress("UNUSED_VALUE") front = -1
                }
            }
            return out
        }
        val rnd = Random(3)
        var p = Abalone.START
        val opening = Abalone.legalMoves(p, 0)
        println("opening moves for black: ${opening.size} (brute ${brute(p, 0).size})")
        for (round in 0 until 120) {
            val side = round % 2
            val fast = Abalone.legalMoves(p, side).map { it.group.sorted().joinToString("-") + ">" + it.dir }
            assertEquals("duplicates at $round", fast.size, fast.toSet().size)
            assertEquals("round $round", brute(p, side), fast.toSet())
            val ms = Abalone.legalMoves(p, side)
            p = Abalone.apply(p, ms[rnd.nextInt(ms.size)])
        }
        assertEquals(44, opening.size)
    }

    @Test fun winnerAtSixAndPositionCodec() {
        val p = pos("b:E5,E6,E7 w:E8,E9", intArrayOf(5, 0))
        val m = Abalone.moveOf(p, 0, listOf(c("E5"), c("E6"), c("E7")), 0)!!
        assertEquals(0, Abalone.winner(Abalone.apply(p, m)))
        assertNull(Abalone.winner(p))
        assertEquals(p, Pos.parse(p.toString()))
        assertNotNull(Pos.parse(Abalone.START.toString()))
    }
}
