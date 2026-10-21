package app.ti.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ti.MainActivity
import app.ti.TiApplication
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EdgeToEdgeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private var providerId: String? = null
    private val container get() = (rule.activity.application as TiApplication).container

    @After
    fun cleanUp() {
        rule.runOnIdle {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        runBlocking {
            providerId?.let { id -> container.dao.provider(id)?.let { container.dao.deleteProvider(it) } }
        }
    }

    @Test
    fun lightThemeUsesDarkSystemBarIconsWithoutNavigationScrim() {
        rule.runOnIdle {
            val window = rule.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
            assertFalse(window.isNavigationBarContrastEnforced)
        }
    }

    @Test
    fun rootTopBarDoesNotDoubleStatusInsetAndFabStaysAboveNavigation() {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithContentDescription("Clone repository").fetchSemanticsNodes().isNotEmpty()
        }
        val title = rule.onNode(hasText("Repositories") and !hasClickAction()).fetchSemanticsNode().boundsInWindow
        val fab = rule.onNodeWithContentDescription("Clone repository").fetchSemanticsNode().boundsInWindow
        val tab = rule.onNode(hasText("Settings") and hasClickAction()).fetchSemanticsNode().boundsInWindow
        val insets = rootInsets()
        val density = rule.activity.resources.displayMetrics.density

        assertEquals(insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + 32f * density, title.center.y, 2f * density)
        assertTrue("FAB overlaps bottom navigation", fab.bottom <= tab.top)
        assertTrue(
            "Bottom navigation overlaps system navigation",
            tab.bottom <= rule.activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + 1,
        )
    }

    @Test
    fun gitTokenInputRemainsAboveKeyboard() {
        rule.onNode(hasText("Settings") and hasClickAction()).performClick()
        rule.onNode(hasText("Git") and hasClickAction()).performClick()
        val input = rule.onNode(hasText("Git token") and hasSetTextAction())
        input.performScrollTo().performClick().assertIsFocused()
        showKeyboard()

        assertAboveKeyboard(input)
    }

    @Test
    fun chatInputAndSendButtonRemainAboveKeyboard() {
        val provider = ProviderEntity(UUID.randomUUID().toString(), "insets-provider", "https://api.test/v1", "", "", 0)
        val model = ModelEntity(UUID.randomUUID().toString(), provider.id, "insets-model")
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            repositoryId = null,
            modelId = model.id,
            title = "Insets chat",
            createdAt = 1,
            updatedAt = 1,
        )
        providerId = provider.id
        runBlocking {
            container.dao.saveProvider(provider)
            container.dao.saveModel(model)
            container.dao.saveSession(session)
        }
        rule.onNode(hasText("Chat") and hasClickAction()).performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText(session.title).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText(session.title) and hasClickAction()).performClick()
        val input = rule.onNode(hasText("Message or /compact") and hasSetTextAction())
        input.performClick().assertIsFocused()
        showKeyboard()

        assertAboveKeyboard(input)
        assertAboveKeyboard(rule.onNodeWithContentDescription("Send"))
    }

    private fun rootInsets(): WindowInsetsCompat = checkNotNull(ViewCompat.getRootWindowInsets(rule.activity.window.decorView))

    private fun keyboardTop(): Int = rule.activity.window.decorView.height - rootInsets().getInsets(WindowInsetsCompat.Type.ime()).bottom

    private fun assertAboveKeyboard(node: SemanticsNodeInteraction) {
        // Android IME and bring-into-view animations are not driven by the Compose test clock.
        rule.waitUntil(timeoutMillis = 5_000) {
            val bounds = node.getUnclippedBoundsInRoot()
            with(rule.density) {
                bounds.top.toPx() >= rootInsets().getInsets(WindowInsetsCompat.Type.statusBars()).top &&
                    bounds.bottom.toPx() <= keyboardTop() + 1
            }
        }
        node.assertIsDisplayed()
    }

    private fun showKeyboard() {
        rule.runOnIdle {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil(timeoutMillis = 15_000) { rootInsets().isVisible(WindowInsetsCompat.Type.ime()) }
        rule.waitForIdle()
    }
}
