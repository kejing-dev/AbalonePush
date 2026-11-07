package io.embodit.abalonepush.ui.board

import io.embodit.abalonepush.domain.Abalone
import io.embodit.abalonepush.domain.Ai
import io.embodit.abalonepush.domain.GameConfig
import io.embodit.abalonepush.domain.GameFlow
import io.embodit.abalonepush.domain.GameStore
import io.embodit.abalonepush.domain.Hex
import io.embodit.abalonepush.domain.Mode
import io.embodit.abalonepush.domain.PadKind
import io.embodit.abalonepush.domain.Phase
import io.embodit.abalonepush.domain.Pos
import io.embodit.abalonepush.domain.SavedGame
import io.embodit.abalonepush.domain.Settings
import io.embodit.abalonepush.ui.board.input.parseLoggedPath
import io.embodit.abalonepush.ui.board.input.resolvePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * build.md「命中目标 -> 格子必须有回放单测」(斗兽棋 v0.1.3: every piece tap resolved to square=-). Real hit chains (the hit
 * entity's name first, then its parents, the `path=` of a BoardInput TAP line) for every kind of target -- a marble's GLB
 * mesh, its collider, the cell collider, a pad's GLB mesh and collider, the board -- go through the template's pure
 * resolver and the same dispatch entry the volume uses, and must reach the right GameFlow call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardTargetTest {
    private val parents = listOf("boardRoot", "tiltRoot", "sceneRoot", "root")
    private fun target(chain: List<String>) = resolvePath(chain, BoardNames::resolveName)

    private class NoStore : GameStore {
        override fun load(): SavedGame? = null
        override fun save(game: SavedGame) {}
        override fun clear() {}
    }

    private fun TestScope.flow(): GameFlow = GameFlow(backgroundScope, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, NoStore(), { Settings(firstRunDone = true, coach = false) })

    /** What a pinch on each part of a marble standing on [c] reports (deepest node first). */
    private fun marbleChains(c: Int, colour: Int): List<List<String>> {
        val node = if (colour == Abalone.BLACK) "marble_black" else "marble_white"
        return listOf(
            listOf(BoardNames.cell(c), BoardNames.cell(c)) + parents,              // the sphere collider child
            listOf(node, BoardNames.cell(c)) + parents,                            // the GLB mesh node
            listOf("Mesh_0", node, BoardNames.cell(c)) + parents,                  // a primitive under the mesh node
        )
    }

    @Test fun everyMarbleAndCellOfTheOpeningResolvesToItsCell() {
        val pos = Abalone.START
        var n = 0
        for (c in 0 until Hex.N) {
            assertEquals(BoardNames.cell(c), target(listOf(BoardNames.cell(c)) + parents))
            val o = pos.owner(c)
            if (o >= 0) for (ch in marbleChains(c, o)) { assertEquals("$ch", BoardNames.cell(c), target(ch)); n++ }
        }
        assertEquals(28 * 3, n)
    }

    @Test fun padMeshAndColliderResolveToTheirDirection() {
        for (d in 0..5) for (v in listOf("pad_blue", "pad_amber1", "pad_amber2", "pad_amber1red", "pad_amber2red", "pad_grey")) {
            assertEquals(BoardNames.pad(d), target(listOf(v, BoardNames.pad(d)) + parents))
            assertEquals(BoardNames.pad(d), target(listOf("Mesh_0", v, BoardNames.pad(d)) + parents))
            assertEquals(BoardNames.pad(d), target(listOf(BoardNames.pad(d), BoardNames.pad(d)) + parents))
        }
    }

    @Test fun boardGutterAndFlyingMarblesAreTheBoardAndNothingElseIsOurs() {
        assertEquals("board", target(listOf("board") + parents))
        assertEquals("board", target(listOf("Mesh_0", "board", "boardModel") + parents))
        assertEquals("board", target(listOf("marble_white", "dropping") + parents))
        assertEquals("board", target(listOf("marble_black", "gutter") + parents))
        assertNull(target(listOf("keyLight", "root")))
        assertNull(target(parseLoggedPath("<none>")))
        assertNull(target(listOf("pad:x")))  // a pad never shown yet
    }

    /** Whole replay: pinches on marble meshes select a line, a pinch on the amber pad's mesh pushes and drops one. */
    @Test fun replayedHitsDriveTheGame() = runTest {
        val f = flow()
        val cells = ByteArray(Hex.N)
        listOf("E5", "E6", "E7").forEach { cells[Hex.parse(it)!!] = 1 }
        listOf("E8", "E9", "I9").forEach { cells[Hex.parse(it)!!] = 2 }
        f.newGame(GameConfig(Mode.TWO_PLAYER, Ai.MEDIUM, Abalone.BLACK), Pos(cells, intArrayOf(0, 0)), Abalone.BLACK); runCurrent()
        val log = listOf(
            "marble_black < cell:${Hex.parse("E5")} < boardRoot < tiltRoot < sceneRoot < root",
            "Mesh_0 < marble_black < cell:${Hex.parse("E6")} < boardRoot < tiltRoot < sceneRoot < root",
            "cell:${Hex.parse("E7")} < cell:${Hex.parse("E7")} < boardRoot < tiltRoot < sceneRoot < root",
        )
        for (line in log) BoardNames.dispatch(f, target(parseLoggedPath(line)))
        assertEquals(Phase.SELECTED, f.state.value.phase)
        assertEquals(3, f.state.value.selection.size)
        val pad = f.state.value.pads.first { it.dir == 0 }
        assertEquals(PadKind.PUSH, pad.kind)
        assertTrue(pad.drops)
        BoardNames.dispatch(f, target(parseLoggedPath("pad_amber2red < pad:0 < boardRoot < tiltRoot < sceneRoot < root")))
        assertEquals(Phase.MOVING, f.state.value.phase)
        assertEquals(1, f.state.value.pos.score[Abalone.BLACK])
    }

    /** A pinch on the board frame while a line is selected drops the selection (S04 -> S03). */
    @Test fun replayedBoardHitCancels() = runTest {
        val f = flow()
        f.newGame(GameConfig(Mode.TWO_PLAYER)); runCurrent()
        BoardNames.dispatch(f, target(listOf("marble_black", BoardNames.cell(Hex.parse("C4")!!)) + parents))
        assertEquals(Phase.SELECTED, f.state.value.phase)
        BoardNames.dispatch(f, target(listOf("Mesh_0", "board") + parents))
        assertEquals(Phase.IDLE, f.state.value.phase)
    }
}
