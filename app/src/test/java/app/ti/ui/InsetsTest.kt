package app.ti.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsetsTest {
    @Test
    fun keepsTopBarWhenKeyboardIsHiddenEvenInSmallWindows() {
        assertTrue(hasRoomForTopBar(120.dp, 24.dp, 0.dp))
    }

    @Test
    fun keepsTopBarWhenEditorAndAppBarFitAboveKeyboard() {
        assertTrue(hasRoomForTopBar(640.dp, 68.dp, 280.dp))
        assertTrue(hasRoomForTopBar(360.dp, 24.dp, 192.dp))
    }

    @Test
    fun hidesTopBarWhenKeyboardLeavesInsufficientEditorSpace() {
        assertFalse(hasRoomForTopBar(360.dp, 24.dp, 239.dp))
        assertFalse(hasRoomForTopBar(360.dp, 24.dp, 193.dp))
    }

    @Test
    fun combinesSystemInsetsWithListSpacing() {
        val systemInsets = PaddingValues(start = 10.dp, top = 24.dp, end = 20.dp, bottom = 48.dp)
        val padding = systemInsets + PaddingValues(12.dp)

        assertEquals(22.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(32.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(36.dp, padding.calculateTopPadding())
        assertEquals(60.dp, padding.calculateBottomPadding())
    }

    @Test
    fun preservesRightToLeftInsets() {
        val padding = PaddingValues(start = 10.dp, end = 20.dp) + PaddingValues(start = 3.dp, end = 7.dp)

        assertEquals(27.dp, padding.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(13.dp, padding.calculateRightPadding(LayoutDirection.Rtl))
        assertEquals(13.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(27.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test
    fun readsUpdatedScaffoldPaddingWithoutAddingFabSpaceToConsumedInsets() {
        var bottom = 24.dp
        val scaffoldPadding = object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
            override fun calculateTopPadding() = 64.dp
            override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
            override fun calculateBottomPadding() = bottom
        }
        val listPadding = scaffoldPadding + PaddingValues(bottom = 88.dp)

        assertEquals(112.dp, listPadding.calculateBottomPadding())
        bottom = 80.dp
        assertEquals(168.dp, listPadding.calculateBottomPadding())
        assertEquals(80.dp, scaffoldPadding.calculateBottomPadding())
    }
}
