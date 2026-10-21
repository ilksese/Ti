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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val localRepositoryMutex = Mutex()
    val agents = AgentRunManager(dao, llm, files, git, scope)

    init {
        scope.launch(Dispatchers.IO) {
            dao.interruptActiveSessions()
            dao.interruptPendingTools()
            recoverRepositories()
        }
        scope.launch {
            if (dao.setting(MODEL_AUTO_COMPLETE)?.value == "true") modelsDev.refreshIfStale()
        }
    }

    /** Reconciles persisted repository state after a process restart. */
    private suspend fun recoverRepositories() {
        dao.repositories().forEach { repo ->
            val directory = File(repo.localPath)
            try {
                if (!directory.exists() || !directory.isDirectory) {
                    val result = repositoryRecoveryResult(repo.status, exists = false, isDirectory = false, isGitRepository = false)
                    result?.let { dao.updateRepositoryClone(repo.id, it.status, it.progress) }
                    return@forEach
                }
                val result = repositoryRecoveryResult(
                    repo.status,
                    exists = true,
                    isDirectory = true,
                    isGitRepository = git.isRepository(directory),
                )
                result?.let { dao.updateRepositoryClone(repo.id, it.status, it.progress) }
            } catch (_: SecurityException) {
                val result = repositoryRecoveryResult(
                    repo.status,
                    exists = true,
                    isDirectory = true,
                    isGitRepository = false,
                    permissionDenied = true,
                )
                result?.let { dao.updateRepositoryClone(repo.id, it.status, it.progress) }
            }
        }
    }

    /** Re-checks an unavailable repository without blocking the Repositories screen. */
    fun retryRepository(repo: RepositoryEntity) {
        scope.launch(Dispatchers.IO) {
            dao.updateRepositoryClone(repo.id, RepositoryStatus.CREATING, "Checking repository")
            try {
                val directory = File(repo.localPath)
                when {
                    !directory.exists() || !directory.isDirectory ->
                        dao.updateRepositoryClone(repo.id, RepositoryStatus.MISSING, "Directory is missing")
                    git.isRepository(directory) ->
                        dao.updateRepositoryClone(repo.id, RepositoryStatus.READY, "")
                    else ->
                        dao.updateRepositoryClone(repo.id, RepositoryStatus.INVALID, "Directory is not a Git repository")
                }
            } catch (_: SecurityException) {
                dao.updateRepositoryClone(repo.id, RepositoryStatus.PERMISSION_REQUIRED, "Permission required")
            } catch (error: Throwable) {
                dao.updateRepositoryClone(repo.id, RepositoryStatus.FAILED, error.message ?: "Repository check failed")
            }
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
            localRepositoryMutex.withLock {
                // Re-check under a process-wide lock so two submissions cannot race.
                if (directory.exists()) return@withLock
                dao.saveRepository(repo)
                var createdDirectory = false
                try {
                    repositoryRoot.mkdirs()
                    if (!directory.mkdir()) throw IllegalStateException("A directory with this name already exists")
                    createdDirectory = true
                    dao.updateRepositoryClone(id, RepositoryStatus.CREATING, "Initializing Git")
                    git.init(directory)
                    coroutineContext.ensureActive()
                    dao.updateRepositoryClone(id, RepositoryStatus.READY, "")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    dao.updateRepositoryClone(id, RepositoryStatus.FAILED, error.message ?: "Repository creation failed")
                    // The directory was created by this operation, so partial JGit output is safe to remove.
                    if (createdDirectory) withContext(Dispatchers.IO) { directory.deleteRecursively() }
                }
            }
        }
        cloneJobs[id] = job
        job.invokeOnCompletion { cloneJobs.remove(id) }
    }


    /** Lists importable repositories without leaving the app-private repository root. */
    suspend fun localRepositoryDirectories(): List<File> = withContext(Dispatchers.IO) {
        repositoryRoot
            .listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    /** Checks a name without scanning the repository root; used for immediate form validation. */
    fun localRepositoryNameExists(name: String): Boolean =
        validLocalRepositoryName(name) && File(repositoryRoot, name.trim()).exists()

    /** Inspects a direct child of the app-private repository root before importing it. */
    suspend fun inspectLocalRepository(directory: File): LocalRepositoryInspection = withContext(Dispatchers.IO) {
        val canonical = validateLocalRepositoryDirectory(directory)
        val duplicate = dao.repositories().firstOrNull { repository ->
            runCatching { File(repository.localPath).canonicalFile == canonical }.getOrDefault(false)
        }
        LocalRepositoryInspection(
            directory = canonical,
            isGitRepository = git.isRepository(canonical),
            duplicate = duplicate,
        )
    }

    /** Adds an existing directory, optionally initializing Git after explicit user confirmation. */
    fun importLocalRepository(directory: File, initialize: Boolean) {
        scope.launch {
            val canonical = try {
                validateLocalRepositoryDirectory(directory)
            } catch (_: Throwable) {
                return@launch
            }
            val id = UUID.randomUUID().toString()
            val repo = RepositoryEntity(
                id = id,
                name = canonical.name,
                remoteUrl = null,
                localPath = canonical.path,
                username = "",
                token = "",
                createdAt = System.currentTimeMillis(),
                status = RepositoryStatus.CREATING,
                progress = if (initialize) "Initializing Git" else "Checking repository",
                source = RepositorySource.LOCAL,
                storageType = RepositoryStorageType.APP_PRIVATE,
            )
            val inserted = localRepositoryMutex.withLock {
                val existing = withContext(Dispatchers.IO) {
                    dao.repositories().firstOrNull { runCatching { File(it.localPath).canonicalFile == canonical }.getOrDefault(false) }
                }
                if (existing != null) false else {
                    dao.saveRepository(repo)
                    true
                }
            }
            if (!inserted) return@launch
            try {
                if (initialize) git.initialize(canonical)
                check(git.isRepository(canonical)) { "Directory is not a Git repository" }
                dao.updateRepositoryClone(id, RepositoryStatus.READY, "")
            } catch (error: Throwable) {
                dao.updateRepositoryClone(id, RepositoryStatus.FAILED, error.message ?: "Import failed")
            }
        }
    }

    private suspend fun validateLocalRepositoryDirectory(directory: File): File = withContext(Dispatchers.IO) {
        val root = repositoryRoot.apply { mkdirs() }.canonicalFile
        val canonical = directory.canonicalFile
        require(canonical.isDirectory) { "Select an existing directory" }
        require(canonical.parentFile == root) { "Only directories under the app repository root can be imported" }
        canonical

    }

    /** Removes a repository from the list without touching its files. */
    fun removeRepository(repo: RepositoryEntity) {
        val job = cloneJobs.remove(repo.id)
        scope.launch {
            job?.cancelAndJoin()
            dao.deleteRepository(repo)
        }
    }

    /**
     * Deletes the repository directory while retaining its record as missing.
     * Keeping the record lets users remove it from the list separately and makes
     * the destructive action explicit in the UI.
     */
    fun deleteRepositoryFiles(repo: RepositoryEntity) {
        val job = cloneJobs.remove(repo.id)
        scope.launch {
            try {
                job?.cancelAndJoin()
                withContext(Dispatchers.IO) {
                    val root = repositoryRoot.canonicalFile
                    val directory = File(repo.localPath).canonicalFile
                    require(isSafeRepositoryPath(root, directory)) { "Repository path is outside the app repository root" }
                    if (directory.exists()) {
                        check(directory.deleteRecursively()) { "Unable to delete repository files" }
                    }
                }
                dao.updateRepositoryClone(repo.id, RepositoryStatus.MISSING, "Local files deleted")
            } catch (error: Throwable) {
                dao.updateRepositoryClone(repo.id, RepositoryStatus.FAILED, error.message ?: "Unable to delete repository files")
            }
        }
    }

    /** Compatibility alias for callers that used the old destructive operation. */
    fun deleteRepository(repo: RepositoryEntity) = removeRepository(repo)

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


data class LocalRepositoryInspection(
    val directory: File,
    val isGitRepository: Boolean,
    val duplicate: RepositoryEntity?,
)

internal fun isSafeRepositoryPath(root: File, directory: File): Boolean =
    directory != root && directory.parentFile == root

internal data class RepositoryRecoveryResult(val status: String, val progress: String)

/** Maps persisted repository state and on-disk checks to the state shown after restart. */
internal fun repositoryRecoveryResult(
    previousStatus: String,
    exists: Boolean,
    isDirectory: Boolean,
    isGitRepository: Boolean,
    permissionDenied: Boolean = false,
): RepositoryRecoveryResult? {
    if (permissionDenied) return RepositoryRecoveryResult(RepositoryStatus.PERMISSION_REQUIRED, "Permission required")
    if (!exists || !isDirectory) return RepositoryRecoveryResult(RepositoryStatus.MISSING, "Directory is missing")
    return when (previousStatus) {
        RepositoryStatus.CREATING, RepositoryStatus.CLONING -> if (isGitRepository) {
            RepositoryRecoveryResult(RepositoryStatus.READY, "")
        } else {
            RepositoryRecoveryResult(RepositoryStatus.FAILED, "Operation interrupted; tap Retry to check again")
        }
        RepositoryStatus.READY -> if (isGitRepository) {
            null
        } else {
            RepositoryRecoveryResult(RepositoryStatus.INVALID, "Directory is not a Git repository")
        }
        else -> null
    }
}


private const val MODEL_AUTO_COMPLETE = "ai_model_autocomplete"
