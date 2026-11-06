package io.embodit.abalonepush

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.abalonepush.ui.Containers
import io.embodit.abalonepush.ui.board.BoardScreen
import io.embodit.abalonepush.ui.control.ControlWindow
import io.embodit.abalonepush.ui.theme.AbaloneTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the board volume (default
 * container, 0.66 x 0.36 x 0.56 m from the manifest) and the control window (Planar 560 x 640 dp) to its right.
 * First open and every reopen use Placement.right(board) (profile D44: no "in front of you" in Shared Space).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            AbaloneTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 640.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            AbaloneTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 560.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
