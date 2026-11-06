package io.embodit.abalonepush.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The board is the default container (manifest). */
object Containers {
    const val BOARD = "AbaloneBoard"
    const val CONTROL = "AbaloneControl"
    const val CONTROL_TAG = "control"
}

/** The four TabBar pages and the design state each one is (S11 settings, S12 rules). */
enum class Page(val stateId: String) { PLAY("S03"), NEW("S02"), RULES("S12"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var exitConfirm by mutableStateOf(false)
    var newConfirm by mutableStateOf(false)
}
