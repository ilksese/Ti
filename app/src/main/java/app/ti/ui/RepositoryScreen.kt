package app.ti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.ti.AppContainer
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.RepositoryEntity
import app.ti.data.SessionEntity
import app.ti.git.withGlobalGitToken
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepositoryScreen(container: AppContainer, nav: NavController, repositoryId: String) {
    val repo by container.dao.observeRepository(repositoryId).collectAsStateWithLifecycle(null)
    val sessions by container.dao.observeSessions(repositoryId).collectAsStateWithLifecycle(emptyList())
    val models by container.dao.observeModels().collectAsStateWithLifecycle(emptyList())
    val providers by container.dao.observeProviders().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showCommit by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var diff by remember { mutableStateOf<String?>(null) }

    fun refresh(value: RepositoryEntity) {
        scope.launch { status = runCatching { container.git.status(value) }.getOrElse { "Error: ${it.message}" } }
    }
    fun gitAction(value: RepositoryEntity, action: suspend () -> String) {
        busy = true
        result = null
        scope.launch {
            runCatching { action() }.onSuccess { result = it }.onFailure { result = "Error: ${it.message}" }
            refresh(value)
            busy = false
        }
    }
    LaunchedEffect(repo?.id) { repo?.let(::refresh) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(repo?.name ?: "Repository", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete repository") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (models.isNotEmpty()) showNewSession = true }) {
                Icon(Icons.Default.Add, "New session")
            }
        },
    ) { padding ->
        val value = repo
        if (value == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refresh(value) }, enabled = !busy) { Icon(Icons.Default.Refresh, null); Text("Status") }
                    OutlinedButton(onClick = { gitAction(value) { container.git.pull(container.dao.withGlobalGitToken(value)) } }, enabled = !busy) { Text("Pull") }
                    OutlinedButton(onClick = { gitAction(value) { container.git.push(container.dao.withGlobalGitToken(value)) } }, enabled = !busy) { Text("Push") }
                    OutlinedButton(onClick = { showCommit = true }, enabled = !busy) { Text("Commit") }
                    OutlinedButton(onClick = { showBranches = true }, enabled = !busy) { Text("Branches") }
                }
                if (busy) CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(22.dp), strokeWidth = 2.dp)
                result?.let { InfoText(it) }
                SectionTitle("Changes")
            }
            if (status.isBlank()) item { InfoText("Loading...") }
            else items(status.lines()) { line ->
                ListItem(
                    headlineContent = { Text(line, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.clickable(enabled = !line.contains("clean", true)) {
                        val path = line.drop(3).trim()
                        scope.launch { diff = runCatching { container.git.diff(value, path) }.getOrElse { "Error: ${it.message}" } }
                    },
                )
                HorizontalDivider()
            }
            item { SectionTitle("Sessions") }
            if (sessions.isEmpty()) item { InfoText("No sessions") }
            items(sessions, key = { it.id }) { session ->
                ListItem(
                    headlineContent = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(session.status) },
                    modifier = Modifier.clickable { nav.navigate("session/${session.id}") },
                )
                HorizontalDivider()
            }
        }
    }

    val value = repo
    if (value != null && showCommit) {
        TextEntryDialog("Commit all changes", "Message", onDismiss = { showCommit = false }) { message ->
            showCommit = false
            scope.launch {
                val name = container.dao.setting("git_author_name")?.value.orEmpty()
                val email = container.dao.setting("git_author_email")?.value.orEmpty()
                if (name.isBlank() || email.isBlank()) result = "Error: configure Git author in Settings"
                else gitAction(value) { container.git.commit(value, message, name, email) }
            }
        }
    }
    if (value != null && showBranches) {
        BranchDialog(container, value, onDismiss = { showBranches = false }) { output ->
            result = output
            refresh(value)
        }
    }
    if (showNewSession) {
        ModelPickerDialog(models, providers, onDismiss = { showNewSession = false }) { model ->
            showNewSession = false
            scope.launch {
                val now = System.currentTimeMillis()
                val session = SessionEntity(UUID.randomUUID().toString(), repositoryId, model.id, createdAt = now, updatedAt = now)
                container.dao.saveSession(session)
                nav.navigate("session/${session.id}")
            }
        }
    }
    diff?.let { DiffDialog(it, onDismiss = { diff = null }) }
    if (value != null && confirmDelete) {
        ConfirmDialog("Delete repository and all sessions?", onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            container.deleteRepository(value)
            nav.popBackStack()
        }
    }
}

@Composable
private fun BranchDialog(container: AppContainer, repo: RepositoryEntity, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun reload() { scope.launch { branches = runCatching { container.git.branches(repo) }.getOrElse { error = it.message; emptyList() } } }
    fun action(block: suspend () -> String) {
        busy = true
        scope.launch {
            runCatching { block() }.onSuccess(onResult).onFailure { error = it.message }
            reload()
            busy = false
        }
    }
    LaunchedEffect(repo.id) { reload() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branches") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text("New branch") }, modifier = Modifier.weight(1f), singleLine = true)
                    IconButton(onClick = { action { container.git.createBranch(repo, name.trim()) } }, enabled = name.isNotBlank() && !busy) { Icon(Icons.Default.Add, "Create") }
                }
                error?.let { ErrorText(it) }
                branches.forEach { branch ->
                    val clean = branch.removePrefix("* ").trim()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(branch, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        if (!branch.startsWith("*")) {
                            TextButton(onClick = { action { container.git.switchBranch(repo, clean) } }, enabled = !busy) { Text("Switch") }
                            IconButton(onClick = { action { container.git.deleteBranch(repo, clean) } }, enabled = !busy) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ModelPickerDialog(models: List<ModelEntity>, providers: List<ProviderEntity>, onDismiss: () -> Unit, onPick: (ModelEntity) -> Unit) {
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val filtered = models.filter { model ->
        model.modelId.contains(needle, ignoreCase = true) ||
            providers.firstOrNull { it.id == model.providerId }?.name.orEmpty().contains(needle, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose model") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search models") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (query.isEmpty()) null else {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } }
                    },
                )
                if (filtered.isEmpty()) InfoText("No matching models")
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        val provider = providers.firstOrNull { it.id == model.providerId }
                        ListItem(
                            headlineContent = { Text(model.modelId) },
                            supportingContent = { Text(provider?.name.orEmpty()) },
                            modifier = Modifier.clickable { onPick(model) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DiffDialog(content: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diff") },
        text = {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                SelectionContainer { Text(content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
