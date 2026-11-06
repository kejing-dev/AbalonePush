package io.embodit.abalonepush.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private class MemStore : GameStore {
        var saved: SavedGame? = null
        var text: String? = null
        override fun load() = text?.let { SaveCodec.decode(it) }
        override fun save(game: SavedGame) { saved = game; text = SaveCodec.encode(game) }
        override fun clear() { saved = null; text = null }
    }

    private class Harness(scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true, coach = false)) {
        val sounds = ArrayList<Sound>()
        val flow = GameFlow(
            scope = scope.backgroundScope,
            engineDispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
            store = store,
            settings = { settings },
            onSettings = { settings = it },
            onSound = { sounds += it },
        )
        val st get() = flow.state.value
    }

    private fun c(n: String) = Hex.parse(n)!!

    private fun pos(spec: String, score: IntArray = intArrayOf(0, 0)): Pos {
        val cells = ByteArray(Hex.N)
        for (t in spec.split(" ").filter { it.isNotBlank() }) {
            val v: Byte = if (t.startsWith("b:")) 1 else 2
            t.drop(2).split(",").filter { it.isNotBlank() }.forEach { cells[c(it)] = v }
        }
        return Pos(cells, score)
    }

    private fun TestScope.settle() { advanceTimeBy(2_000); runCurrent() }

    @Test fun firstRunOpensTheDefaultGameAndTheFirstTapEndsTheWelcome() = runTest {
        val h = Harness(this, settings = Settings())
        h.flow.start(); runCurrent()
        assertEquals(Phase.IDLE, h.st.phase)
        assertTrue(h.st.welcome)
        assertEquals(Mode.AI, h.st.config!!.mode)
        assertEquals(Ai.MEDIUM, h.st.config!!.aiLevel)
        assertEquals(Abalone.BLACK, h.st.config!!.humanSide)
        assertTrue("coach on in the first game", h.st.coachOn)
        assertEquals(StatusKey.COACH_1, h.st.status().key)
        h.flow.tapCell(c("C4"))
        assertFalse(h.st.welcome)
        assertTrue(h.settings.firstRunDone)
        assertEquals(Phase.SELECTED, h.st.phase)
        assertEquals(StatusKey.COACH_2, h.st.status().key)
    }

    @Test fun selectionGrowsShrinksResetsAndCancels() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER)); runCurrent()
        h.flow.tapCell(c("C4"))
        assertEquals(listOf(c("C4")), h.st.selection)
        // blocker 1: the next own marble is reachable (no pad sits on it) and joins the line
        assertTrue(h.st.pads.none { it.anchorCell == c("C5") })
        h.flow.tapCell(c("C5")); assertEquals(2, h.st.selection.size)
        h.flow.tapCell(c("C3")); assertEquals(3, h.st.selection.size)
        // the middle one resets to it alone; an end shortens the line
        h.flow.tapCell(c("C4")); assertEquals(listOf(c("C4")), h.st.selection)
        h.flow.tapCell(c("C5")); h.flow.tapCell(c("C3"))
        h.flow.tapCell(c("C5")); assertEquals(setOf(c("C3"), c("C4")), h.st.selection.toSet())
        // not in a line: an alert, the selection stays
        h.flow.tapCell(c("B3"))
        assertEquals(Reason.NOT_LINE, h.st.alert?.reason)
        assertEquals(2, h.st.selection.size)
        // an empty cell that no pad covers cancels (S03)
        h.flow.tapCell(c("G3"))
        assertEquals(Phase.IDLE, h.st.phase)
        assertTrue(h.st.selection.isEmpty())
    }

    @Test fun openingSelectionsNeverPutAPadOnAnOwnMarbleAndTwoInARowJoin() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER)); runCurrent()
        val own = Abalone.START.cellsOf(Abalone.BLACK)
        for (a in own) {
            h.flow.selectCells(listOf(a))
            for (p in h.st.pads) if (p.anchorCell >= 0) assertTrue(Abalone.START.owner(p.anchorCell) != Abalone.BLACK)
            for (b in own) if (b != a && (0..5).any { Hex.next(a, it) == b }) {
                h.flow.selectCells(listOf(a, b))
                assertEquals("$a+$b", 2, h.st.selection.size)
                assertEquals(Phase.SELECTED, h.st.phase)
            }
            h.flow.cancelSelection()
        }
    }

    @Test fun pushWithDropGoesThroughS05S17AndScores() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER), pos("b:E5,E6,E7,A1 w:E8,E9,I9"), Abalone.BLACK); runCurrent()
        h.flow.selectCells(listOf(c("E5"), c("E6"), c("E7")))
        val pad = h.st.pads.first { it.dir == 0 }
        assertEquals(PadKind.PUSH, pad.kind); assertEquals(2, pad.pushCount); assertTrue(pad.drops)
        h.flow.tapPad(0)
        assertEquals(Phase.MOVING, h.st.phase)
        // the plate keeps showing the old score until the marble lands
        assertEquals(0, h.st.shownPos.score[0])
        assertEquals(1, h.st.pos.score[0])
        advanceTimeBy(GameFlow.PUSH_MS + GameFlow.PUSH_LAG_MS + 10); runCurrent()
        assertEquals(Phase.DROP, h.st.phase)
        assertEquals(StatusKey.PUSHED_OFF, h.st.status().key)
        assertTrue(Sound.CLACK in h.sounds)
        advanceTimeBy(GameFlow.FLIP_MS + GameFlow.ROLL_MS + 10); runCurrent()
        assertTrue(Sound.DROP in h.sounds)
        assertEquals(Phase.IDLE, h.st.phase)
        assertEquals(Abalone.WHITE, h.st.side)
    }

    @Test fun greyPadExplainsAndNothingMoves() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER), pos("b:E1,E2 w:E3,E4"), Abalone.BLACK); runCurrent()
        h.flow.selectCells(listOf(c("E1"), c("E2")))
        val before = h.st.pos
        h.flow.tapPad(0)
        assertEquals(Reason.FEWER, h.st.alert?.reason)
        assertEquals(before, h.st.pos)
        assertEquals(Phase.SELECTED, h.st.phase)
        assertTrue(Sound.ILLEGAL in h.sounds)
        advanceTimeBy(GameFlow.ALERT_MS + 50); runCurrent()
        assertNull(h.st.alert)
    }

    @Test fun tappingTheMarbleUnderAPadTakesThatPad() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER), pos("b:E5,E6,E7,A1 w:E8,I9"), Abalone.BLACK); runCurrent()
        h.flow.selectCells(listOf(c("E5"), c("E6"), c("E7")))
        h.flow.tapCell(c("E8"))
        assertEquals(Phase.MOVING, h.st.phase)
        assertTrue(h.st.lastMove!!.isPush)
    }

    @Test fun aiAnswersAfterAtLeast400msAndATapMeanwhileOnlyReminds() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Abalone.WHITE)); runCurrent()
        assertEquals(Phase.THINKING, h.st.phase)
        h.flow.tapCell(c("I5"))
        assertEquals(Reason.THINKING, h.st.alert?.reason)
        assertTrue(h.st.selection.isEmpty())
        advanceTimeBy(GameFlow.MIN_THINK_MS - 50); runCurrent()
        assertEquals(0, h.st.moves.size)
        settle()
        assertEquals(1, h.st.moves.size)
        assertEquals(Phase.IDLE, h.st.phase)
        assertEquals(Abalone.WHITE, h.st.side)
    }

    @Test fun undoTakesTheAiReplyAlongAndRedoReplaysIt() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Abalone.BLACK)); runCurrent()
        assertFalse(h.st.canUndo)
        h.flow.selectCells(listOf(c("C3"), c("C4"), c("C5"))); h.flow.tapPad(2)
        settle(); settle()
        assertEquals(2, h.st.moves.size)
        assertTrue(h.st.canUndo)
        h.flow.undo(); runCurrent()
        assertEquals(0, h.st.moves.size)
        assertEquals(Abalone.START, h.st.pos)
        assertTrue(h.st.canRedo)
        h.flow.redo(); runCurrent()
        assertEquals(2, h.st.moves.size)
        assertEquals(Phase.IDLE, h.st.phase)
        // a fresh human move clears the redo list
        h.flow.undo(); runCurrent()
        h.flow.selectCells(listOf(c("C4"))); h.flow.tapPad(2)
        assertTrue(h.st.redo.isEmpty())
    }

    @Test fun saveIsWrittenBeforeTheAnimationAndRestoreLandsOnTheStableState() = runTest {
        val store = MemStore()
        val h = Harness(this, store)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Abalone.BLACK)); runCurrent()
        h.flow.selectCells(listOf(c("C3"), c("C4"), c("C5"))); h.flow.tapPad(2)
        assertEquals(Phase.MOVING, h.st.phase)
        assertEquals(1, store.saved!!.moves.size)
        // the process dies mid-slide: a new flow restores to after the move, the AI to move -> S06 thinks again
        val h2 = Harness(this, store)
        h2.flow.start(); runCurrent()
        assertTrue(h2.st.restored)
        assertEquals(1, h2.st.moves.size)
        assertEquals(Phase.THINKING, h2.st.phase)
        settle()
        assertEquals(2, h2.st.moves.size)
        assertEquals(Phase.IDLE, h2.st.phase)
    }

    @Test fun thirdRepetitionIsADraw() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER), pos("b:E1 w:I9"), Abalone.BLACK); runCurrent()
        repeat(2) {
            h.flow.selectCells(listOf(c("E1"))); h.flow.tapPad(0); settle()
            h.flow.selectCells(listOf(c("I9"))); h.flow.tapPad(3); settle()
            h.flow.selectCells(listOf(c("E2"))); h.flow.tapPad(3); settle()
            h.flow.selectCells(listOf(c("I8"))); h.flow.tapPad(0); settle()
        }
        assertEquals(Phase.RESULT, h.st.phase)
        assertEquals(EndReason.REPETITION, h.st.endReason)
        assertNull(h.st.winner)
    }

    @Test fun hundredTwentyPliesWithoutADropEndsByCount() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER), pos("b:E1,A1,A2,A3 w:I9,I5,I6,I7", intArrayOf(2, 1)), Abalone.BLACK); runCurrent()
        // walk single marbles around without repeating (the repetition rule must not fire first)
        val rnd = kotlin.random.Random(1)
        var guard = 0
        while (h.st.phase != Phase.RESULT && guard++ < 400) {
            val moves = Abalone.legalMoves(h.st.pos, h.st.side).filter { !it.drops }
            val m = moves[rnd.nextInt(moves.size)]
            h.flow.selectCells(m.group); h.flow.tapPad(m.dir); settle()
        }
        assertEquals(Phase.RESULT, h.st.phase)
        if (h.st.endReason == EndReason.NO_DROP) {
            assertEquals(Abalone.NO_DROP_PLIES, h.st.moves.size)
            assertEquals(Abalone.BLACK, h.st.winner)
        } else assertEquals(EndReason.REPETITION, h.st.endReason)
    }

    @Test fun resignGivesTheGameAwayAndTheAiWinIsNoFanfare() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Abalone.BLACK)); runCurrent()
        h.flow.requestConfirm(ConfirmKind.RESIGN)
        h.flow.tapCell(c("C4"))
        assertEquals("the volume is locked while a confirmation is open", Phase.IDLE, h.st.phase)
        h.flow.confirm()
        assertEquals(Phase.RESULT, h.st.phase)
        assertEquals(EndReason.RESIGN, h.st.endReason)
        assertEquals(Abalone.WHITE, h.st.winner)
        assertTrue(Sound.LOSE in h.sounds)
        assertFalse(Sound.WIN in h.sounds)
    }

    @Test fun sixthMarbleWins() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Abalone.BLACK), pos("b:E5,E6,E7 w:E8,E9,I9", intArrayOf(5, 0)), Abalone.BLACK); runCurrent()
        h.flow.selectCells(listOf(c("E5"), c("E6"), c("E7"))); h.flow.tapPad(0)
        settle()
        assertEquals(Phase.RESULT, h.st.phase)
        assertEquals(Abalone.BLACK, h.st.winner)
        assertTrue(Sound.WIN in h.sounds)
        assertNull("a finished game is not kept", h.store.saved)
    }

    @Test fun hintCountsAndStaysOutOfTheSettings() = runTest {
        val h = Harness(this)
        val before = h.settings
        h.flow.newGame(GameConfig(Mode.AI, Ai.EASY, Abalone.BLACK)); runCurrent()
        h.flow.setHint(true); settle()
        assertNotNull(h.st.hint)
        assertEquals(1, h.st.hintsUsed)
        assertEquals(before.copy(lastSetup = h.settings.lastSetup), h.settings)
    }

    @Test fun twoPlayerUndoIsOneMove() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER)); runCurrent()
        h.flow.selectCells(listOf(c("C4"))); h.flow.tapPad(2); settle()
        assertEquals(Abalone.WHITE, h.st.side)
        assertTrue(h.st.humansTurn)
        h.flow.undo(); runCurrent()
        assertEquals(0, h.st.moves.size)
        assertEquals(Abalone.BLACK, h.st.side)
    }

    @Test fun codecRoundTripsAndRejectsIllegalMoves() {
        val m = Abalone.moveOf(Abalone.START, 0, listOf(Hex.parse("C3")!!, Hex.parse("C4")!!, Hex.parse("C5")!!), 2)!!
        val g = SavedGame(GameConfig(Mode.AI, Ai.HARD, Abalone.WHITE), listOf(m), emptyList(), 1234, true, 2)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        assertNull(SaveCodec.decode("ab1|AI|2|0|0|0|0|0.0-1-2.0|"))
        assertNull(SaveCodec.decode("garbage"))
        advanceNothing()
    }

    private fun advanceNothing() = Unit

    @Suppress("unused")
    private fun TestScope.idle() = advanceUntilIdle()
}
