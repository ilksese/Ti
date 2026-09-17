package app.ti.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ti.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TiAppNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun awaitClickableText(text: String) {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitContentDescription(description: String) {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun switchesBetweenRootTabs() {
        awaitClickableText("Chat")
        awaitClickableText("Providers")
        awaitClickableText("Settings")

        rule.onNode(hasText("Chat") and hasClickAction()).performClick()
        awaitContentDescription("New chat")
        rule.onNodeWithContentDescription("New chat").assertIsDisplayed()

        rule.onNode(hasText("Providers") and hasClickAction()).performClick()
        awaitContentDescription("Add provider")
        rule.onNodeWithContentDescription("Add provider").assertIsDisplayed()

        rule.onNode(hasText("Repositories") and hasClickAction()).performClick()
        awaitContentDescription("Clone repository")
        rule.onNodeWithContentDescription("Clone repository").assertIsDisplayed()
    }

    @Test
    fun opensNestedSettingsScreens() {
        awaitClickableText("Settings")

        rule.onNode(hasText("Settings") and hasClickAction()).performClick()
        awaitClickableText("Model auto-completion")

        rule.onNodeWithText("AI").performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Model auto-completion").assertIsDisplayed()
    }
}