package app.ti.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ti.MainActivity
import app.ti.TiApplication
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionModelSwitchTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as TiApplication).container

    private var providerId: String? = null
    private var sessionId: String? = null

    @After
    fun cleanUp() {
        runBlocking {
            sessionId?.let { id -> container.dao.session(id)?.let { container.dao.deleteSession(it) } }
            providerId?.let { id -> container.dao.provider(id)?.let { container.dao.deleteProvider(it) } }
        }
    }

    @Test
    fun switchesTheModelOfAnExistingChatSession() {
        val provider = ProviderEntity(UUID.randomUUID().toString(), "aaa-switch-provider", "https://api.test/v1", "key", "", 0)
        providerId = provider.id
        val current = ModelEntity(UUID.randomUUID().toString(), provider.id, "aaa-current-model")
        val target = ModelEntity(UUID.randomUUID().toString(), provider.id, "aaa-target-model")
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            repositoryId = null,
            modelId = current.id,
            title = "Switch me",
            titleGenerated = true,
            createdAt = 1,
            updatedAt = 1,
        )
        sessionId = session.id
        runBlocking {
            container.dao.saveProvider(provider)
            container.dao.saveModel(current)
            container.dao.saveModel(target)
            container.dao.saveSession(session)
        }

        awaitClickableText("Chat")
        rule.onNode(hasText("Chat") and hasClickAction()).performClick()
        awaitClickableText("Switch me")
        rule.onNode(hasText("Switch me") and hasClickAction()).performClick()

        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("aaa-current-model").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Switch model").performClick()

        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("aaa-target-model").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("aaa-target-model").performClick()

        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("aaa-target-model").fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { container.dao.session(session.id)!!.modelId } == target.id
        }

        assertEquals(target.id, runBlocking { container.dao.session(session.id)!!.modelId })
        rule.onNodeWithText("aaa-target-model").assertExists()
    }

    @Test
    fun pickerSearchFiltersModelsByModelIdAndProviderName() {
        val provider = ProviderEntity(UUID.randomUUID().toString(), "aaa-search-provider", "https://api.test/v1", "key", "", 0)
        providerId = provider.id
        val alpha = ModelEntity(UUID.randomUUID().toString(), provider.id, "aaa-alpha-model")
        val beta = ModelEntity(UUID.randomUUID().toString(), provider.id, "aaa-beta-model")
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            repositoryId = null,
            modelId = alpha.id,
            title = "Search me",
            titleGenerated = true,
            createdAt = 1,
            updatedAt = 1,
        )
        sessionId = session.id
        runBlocking {
            container.dao.saveProvider(provider)
            container.dao.saveModel(alpha)
            container.dao.saveModel(beta)
            container.dao.saveSession(session)
        }

        awaitClickableText("Chat")
        rule.onNode(hasText("Chat") and hasClickAction()).performClick()
        awaitClickableText("Search me")
        rule.onNode(hasText("Search me") and hasClickAction()).performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("aaa-alpha-model").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithContentDescription("Switch model").performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("aaa-beta-model").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithText("Search models").performTextInput("zzz")
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("aaa-beta-model").fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText("No matching models").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("aaa-beta-model").fetchSemanticsNodes().isNotEmpty()
        }

        // the top bar subtitle always shows the current model, so a filtered list leaves exactly one match
        rule.onNodeWithText("Search models").performTextInput("beta")
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText("aaa-alpha-model").fetchSemanticsNodes().size == 1 &&
                rule.onAllNodesWithText("aaa-beta-model").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitClickableText(text: String) {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}