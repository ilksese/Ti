package app.ti.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun ImeAwareTopBar(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val windowHeight = with(density) { windowHeightPx.toDp() }
    val topInset = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val imeInset = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    if (hasRoomForTopBar(windowHeight, topInset, imeInset)) {
        content()
    } else {
        Surface {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing))
        }
    }
}

internal fun hasRoomForTopBar(windowHeight: Dp, topInset: Dp, imeInset: Dp): Boolean =
    // Keep room for a 64dp app bar and an 80dp editor, or prioritize the editor.
    imeInset == 0.dp || windowHeight - topInset - imeInset >= 144.dp

@Composable
internal fun NavigationBarProtection(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.surface
    Spacer(
        modifier.fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.8f), color))),
    )
}

internal operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val original = this
    return object : PaddingValues {
        override fun calculateLeftPadding(layoutDirection: LayoutDirection) =
            original.calculateLeftPadding(layoutDirection) + other.calculateLeftPadding(layoutDirection)

        override fun calculateTopPadding() = original.calculateTopPadding() + other.calculateTopPadding()

        override fun calculateRightPadding(layoutDirection: LayoutDirection) =
            original.calculateRightPadding(layoutDirection) + other.calculateRightPadding(layoutDirection)

        override fun calculateBottomPadding() = original.calculateBottomPadding() + other.calculateBottomPadding()
    }
}
