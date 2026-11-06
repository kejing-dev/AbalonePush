package io.embodit.abalonepush.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The 61-cell hexagonal Abalone board (design gameSpec.board / coords). Axial coordinates (q, r), centre (0, 0),
 * radius 4: max(|q|, |r|, |q + r|) <= 4. x = P·(q + r/2), z = P·(√3/2)·r, +Z toward the user: r = +4 is the near row
 * (black, 5 cells), r = -4 the far row (white). Rows are horizontal; the widest row (9 cells) is r = 0.
 *
 * Directions d0..d5: (+1,0) right, (+1,-1) right-far, (0,-1) left-far, (-1,0) left, (-1,+1) left-near, (0,+1) right-near.
 * Standard notation (debug and logs only): rows A..I from the near edge (A = r = +4), columns 1..9 counted from the
 * left end of the A row's diagonal (column = q + 5 for A..E, so A1..A5, E1..E9, I5..I9).
 */
object Hex {
    const val RADIUS = 4
    const val N = 61
    /** Cell pitch (m) and row pitch (P·√3/2 = 0.045). */
    const val P = 0.052f
    val ROW = P * sqrt(3f) / 2f

    val DQ = intArrayOf(1, 1, 0, -1, -1, 0)
    val DR = intArrayOf(0, -1, -1, 0, 1, 1)

    /** Cell index -> q / r, in reading order from the far row (r = -4) to the near row, left to right. */
    val Q = IntArray(N)
    val R = IntArray(N)
    private val index = HashMap<Int, Int>()

    /** NEIGHBOUR[i * 6 + d] = the cell next to i in direction d, or -1 off the board. */
    val NEIGHBOUR = IntArray(N * 6)

    init {
        var i = 0
        for (r in -RADIUS..RADIUS) for (q in -RADIUS..RADIUS) {
            if (abs(q + r) > RADIUS) continue
            Q[i] = q; R[i] = r; index[key(q, r)] = i; i++
        }
        check(i == N)
        for (c in 0 until N) for (d in 0..5) NEIGHBOUR[c * 6 + d] = at(Q[c] + DQ[d], R[c] + DR[d])
    }

    private fun key(q: Int, r: Int) = (q + 16) * 64 + (r + 16)

    fun onBoard(q: Int, r: Int): Boolean = max(max(abs(q), abs(r)), abs(q + r)) <= RADIUS

    /** Cell index of (q, r) or -1. */
    fun at(q: Int, r: Int): Int = if (onBoard(q, r)) index[key(q, r)] ?: -1 else -1

    fun next(c: Int, d: Int): Int = NEIGHBOUR[c * 6 + d]

    fun opposite(d: Int): Int = (d + 3) % 6

    /** Hex distance from the centre (0 = centre, 4 = the rim ring). */
    fun ring(c: Int): Int = max(max(abs(Q[c]), abs(R[c])), abs(Q[c] + R[c]))

    /** Board-local metres (x right, z toward the user) of any axial point, on or off the board. */
    fun x(q: Float, r: Float): Float = P * (q + r / 2f)
    fun z(r: Float): Float = ROW * r
    fun x(c: Int): Float = x(Q[c].toFloat(), R[c].toFloat())
    fun z(c: Int): Float = z(R[c].toFloat())

    /** Unit direction of d on the board plane (x, z). */
    fun dirX(d: Int): Float = x(DQ[d].toFloat(), DR[d].toFloat()) / P
    fun dirZ(d: Int): Float = z(DR[d].toFloat()) / P

    /** "A1".."I9" (debug / logs): row letter from the near edge (A = r = +4), column = q + 5. */
    fun name(c: Int): String = "${'A' + (4 - R[c])}${Q[c] + 5}"

    fun parse(s: String): Int? {
        val t = s.trim().uppercase()
        if (t.contains(',')) {
            val f = t.split(',').mapNotNull { it.trim().toIntOrNull() }
            return if (f.size == 2) at(f[0], f[1]).takeIf { it >= 0 } else null
        }
        if (t.length != 2 || t[0] !in 'A'..'I' || !t[1].isDigit()) return null
        val r = 4 - (t[0] - 'A')
        val q = t[1].digitToInt() - 5
        return at(q, r).takeIf { it >= 0 }
    }
}
