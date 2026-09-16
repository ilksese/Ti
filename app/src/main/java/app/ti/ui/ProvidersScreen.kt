package app.ti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> "${tokens / 1_000}k"
    else -> tokens.toString()
}

private fun modelSummary(model: ModelEntity): String = buildString {
    append("${model.contextTokens} tokens")
    model.maxInputTokens?.let { append(" · max in ${formatTokens(it)}") }
    if (model.reasoningMode == true) {
        append(" · reasoning")
        model.reasoningLevels?.let { append(": ${it.replace(",", "/")}") }
    }
    if (model.inputPrice != null || model.outputPrice != null) {
        append(String.format(Locale.US, " · $%.2f/$%.2f per M", model.inputPrice ?: 0.0, model.outputPrice ?: 0.0))
    }
    if (!model.discovered) append(" · not discovered")
}

@Composable
private fun ModalityTags(model: ModelEntity) {
    val inputs = model.inputModalities?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    val outputs = model.outputModalities?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    if (inputs.isEmpty() && outputs.isEmpty()) return
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (inputs.isNotEmpty()) Tag("in: ${inputs.joinToString(" · ")}")
        if (outputs.isNotEmpty()) Tag("out: ${outputs.joinToString(" · ")}")
    }
}

@Composable
private fun Tag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProvidersScreen(container: AppContainer, nav: NavController) {
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
internal fun ProviderScreen(container: AppContainer, nav: NavController, providerId: String) {
    val provider by container.dao.observeProvider(providerId).collectAsStateWithLifecycle(null)
    val models by container.dao.observeModels(providerId).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editProvider by remember { mutableStateOf(false) }
    var editModel by remember { mutableStateOf<ModelEntity?>(null) }
    var addingModel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var modelQuery by remember { mutableStateOf("") }
    val filteredModels = models.filter { it.modelId.contains(modelQuery.trim(), ignoreCase = true) }

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
                                    container.enrichProvider(value.id)
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
                OutlinedTextField(
                    value = modelQuery,
                    onValueChange = { modelQuery = it },
                    label = { Text("Search models") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (modelQuery.isEmpty()) null else {
                        { IconButton(onClick = { modelQuery = "" }) { Icon(Icons.Default.Close, "Clear search") } }
                    },
                )
            }
            if (models.isEmpty()) item { InfoText("No models") }
            else if (filteredModels.isEmpty()) item { InfoText("No matching models") }
            items(filteredModels, key = { it.id }) { model ->
                ListItem(
                    headlineContent = { Text(model.modelId) },
                    supportingContent = {
                        Column {
                            Text(modelSummary(model), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ModalityTags(model)
                        }
                    },
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
