package app.ti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.ti.AppContainer
import app.ti.data.SessionEntity
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(container: AppContainer, nav: NavController) {
    val sessions by container.dao.observeChatSessions().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Chat") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                scope.launch {
                    val model = container.dao.defaultModel()
                    if (model == null) {
                        error = "No model available. Add a provider and refresh its models first."
                    } else {
                        error = null
                        val now = System.currentTimeMillis()
                        val session = SessionEntity(UUID.randomUUID().toString(), null, model.id, createdAt = now, updatedAt = now)
                        container.dao.saveSession(session)
                        nav.navigate("session/${session.id}")
                    }
                }
            }) { Icon(Icons.Default.Add, "New chat") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sessions.isEmpty()) EmptyState("No chats")
            error?.let { InfoText(it) }
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(sessions, key = { it.id }) { session ->
                    ListItem(
                        headlineContent = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(session.status) },
                        leadingContent = { Icon(Icons.Default.Chat, null) },
                        modifier = Modifier.clickable { nav.navigate("session/${session.id}") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
