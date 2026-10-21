package app.ti

import android.content.Context
import app.ti.agent.AgentRunManager
import app.ti.agent.RepoFiles
import app.ti.data.ModelEntity
import app.ti.data.RepositoryEntity
import app.ti.data.RepositorySource
import app.ti.data.RepositoryStatus
import app.ti.data.RepositoryStorageType
import app.ti.data.SettingEntity
import app.ti.data.TiDatabase
import app.ti.git.GitService
import app.ti.git.gitProxy
import app.ti.git.gitProxyUrl
import app.ti.llm.ModelInfo
import app.ti.llm.ModelsDevClient
import app.ti.llm.OpenAiClient
import app.ti.llm.matchModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class AppContainer(context: Context) {
    val repositoryRoot = File(context.filesDir, "repositories")
    val database = TiDatabase.create(context)
    val dao = database.dao()
    val git = GitService()
    val llm = OpenAiClient()
    val files = RepoFiles()
    private val modelsDev = ModelsDevClient(File(context.filesDir, "models-dev-cache.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cloneJobs = ConcurrentHashMap<String, Job>()
    val agents = AgentRunManager(dao, llm, files, git, scope)

    init {
        scope.launch(Dispatchers.IO) {
            dao.interruptActiveSessions()
            dao.interruptPendingTools()
            dao.interruptCloningRepositories()
        }
        scope.launch {
            if (dao.setting(MODEL_AUTO_COMPLETE)?.value == "true") modelsDev.refreshIfStale()
        }
    }

    fun cloneRepository(name: String, url: String) {
        val id = UUID.randomUUID().toString()
        val directory = File(repositoryRoot, id)
        val repo = RepositoryEntity(
            id = id,
            name = name.ifBlank { url.substringAfterLast('/').removeSuffix(".git") },
            remoteUrl = url,
            localPath = directory.path,
            username = "",
            token = "",
            createdAt = System.currentTimeMillis(),
            status = "cloning",
            progress = "Starting clone",
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            dao.saveRepository(repo)
            try {
                val token = dao.setting("git_token")?.value.orEmpty()
                val proxy = dao.gitProxy()
                val cloneUrl = gitProxyUrl(url, proxy)
                git.clone(cloneUrl, directory, "git", if (cloneUrl == url) token else "") { progress ->
                    scope.launch { dao.updateRepositoryClone(id, "cloning", progress) }
                }
                coroutineContext.ensureActive()
                git.configureRemote(repo, proxy)
                dao.updateRepositoryClone(id, "ready", "")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                dao.updateRepositoryClone(id, "failed", error.message ?: "Clone failed")
            } finally {
                cloneJobs.remove(id)
            }
        }
        cloneJobs[id] = job
        job.start()
    }

    /** Creates a new app-private repository and initializes it with JGit. */
    fun createLocalRepository(name: String) {
        val normalized = name.trim()
        require(validLocalRepositoryName(normalized)) { "Invalid repository name" }
        val id = UUID.randomUUID().toString()
        val directory = File(repositoryRoot, normalized)
        val repo = RepositoryEntity(
            id = id,
            name = normalized,
            remoteUrl = null,
            localPath = directory.path,
            username = "",
            token = "",
            createdAt = System.currentTimeMillis(),
            status = RepositoryStatus.CREATING,
            progress = "Creating repository",
            source = RepositorySource.LOCAL,
            storageType = RepositoryStorageType.APP_PRIVATE,
        )
        val job = scope.launch {
            dao.saveRepository(repo)
            var createdDirectory = false
            try {
                if (directory.exists()) throw IllegalStateException("A directory with this name already exists")
                repositoryRoot.mkdirs()
                createdDirectory = true
                dao.updateRepositoryClone(id, RepositoryStatus.CREATING, "Initializing Git")
                git.init(directory)
                coroutineContext.ensureActive()
                dao.updateRepositoryClone(id, RepositoryStatus.READY, "")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                dao.updateRepositoryClone(id, RepositoryStatus.FAILED, error.message ?: "Repository creation failed")
                // Never remove a pre-existing directory; only clean up our empty directory.
                if (createdDirectory && directory.listFiles().isNullOrEmpty()) {
                    withContext(Dispatchers.IO) { directory.deleteRecursively() }
                }
            }
        }
        cloneJobs[id] = job
        job.invokeOnCompletion { cloneJobs.remove(id) }
    }

    fun deleteRepository(repo: RepositoryEntity) {
        val cloneJob = cloneJobs.remove(repo.id)
        scope.launch {
            dao.deleteRepository(repo)
            cloneJob?.cancelAndJoin()
            withContext(Dispatchers.IO) { File(repo.localPath).deleteRecursively() }
        }
    }

    suspend fun enrichProvider(providerId: String) {
        if (dao.setting(MODEL_AUTO_COMPLETE)?.value != "true") return
        scope.launch { modelsDev.refreshIfStale() }
        val catalog = modelsDev.cached()?.second ?: return
        enrich(providerId, catalog)
    }

    suspend fun enableModelAutoComplete(): Boolean {
        if (!modelsDev.refresh()) return false
        dao.saveSetting(SettingEntity(MODEL_AUTO_COMPLETE, "true"))
        modelsDev.cached()?.second?.let { catalog ->
            dao.providers().forEach { enrich(it.id, catalog) }
        }
        return true
    }

    private suspend fun enrich(providerId: String, catalog: Map<String, ModelInfo>) {
        dao.models(providerId).forEach { model ->
            val info = matchModelInfo(catalog, model.modelId) ?: return@forEach
            dao.saveModel(
                model.copy(
                    contextTokens = info.contextTokens ?: model.contextTokens,
                    reasoningMode = info.reasoningMode,
                    reasoningLevels = info.reasoningLevels?.joinToString(","),
                    maxInputTokens = info.maxInputTokens,
                    inputPrice = info.inputPrice,
                    outputPrice = info.outputPrice,
                    inputModalities = info.inputModalities?.joinToString(","),
                    outputModalities = info.outputModalities?.joinToString(","),
                ),
            )
        }
    }
}

/** Android-safe directory name validation for app-private local repositories. */
fun validLocalRepositoryName(name: String): Boolean {
    val value = name.trim()
    return value.isNotEmpty() && value.length <= 64 && value != "." && value != ".." &&
        value.none { it == '/' || it == '\\' || it.isISOControl() }
}

private const val MODEL_AUTO_COMPLETE = "ai_model_autocomplete"
