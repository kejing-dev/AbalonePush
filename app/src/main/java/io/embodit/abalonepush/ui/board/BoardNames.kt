package io.embodit.abalonepush.ui.board

import io.embodit.abalonepush.domain.GameFlow
import io.embodit.abalonepush.domain.Hex

/**
 * Entity names of the board volume and what a hit on them means (pure Kotlin, unit tested by BoardTargetTest):
 * "cell:<i>" = a cell collider, or a marble standing on cell i (the marble entity and its collider both carry the name, so
 * a hit on the marble's GLB mesh resolves up the parent chain); "pad:<d>" = a direction pad (entity + collider);
 * "board" / "boardModel" / gutter and flying marbles = the board (a tap there drops the selection).
 */
object BoardNames {
    fun cell(c: Int) = "cell:$c"
    fun pad(d: Int) = "pad:$d"

    sealed interface Hit
    data class CellHit(val cell: Int) : Hit
    data class PadHit(val dir: Int) : Hit
    data object EmptyHit : Hit

    /** A collider name -> what was tapped ("cell:<i>", "pad:<d>"; "board" / "gutter" / anything else = empty). */
    fun hitOf(name: String?): Hit {
        if (name == null) return EmptyHit
        val f = name.split(":")
        return when (f[0]) {
            "cell" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until Hex.N }?.let { CellHit(it) } ?: EmptyHit
            "pad" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..5 }?.let { PadHit(it) } ?: EmptyHit
            else -> EmptyHit
        }
    }

    /** Name -> the target the game knows (null = not ours): "cell:<i>" (cell collider, marble or its collider), "pad:<d>", the board. */
    fun resolveName(name: String): String? = when {
        hitOf(name) != EmptyHit -> name
        name == "board" || name == "boardModel" || name == "gutter" || name == "moving" || name == "dropping" -> "board"
        else -> null
    }

    /** Collider name -> the one flow call (the volume listener and the debug `tap` both come through here). */
    fun dispatch(flow: GameFlow, name: String?) {
        when (val h = hitOf(name)) {
            is CellHit -> flow.tapCell(h.cell)
            is PadHit -> flow.tapPad(h.dir)
            EmptyHit -> flow.tapEmpty()
        }
    }
}
