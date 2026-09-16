package app.ti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.ti.AppContainer
import app.ti.data.MessageEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionScreen(container: AppContainer, nav: NavController, sessionId: String) {
    val session by container.dao.observeSession(sessionId).collectAsStateWithLifecycle(null)
    val messages by container.dao.observeMessages(sessionId).collectAsStateWithLifecycle(emptyList())
    val states by container.agents.states.collectAsStateWithLifecycle()
    val state = states[sessionId]
    val visibleMessages = messages.filterNot { it.role == "assistant" && it.content.isBlank() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(visibleMessages.size, state?.partial) {
        val extra = if (state?.partial?.isNotBlank() == true) 1 else 0
        if (visibleMessages.isNotEmpty() || extra > 0) listState.animateScrollToItem((visibleMessages.size + extra - 1).coerceAtLeast(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.title ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { rename = true }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message or /compact") },
                    maxLines = 6,
                    enabled = state?.running != true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { val value = input; input = ""; container.agents.send(sessionId, value) },
                    enabled = input.isNotBlank() && state?.running != true,
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(visibleMessages, key = { it.id }) { message ->
                MessageRow(message, container)
            }
            state?.partial?.takeIf { it.isNotBlank() }?.let { partial ->
                item { MessageBubble("assistant", partial) }
            }
            if (state?.running == true && state.partial.isBlank()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(state.retryText ?: "Working...") } }
            }
            state?.error?.let { item { ErrorText(it) } }
        }
    }
    if (rename) TextEntryDialog("Rename session", "Title", session?.title.orEmpty(), onDismiss = { rename = false }) { title ->
        rename = false
        session?.let { scope.launch { container.dao.saveSession(it.copy(title = title, titleGenerated = true, updatedAt = System.currentTimeMillis())) } }
    }
    if (confirmDelete) ConfirmDialog("Delete this session?", onDismiss = { confirmDelete = false }) {
        confirmDelete = false
        session?.let { scope.launch { container.dao.deleteSession(it); nav.popBackStack() } }
    }
}

@Composable
private fun MessageRow(message: MessageEntity, container: AppContainer) {
    if (message.role == "tool") {
        var expanded by remember(message.id) { mutableStateOf(false) }
        Card(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message.name.orEmpty(), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(message.status, style = MaterialTheme.typography.labelSmall)
                }
                if (expanded) SelectionContainer { Text(message.content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                if (message.status == "pending") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { message.toolCallId?.let { container.agents.resolveApproval(it, true) } }) { Icon(Icons.Default.Check, null); Text("Approve") }
                        TextButton(onClick = { message.toolCallId?.let { container.agents.resolveApproval(it, false) } }) { Icon(Icons.Default.Close, null); Text("Reject") }
                    }
                }
            }
        }
    } else if (message.role == "event") {
        Text(message.content, style = MaterialTheme.typography.labelMedium, color = if (message.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
    } else {
        MessageBubble(message.role, message.content)
    }
}

@Composable
private fun MessageBubble(role: String, content: String) {
    val user = role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(if (user) 0.86f else 0.96f),
        ) {
            SelectionContainer { Text(content, Modifier.padding(12.dp)) }
        }
    }
}
