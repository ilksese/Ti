package app.ti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.ti.LocalRepositoryInspection
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepositoriesScreen(container: AppContainer, nav: NavController) {
    val repositories by container.dao.observeRepositories().collectAsStateWithLifecycle(emptyList())
    var showClone by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var confirmInitialize by remember { mutableStateOf<File?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Repositories") },
                actions = {
                    IconButton(onClick = { importError = null; showImport = true }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Add existing local repository")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showClone = true }) {
                Icon(Icons.Default.Add, contentDescription = "Clone repository")
            }
        },
    ) { padding ->
        if (repositories.isEmpty()) EmptyState("No repositories", Modifier.padding(padding).consumeWindowInsets(padding))
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = padding + PaddingValues(bottom = 88.dp),
        ) {
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
                                    "creating" -> repo.progress.ifBlank { "Importing..." }
                                    "failed" -> "Clone failed: ${repo.progress}"
                                    else -> repo.remoteUrl ?: repo.localPath
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

    if (showClone) {
        CloneDialog(
            onDismiss = { showClone = false },
            onClone = { name, url ->
                showClone = false
                container.cloneRepository(name, url)
            },
        )
    }

    if (showImport) {
        ImportDialog(
            directories = container.localRepositoryDirectories(),
            error = importError,
            onDismiss = { showImport = false },
            onSelect = { directory ->
                scope.launch {
                    runCatching { container.inspectLocalRepository(directory) }
                        .onSuccess { inspection: LocalRepositoryInspection ->
                            when {
                                inspection.duplicate != null -> importError = "This directory is already added"
                                inspection.isGitRepository -> {
                                    showImport = false
                                    container.importLocalRepository(inspection.directory, initialize = false)
                                }
                                else -> {
                                    showImport = false
                                    confirmInitialize = inspection.directory
                                }
                            }
                        }
                        .onFailure { importError = it.message ?: "Unable to inspect directory" }
                }
            },
        )
    }

    confirmInitialize?.let { directory ->
        AlertDialog(
            onDismissRequest = { confirmInitialize = null },
            title = { Text("Initialize Git repository?") },
            text = { Text("${directory.name} is not a Git repository. Initialize Git in this existing directory?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmInitialize = null
                    container.importLocalRepository(directory, initialize = true)
                }) { Text("Initialize") }
            },
            dismissButton = { TextButton(onClick = { confirmInitialize = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ImportDialog(
    directories: List<File>,
    error: String?,
    onDismiss: () -> Unit,
    onSelect: (File) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add existing local repository") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Select a directory under the app repository root.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (directories.isEmpty()) Text("No directories available")
                directories.forEach { directory ->
                    TextButton(onClick = { onSelect(directory) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Text(directory.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CloneDialog(onDismiss: () -> Unit, onClone: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone repository") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS URL") }, singleLine = true)
                Text("Authentication and GitHub proxy use the global Git settings.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onClone(name.trim(), url.trim()) }, enabled = url.startsWith("https://")) { Text("Clone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
