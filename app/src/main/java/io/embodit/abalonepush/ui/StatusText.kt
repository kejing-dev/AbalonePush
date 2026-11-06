package io.embodit.abalonepush.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.embodit.abalonepush.R
import io.embodit.abalonepush.domain.Abalone
import io.embodit.abalonepush.domain.Mode
import io.embodit.abalonepush.domain.PlayState
import io.embodit.abalonepush.domain.Reason
import io.embodit.abalonepush.domain.StatusKey

@Composable
fun sideName(side: Int): String = stringResource(if (side == Abalone.BLACK) R.string.side_black else R.string.side_white)

@Composable
fun reasonText(r: Reason, long: Boolean = false): String {
    val base = stringResource(when (r) {
        Reason.EDGE -> R.string.al_edge
        Reason.FEWER -> R.string.al_fewer
        Reason.THREE -> R.string.al_three
        Reason.SANDWICHED -> R.string.al_sandwiched
        Reason.SIDEWAYS -> R.string.al_sideways
        Reason.NOT_LINE -> R.string.al_not_line
        Reason.NOT_YOURS -> R.string.al_not_yours
        Reason.EMPTY -> R.string.al_empty
        Reason.THINKING -> R.string.al_empty
    })
    return if (long && r in setOf(Reason.FEWER, Reason.THREE, Reason.SANDWICHED, Reason.SIDEWAYS, Reason.EDGE)) base + (if (base.last().code < 128) ". " else "，") + stringResource(R.string.al_try_other) else base
}

/**
 * The one status sentence (D38 / D45): the counter plate shows the short form ([short], <= 24 English characters /
 * 12 Chinese characters), the window the long form. Both read [PlayState.status].
 */
@Composable
fun statusText(st: PlayState, short: Boolean): String {
    val s = st.status()
    val cfg = st.config
    return when (s.key) {
        StatusKey.NONE -> ""
        StatusKey.PICK -> stringResource(R.string.st_pick, sideName(s.side))
        StatusKey.COACH_1 -> stringResource(if (short) R.string.st_coach1 else R.string.st_coach1_long)
        StatusKey.COACH_2 -> stringResource(if (short) R.string.st_coach2 else R.string.st_coach2_long)
        StatusKey.COACH_3 -> stringResource(if (short) R.string.st_coach3 else R.string.st_coach3_long)
        StatusKey.ADD_MORE -> stringResource(if (short) R.string.st_add_more else R.string.st_add_more_long)
        StatusKey.CHOOSE_ARROW -> stringResource(if (short) R.string.st_choose_arrow else R.string.st_choose_arrow_long)
        StatusKey.ALERT -> when (s.reason) {
            Reason.THINKING -> stringResource(R.string.st_thinking, sideName(s.side))
            null -> ""
            else -> reasonText(s.reason, long = !short)
        }
        StatusKey.THINKING -> stringResource(R.string.st_thinking, sideName(s.side))
        StatusKey.MOVING -> stringResource(R.string.st_moving)
        StatusKey.PUSHED_OFF -> when {
            cfg != null && cfg.mode == Mode.AI && s.side != cfg.humanSide -> stringResource(R.string.st_pushed_off_by, sideName(s.side), s.n)
            short -> stringResource(R.string.st_pushed_off, s.n)
            else -> stringResource(R.string.st_pushed_off_long, s.n, (Abalone.TO_WIN - s.n).coerceAtLeast(0))
        }
        StatusKey.RESTORED -> stringResource(R.string.st_restored)
        StatusKey.OVER -> when {
            s.side < 0 -> stringResource(R.string.st_draw)
            cfg != null && cfg.mode == Mode.AI && s.side == cfg.humanSide -> stringResource(R.string.st_you_win)
            else -> stringResource(R.string.st_side_wins, sideName(s.side))
        }
    }
}
