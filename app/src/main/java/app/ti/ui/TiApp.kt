package app.ti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.ti.AppContainer
import app.ti.data.MessageEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.RepositoryEntity
import app.ti.data.SessionEntity
import app.ti.data.SettingEntity
import app.ti.git.withGlobalGitToken
import kotlinx.coroutines.launch
import java.util.UUID

private val TiColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    secondary = Color(0xFF455A64),
    tertiary = Color(0xFFB23A48),
    background = Color(0xFFF7F8F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EEEC),
)

@Composable
fun TiApp(container: AppContainer) {
    MaterialTheme(colorScheme = TiColors) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val roots = listOf(
            Root("repos", "Repositories", Icons.Default.Folder),
            Root("providers", "Providers", Icons.Default.Cloud),
            Root("settings", "Settings", Icons.Default.Settings),
        )
        Scaffold(
            bottomBar = {
                if (route in roots.map { it.route }) {
                    NavigationBar {
                        roots.forEach { root ->
                            NavigationBarItem(
                                selected = route == root.route,
                                onClick = {
                                    nav.navigate(root.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(root.icon, contentDescription = root.label) },
                                label = { Text(root.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "repos", modifier = Modifier.padding(padding)) {
                composable("repos") { RepositoriesScreen(container, nav) }
                composable("repo/{id}") { backStack ->
                    RepositoryScreen(container, nav, checkNotNull(backStack.arguments?.getString("id")))
                }
                composable("session/{id}") { backStack ->
                    SessionScreen(container, nav, checkNotNull(backStack.arguments?.getString("id")))
                }
                composable("providers") { ProvidersScreen(container, nav) }
                composable("provider/{id}") { backStack ->
                    ProviderScreen(container, nav, checkNotNull(backStack.arguments?.getString("id")))
                }
                composable("settings") { SettingsScreen(container) }
            }
        }
    }
}

private data class Root(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepositoriesScreen(container: AppContainer, nav: NavController) {
    val repositories by container.dao.observeRepositories().collectAsStateWithLifecycle(emptyList())
    var showClone by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Repositories") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showClone = true }) {
                Icon(Icons.Default.Add, contentDescription = "Clone repository")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (repositories.isEmpty()) EmptyState("No repositories")
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(repositories, key = { it.id }) { repo ->
                    val ready = repo.status == "ready"
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable(enabled = ready) { nav.navigate("repo/${repo.id}") },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        ListItem(
                            headlineContent = { Text(repo.name) },
                            supportingContent = {
                                Text(
                                    when (repo.status) {
                                        "cloning" -> repo.progress.ifBlank { "Loading..." }
                                        "failed" -> "Clone failed: ${repo.progress}"
                                        else -> repo.remoteUrl
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                if (repo.status == "cloning") CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            trailingContent = {
                                if (!ready) {
                                    IconButton(onClick = { container.deleteRepository(repo) }) {
                                        Icon(Icons.Default.Delete, "Delete repository")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showClone) {
        CloneDialog(
            onDismiss = { showClone = false },
            onClone = { name, url ->
                showClone = false
                container.cloneRepository(name, url)
            },
        )
    }
}

@Composable
private fun CloneDialog(onDismiss: () -> Unit, onClone: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS URL") }, singleLine = true)
                Text("Authentication and GitHub proxy use the global Git settings.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onClone(name.trim(), url.trim()) }, enabled = url.startsWith("https://")) { Text("Clone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepositoryScreen(container: AppContainer, nav: NavController, repositoryId: String) {
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
private fun ModelPickerDialog(models: List<ModelEntity>, providers: List<ProviderEntity>, onDismiss: () -> Unit, onPick: (ModelEntity) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose model") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                items(models, key = { it.id }) { model ->
                    val provider = providers.firstOrNull { it.id == model.providerId }
                    ListItem(
                        headlineContent = { Text(model.modelId) },
                        supportingContent = { Text(provider?.name.orEmpty()) },
                        modifier = Modifier.clickable { onPick(model) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersScreen(container: AppContainer, nav: NavController) {
    val providers by container.dao.observeProviders().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Providers") }) },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add provider") } },
    ) { padding ->
        if (providers.isEmpty()) EmptyState("No providers", Modifier.padding(padding))
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(providers, key = { it.id }) { provider ->
                ListItem(
                    headlineContent = { Text(provider.name) },
                    supportingContent = { Text(provider.baseUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Default.Cloud, null) },
                    modifier = Modifier.clickable { nav.navigate("provider/${provider.id}") },
                )
                HorizontalDivider()
            }
        }
    }
    if (showAdd) {
        ProviderDialog(null, onDismiss = { showAdd = false }) { provider ->
            showAdd = false
            scope.launch {
                container.dao.saveProvider(provider)
                nav.navigate("provider/${provider.id}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderScreen(container: AppContainer, nav: NavController, providerId: String) {
    val provider by container.dao.observeProvider(providerId).collectAsStateWithLifecycle(null)
    val models by container.dao.observeModels(providerId).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editProvider by remember { mutableStateOf(false) }
    var editModel by remember { mutableStateOf<ModelEntity?>(null) }
    var addingModel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider?.name ?: "Provider") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { editProvider = true }) { Icon(Icons.Default.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { addingModel = true }) { Icon(Icons.Default.Add, "Add model") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val value = provider ?: return@Button
                            busy = true
                            scope.launch {
                                runCatching { container.llm.listModels(value) }.onSuccess { found ->
                                    container.dao.markModelsUndiscovered(value.id)
                                    val existing = container.dao.models(value.id).associateBy { it.modelId }
                                    found.forEach { id ->
                                        container.dao.saveModel(existing[id]?.copy(discovered = true) ?: ModelEntity(UUID.randomUUID().toString(), value.id, id))
                                    }
                                    result = "Found ${found.size} models"
                                }.onFailure { result = "Error: ${it.message}" }
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Icon(Icons.Default.Refresh, null); Text("Refresh / Test") }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                result?.let { InfoText(it) }
                SectionTitle("Models")
            }
            if (models.isEmpty()) item { InfoText("No models") }
            items(models, key = { it.id }) { model ->
                ListItem(
                    headlineContent = { Text(model.modelId) },
                    supportingContent = { Text("${model.contextTokens} tokens${if (!model.discovered) " · not discovered" else ""}") },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { container.dao.deleteModel(model) } }) { Icon(Icons.Default.Delete, "Delete model") }
                    },
                    modifier = Modifier.clickable { editModel = model },
                )
                HorizontalDivider()
            }
        }
    }
    provider?.let { value ->
        if (editProvider) ProviderDialog(value, onDismiss = { editProvider = false }) { updated ->
            editProvider = false
            scope.launch { container.dao.saveProvider(updated) }
        }
        if (confirmDelete) ConfirmDialog("Delete provider, models, and related sessions?", onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            scope.launch { container.dao.deleteProvider(value); nav.popBackStack() }
        }
        if (addingModel) ModelDialog(null, value.id, onDismiss = { addingModel = false }) { model ->
            addingModel = false
            scope.launch { container.dao.saveModel(model) }
        }
    }
    editModel?.let { value ->
        ModelDialog(value, value.providerId, onDismiss = { editModel = null }) { model ->
            editModel = null
            scope.launch { container.dao.saveModel(model) }
        }
    }
}

@Composable
private fun ProviderDialog(initial: ProviderEntity?, onDismiss: () -> Unit, onSave: (ProviderEntity) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    var headers by remember { mutableStateOf(initial?.headers.orEmpty()) }
    val valid = name.isNotBlank() && baseUrl.trimEnd('/').endsWith("/v1")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add provider" else "Edit provider") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL ending in /v1") }, singleLine = true)
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, singleLine = true)
                OutlinedTextField(headers, { headers = it }, label = { Text("Custom headers, one per line") }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(ProviderEntity(initial?.id ?: UUID.randomUUID().toString(), name.trim(), baseUrl.trimEnd('/'), apiKey, headers, initial?.createdAt ?: System.currentTimeMillis()))
                },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModelDialog(initial: ModelEntity?, providerId: String, onDismiss: () -> Unit, onSave: (ModelEntity) -> Unit) {
    var id by remember { mutableStateOf(initial?.modelId.orEmpty()) }
    var context by remember { mutableStateOf((initial?.contextTokens ?: 131_072).toString()) }
    var temperature by remember { mutableStateOf(initial?.temperature?.toString().orEmpty()) }
    var topP by remember { mutableStateOf(initial?.topP?.toString().orEmpty()) }
    var maxTokens by remember { mutableStateOf(initial?.maxTokens?.toString().orEmpty()) }
    val valid = id.isNotBlank() && (context.toIntOrNull() ?: 0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add model" else "Edit model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it }, label = { Text("Model ID") }, singleLine = true)
                OutlinedTextField(context, { context = it.filter(Char::isDigit) }, label = { Text("Context tokens") }, singleLine = true)
                OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature (optional)") }, singleLine = true)
                OutlinedTextField(topP, { topP = it }, label = { Text("Top P (optional)") }, singleLine = true)
                OutlinedTextField(maxTokens, { maxTokens = it.filter(Char::isDigit) }, label = { Text("Max tokens (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(ModelEntity(initial?.id ?: UUID.randomUUID().toString(), providerId, id.trim(), context.toInt(), temperature.toDoubleOrNull(), topP.toDoubleOrNull(), maxTokens.toIntOrNull(), initial?.discovered ?: false))
                },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(container: AppContainer) {
    val storedName by container.dao.observeSetting("git_author_name").collectAsStateWithLifecycle(null)
    val storedEmail by container.dao.observeSetting("git_author_email").collectAsStateWithLifecycle(null)
    val storedToken by container.dao.observeSetting("git_token").collectAsStateWithLifecycle(null)
    val storedProxy by container.dao.observeSetting("git_proxy").collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var proxy by remember { mutableStateOf("github") }
    var saved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(storedName?.value) { name = storedName?.value.orEmpty() }
    LaunchedEffect(storedEmail?.value) { email = storedEmail?.value.orEmpty() }
    LaunchedEffect(storedToken?.value) { token = storedToken?.value.orEmpty() }
    LaunchedEffect(storedProxy?.value) { proxy = storedProxy?.value ?: "github" }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Git", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Global identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(name, { name = it; saved = false }, label = { Text("Author name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(email, { email = it; saved = false }, label = { Text("Author email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Authentication", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                token,
                { token = it; saved = false },
                label = { Text("Git token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text("GitHub proxy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val proxies = listOf("github" to "GitHub", "gh-proxy" to "gh-proxy", "gitclone" to "gitclone")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                proxies.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = proxy == value,
                        onClick = { proxy = value; saved = false },
                        shape = SegmentedButtonDefaults.itemShape(index, proxies.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            Text("The proxy applies to public GitHub clone and pull. Push always uses the original URL.", style = MaterialTheme.typography.bodySmall)
            error?.let { ErrorText(it) }
            Button(
                onClick = {
                    saving = true
                    saved = false
                    error = null
                    scope.launch {
                        container.dao.saveSetting(SettingEntity("git_author_name", name.trim()))
                        container.dao.saveSetting(SettingEntity("git_author_email", email.trim()))
                        container.dao.saveSetting(SettingEntity("git_token", token.trim()))
                        container.dao.saveSetting(SettingEntity("git_proxy", proxy))
                        val failures = container.dao.repositories().mapNotNull { repo ->
                            runCatching { container.git.configureRemote(repo, proxy) }.exceptionOrNull()
                        }
                        error = failures.firstOrNull()?.let { "Settings saved, but a repository remote failed to update: ${it.message}" }
                        saved = failures.isEmpty()
                        saving = false
                    }
                },
                enabled = name.isNotBlank() && email.isNotBlank() && !saving,
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (saved) "Saved" else "Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionScreen(container: AppContainer, nav: NavController, sessionId: String) {
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

@Composable
private fun TextEntryDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
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

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun InfoText(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.secondary) }
}
