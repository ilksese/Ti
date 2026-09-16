package app.ti.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.ti.AppContainer
import app.ti.data.SettingEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(nav: NavController) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("AI") },
                supportingContent = { Text("Model auto-completion") },
                leadingContent = { Icon(Icons.Default.AutoAwesome, null) },
                modifier = Modifier.clickable { nav.navigate("settings/ai") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Git") },
                supportingContent = { Text("Identity, token, GitHub proxy") },
                leadingContent = { Icon(Icons.Default.Code, null) },
                modifier = Modifier.clickable { nav.navigate("settings/git") },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSettingsScreen(container: AppContainer, nav: NavController) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        enabled = container.dao.setting("ai_model_autocomplete")?.value == "true"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Model auto-completion", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Auto-complete models using models.dev",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (saving) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                saving = true
                                scope.launch {
                                    enabled = container.enableModelAutoComplete()
                                    saving = false
                                }
                            } else {
                                enabled = false
                                scope.launch { container.dao.saveSetting(SettingEntity("ai_model_autocomplete", "false")) }
                            }
                        },
                    )
                }
            }
            Text(
                "Fills reasoning level, reasoning mode, context window, max input, and pricing for your models. " +
                    "Data is cached locally and refreshes when enabled or every 2 hours. " +
                    "If a refresh fails, the cached data is kept until the next refresh.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitSettingsScreen(container: AppContainer, nav: NavController) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var proxy by remember { mutableStateOf("github") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        name = container.dao.setting("git_author_name")?.value.orEmpty()
        email = container.dao.setting("git_author_email")?.value.orEmpty()
        token = container.dao.setting("git_token")?.value.orEmpty()
        proxy = container.dao.setting("git_proxy")?.value ?: "github"
    }
    fun update(key: String, value: String, reconfigureRemotes: Boolean = false) {
        scope.launch {
            container.dao.saveSetting(SettingEntity(key, value))
            if (reconfigureRemotes) {
                val failures = container.dao.repositories().mapNotNull { repo ->
                    runCatching { container.git.configureRemote(repo, value) }.exceptionOrNull()
                }
                error = failures.firstOrNull()?.let { "Saved, but a repository remote failed to update: ${it.message}" }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Git") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Global identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(name, { name = it; update("git_author_name", it.trim()) }, label = { Text("Author name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(email, { email = it; update("git_author_email", it.trim()) }, label = { Text("Author email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Authentication", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                token,
                { token = it; update("git_token", it.trim()) },
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
                        onClick = { proxy = value; update("git_proxy", value, reconfigureRemotes = true) },
                        shape = SegmentedButtonDefaults.itemShape(index, proxies.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            Text("The proxy applies to public GitHub clone and pull. Push always uses the original URL.", style = MaterialTheme.typography.bodySmall)
            error?.let { ErrorText(it) }
        }
    }
}
