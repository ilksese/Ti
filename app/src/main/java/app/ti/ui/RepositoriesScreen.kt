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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.ti.AppContainer
import app.ti.validLocalRepositoryName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepositoriesScreen(container: AppContainer, nav: NavController) {
    val repositories by container.dao.observeRepositories().collectAsStateWithLifecycle(emptyList())
    var showActions by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var showLocal by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Repositories") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showActions = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add repository")
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

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text("Add repository") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showActions = false; showLocal = true },
                    ) { Text("New local repository") }
                    HorizontalDivider()
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showActions = false; showClone = true },
                    ) { Text("Clone remote repository") }
                }
            },
            confirmButton = {},
        )
    }
    if (showLocal) {
        LocalRepositoryDialog(
            onDismiss = { showLocal = false },
            onCreate = { name ->
                showLocal = false
                container.createLocalRepository(name)
            },
        )
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
private fun LocalRepositoryDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = validLocalRepositoryName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New local repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Repository name") },
                    supportingText = {
                        if (name.isNotBlank() && !valid) Text("Use 1-64 characters without / or \\")
                    },
                    singleLine = true,
                )
                Text(
                    "The repository will be created in the app's private repositories folder.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = valid) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
