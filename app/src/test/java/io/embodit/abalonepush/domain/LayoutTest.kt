package io.embodit.abalonepush.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Design spatialLayout.panelGeometryCheck (profile D35) + volume fit. Eyes = the measured emulator reset (about level with
 * the volume centre, 0.7 / 1.2 / 1.9 / 2.5 m, azimuth 0 / ±30°) + the design's sit / sit low / sit high / stand.
 */
class LayoutTest {
    @Test fun marbleSitsInItsDimpleWithItsTopAt28mm() {
        assertEquals(0.011f, Layout.MARBLE_Y, 0.0005f)
        assertEquals(0.028f, Layout.MARBLE_Y + Layout.MARBLE_R, 0.0005f)
    }

    @Test fun counterPlateCornersStayOutOfTheBoardAndEveryMarble() {
        val board = Layout.sceneBox(Layout.boardBoxLocal())
        for (corner in Layout.plateCorners()) {
            val local = Layout.toLocal(corner)
            // above the board surface along its normal by >= 5 mm everywhere under the plate
            assertTrue("plate corner $corner local $local", local.y > 0.005f || abs(local.z) > Layout.BOARD_APOTHEM)
            for (c in 0 until Hex.N) assertFalse("plate corner in marble box at ${Hex.name(c)}", Layout.sceneBox(Layout.marbleBoxLocal(c, Layout.LIFT)).contains(corner, 0.005f))
        }
        // the plate's bottom edge is above the board's scene box top behind it (no clipping into the wood)
        val bottom = Layout.plateCentre().y - Layout.PLATE_H / 2
        val farTop = Layout.toScene(V3(0f, 0f, -Layout.BOARD_APOTHEM)).y
        assertTrue("plate bottom $bottom over far rim $farTop", bottom > farTop + 0.03f)
        assertTrue(board.max.y > 0f)
    }

    @Test fun noEyeLosesACellOrAMarbleTopBehindThePlate() {
        var rays = 0
        for ((name, eye) in Layout.eyes()) for (c in 0 until Hex.N) {
            for (up in listOf(0f, Layout.MARBLE_Y + Layout.MARBLE_R)) {
                val target = Layout.toScene(Layout.cell(c, up))
                rays++
                assertFalse("$name -> ${Hex.name(c)} up=$up", Layout.plateBlocks(eye, target))
            }
        }
        println("plate occlusion rays: $rays")
    }

    @Test fun everythingFitsTheVolume() {
        val vol = Layout.volume()
        val board = Layout.sceneBox(Layout.boardBoxLocal())
        assertTrue("board $board in $vol", board.min.x >= vol.min.x && board.max.x <= vol.max.x && board.min.z >= vol.min.z && board.max.z <= vol.max.z && board.min.y >= -0.001f)
        for (corner in Layout.plateCorners()) assertTrue("plate $corner", vol.contains(corner))
        // the highest thing: a lifted marble on the far row with a pad over it, and the drop arc over the far rim
        val farPad = Layout.toScene(Layout.axial(0f, -5f, Layout.PAD_Y + Layout.PAD_T + 0.004f))
        assertTrue("far pad $farPad", farPad.y < vol.max.y)
        val arcTop = Layout.toScene(V3(0f, Layout.MARBLE_Y + Layout.ARC + Layout.MARBLE_R, -Layout.FIELD_R * 0.87f))
        assertTrue("drop arc $arcTop", arcTop.y < vol.max.y)
        for (i in 0 until Layout.SLOTS) assertTrue("slot $i", vol.contains(Layout.toScene(Layout.slot(i))))
    }

    @Test fun padsKeepClearOfEachOtherAndOfTheMarblesBelow() {
        // six n = 1 pads around a centre cell: collider squares (board-aligned) 5.2 cm apart at 60° steps never overlap
        val centre = Hex.at(0, 0)
        val pads = Abalone.pads(Abalone.START.let { Pos(ByteArray(Hex.N).also { a -> a[centre] = 1 }, intArrayOf(0, 0)) }, 0, listOf(centre))
        assertEquals(6, pads.size)
        val h = Layout.PAD_HIT / 2
        for (i in pads.indices) for (j in i + 1 until pads.size) {
            val a = Layout.pad(pads[i]); val b = Layout.pad(pads[j])
            val overlap = abs(a.x - b.x) < 2 * h - 1e-4f && abs(a.z - b.z) < 2 * h - 1e-4f
            assertFalse("pads d${pads[i].dir} / d${pads[j].dir} overlap", overlap)
        }
        // pad underside >= 1 cm over the marble top, so a ray from above meets the pad first
        assertTrue(Layout.PAD_Y - (Layout.MARBLE_Y + Layout.MARBLE_R + Layout.LIFT) >= 0.0035f)
        assertTrue(Layout.PAD_Y - (Layout.MARBLE_Y + Layout.MARBLE_R) >= 0.01f)
    }

    @Test fun padAngularSizeFromTheSeatedEye() {
        val eye = Layout.eyes().getValue("sit")
        var worst = 99.0
        for (c in 0 until Hex.N) {
            val p = Layout.toScene(Layout.cell(c, Layout.PAD_Y))
            val d = (p - eye).len()
            val deg = Math.toDegrees(2 * atan2(Layout.PAD_HIT / 2.0, d.toDouble()))
            worst = minOf(worst, deg)
        }
        println("smallest pad collider angle from the seated eye: %.2f°".format(worst))
        assertTrue(worst > 2.0)
    }

    @Test fun gutterSlotsAreInTheTroughAndDropsTakeTheNearestFreeOne() {
        for (i in 0 until Layout.SLOTS) {
            val s = Layout.slot(i)
            val r = sqrt(s.x * s.x + s.z * s.z)
            assertTrue("slot $i r=$r", r > Layout.GUTTER_IN * 0.85f && r < Layout.GUTTER_OUT)
        }
        val e9 = Hex.parse("E9")!!
        val m = Move(0, listOf(Hex.parse("E5")!!, Hex.parse("E6")!!, Hex.parse("E7")!!), 0, true, listOf(Hex.parse("E8")!!, e9), true)
        val g = Layout.gutter(Abalone.START, listOf(m))
        assertEquals(1, g.size)
        assertEquals(Abalone.WHITE, g[0].first)
        val slot = Layout.slot(g[0].second)
        assertTrue("dropped to the right-hand gutter $slot", slot.x > 0.15f)
        assertEquals(36, (0 until Layout.SLOTS).map { Layout.slot(it) }.toSet().size)
    }

    @Test fun boardRoundTripsBetweenSceneAndLocal() {
        val p = V3(0.1f, 0.02f, -0.15f)
        val q = Layout.toLocal(Layout.toScene(p))
        assertEquals(p.x, q.x, 1e-5f); assertEquals(p.y, q.y, 1e-5f); assertEquals(p.z, q.z, 1e-5f)
        // far edge up by 20°
        val near = Layout.toScene(V3(0f, 0f, Layout.BOARD_APOTHEM)); val far = Layout.toScene(V3(0f, 0f, -Layout.BOARD_APOTHEM))
        val deg = Math.toDegrees(atan2((far.y - near.y).toDouble(), (near.z - far.z).toDouble()))
        assertEquals(20.0, deg, 0.01)
    }
}
