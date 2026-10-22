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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class AppContainer(
    context: Context,
    val database: TiDatabase = TiDatabase.create(context),
    val repositoryRoot: File = File(context.filesDir, "repositories"),
    val git: GitService = GitService(),
    private val initializeNewRepository: suspend (File) -> Unit = git::init,
    private val initializeExistingRepository: suspend (File) -> Unit = git::initialize,
) {
    val dao = database.dao()
    val llm = OpenAiClient()
    val files = RepoFiles()
    private val modelsDev = ModelsDevClient(File(context.filesDir, "models-dev-cache.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cloneJobs = ConcurrentHashMap<String, Job>()
    private val localRepositoryMutex = Mutex()
    val agents = AgentRunManager(dao, llm, files, git, scope)

    private val startup = scope.async(Dispatchers.IO) {
            dao.interruptActiveSessions()
            dao.interruptPendingTools()
            recoverRepositories()
    }

    init {
        scope.launch {
            if (dao.setting(MODEL_AUTO_COMPLETE)?.value == "true") modelsDev.refreshIfStale()
        }
    }

    suspend fun close() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        withContext(Dispatchers.IO) { database.close() }
    }

    private fun <T> repositoryOperation(id: String, operation: suspend () -> T): Deferred<T> {
        val task = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            startup.await()
            operation()
        }
        cloneJobs[id] = task
        task.invokeOnCompletion { cloneJobs.remove(id, task) }
        task.start()
        return task
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
    fun retryRepository(repo: RepositoryEntity): Job {
        cloneJobs[repo.id]?.takeIf { it.isActive }?.let { return it }
        return repositoryOperation(repo.id) {
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
            } catch (error: CancellationException) {
                throw error
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
    fun createLocalRepository(name: String, displayName: String = name): Deferred<String> {
        val normalized = name.trim()
        val id = UUID.randomUUID().toString()
        val directory = File(repositoryRoot, normalized)
        val repo = RepositoryEntity(
            id = id,
            name = displayName.trim().ifBlank { normalized },
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
        return repositoryOperation(id) {
            require(validLocalRepositoryName(normalized)) { "Invalid repository name" }
            localRepositoryMutex.withLock {
                // Re-check under a process-wide lock so two submissions cannot race.
                require(!directory.exists()) { "A directory with this name already exists" }
                val canonical = directory.canonicalFile
                require(isSafeRepositoryPath(repositoryRoot.canonicalFile, canonical)) { "Invalid repository path" }
                requireUniqueRepositoryPath(canonical)
                dao.saveRepository(repo.copy(localPath = canonical.path))
                var createdDirectory = false
                var createdDirectoryKey: Any? = null
                try {
                    repositoryRoot.mkdirs()
                    if (!directory.mkdir()) throw IllegalStateException("A directory with this name already exists")
                    createdDirectory = true
                    createdDirectoryKey = repositoryDirectoryKey(directory)
                    dao.updateRepositoryClone(id, RepositoryStatus.CREATING, "Initializing Git")
                    initializeNewRepository(directory)
                    coroutineContext.ensureActive()
                    dao.updateRepositoryClone(id, RepositoryStatus.READY, "")
                    id
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        dao.updateRepositoryClone(id, RepositoryStatus.FAILED, "Operation interrupted; tap Retry to check again")
                        if (createdDirectory) {
                            runCatching { cleanupNewRepositoryDirectory(directory, createdDirectoryKey) }
                                .exceptionOrNull()?.let(error::addSuppressed)
                        }
                    }
                    throw error
                } catch (error: Throwable) {
                    val status = if (error is SecurityException) RepositoryStatus.PERMISSION_REQUIRED else RepositoryStatus.FAILED
                    dao.updateRepositoryClone(id, status, error.message ?: "Repository creation failed")
                    // A file may have arrived after mkdir; only the initializer's Git output is owned.
                    if (createdDirectory) {
                        runCatching { cleanupNewRepositoryDirectory(directory, createdDirectoryKey) }
                            .exceptionOrNull()?.let(error::addSuppressed)
                    }
                    throw error
                }
            }
        }
    }

    private suspend fun requireUniqueRepositoryPath(directory: File) {
        require(dao.repositories().none { File(it.localPath).canonicalFile == directory }) {
            "This directory has already been added"
        }
    }

    private fun repositoryDirectoryKey(directory: File): Any? {
        val attributes = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return if (attributes.isDirectory && !attributes.isSymbolicLink) attributes.fileKey() else null
    }

    private fun cleanupNewRepositoryDirectory(directory: File, createdDirectoryKey: Any?) {
        // A matching path alone does not establish ownership after a rename or symlink swap.
        if (createdDirectoryKey == null || repositoryDirectoryKey(directory) != createdDirectoryKey) return
        if (!isSafeRepositoryPath(repositoryRoot.canonicalFile, directory.canonicalFile)) return
        deleteRepositoryTree(File(directory, ".git"))
        directory.delete()
    }

    private fun deleteRepositoryTree(directory: File) {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        // walkFileTree does not follow symlinks, including links inside .git.
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
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
    fun importLocalRepository(
        directory: File,
        initialize: Boolean,
        displayName: String = directory.name,
    ): Deferred<String> {
        val id = UUID.randomUUID().toString()
        return repositoryOperation(id) {
            val canonical = validateLocalRepositoryDirectory(directory)
            val repo = RepositoryEntity(
                id = id,
                name = displayName.trim().ifBlank { canonical.name },
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
            localRepositoryMutex.withLock {
                requireUniqueRepositoryPath(canonical)
                dao.saveRepository(repo)
            }
            try {
                if (!git.isRepository(canonical)) {
                    require(initialize) { "Confirm Git initialization before adding this directory" }
                    initializeExistingRepository(canonical)
                }
                coroutineContext.ensureActive()
                check(git.isRepository(canonical)) { "Directory is not a Git repository" }
                dao.updateRepositoryClone(id, RepositoryStatus.READY, "")
                id
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    dao.updateRepositoryClone(id, RepositoryStatus.FAILED, "Operation interrupted; tap Retry to check again")
                }
                throw error
            } catch (error: Throwable) {
                val status = if (error is SecurityException) RepositoryStatus.PERMISSION_REQUIRED else RepositoryStatus.FAILED
                dao.updateRepositoryClone(id, status, error.message ?: "Import failed")
                throw error
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
    fun removeRepository(repo: RepositoryEntity): Job {
        val job = cloneJobs[repo.id]
        return scope.launch(Dispatchers.IO) {
            startup.await()
            job?.cancelAndJoin()
            localRepositoryMutex.withLock { dao.deleteRepository(repo) }
        }
    }

    /**
     * Deletes the repository directory while retaining its record as missing.
     * Keeping the record lets users remove it from the list separately and makes
     * the destructive action explicit in the UI.
     */
    fun deleteRepositoryFiles(repo: RepositoryEntity): Job {
        val job = cloneJobs[repo.id]
        return scope.launch(Dispatchers.IO) {
            startup.await()
            try {
                job?.cancelAndJoin()
                localRepositoryMutex.withLock {
                    val current = dao.repository(repo.id) ?: return@withLock
                    if (current.localPath != repo.localPath || current.storageType != repo.storageType) return@withLock
                    require(current.storageType == RepositoryStorageType.APP_PRIVATE) { "Unsupported repository storage type" }
                    val root = repositoryRoot.canonicalFile
                    val selectedDirectory = File(current.localPath)
                    require(!Files.isSymbolicLink(selectedDirectory.toPath())) { "Repository directory is a symbolic link" }
                    val directory = selectedDirectory.canonicalFile
                    require(isSafeRepositoryPath(root, directory)) { "Repository path is outside the app repository root" }
                    if (directory.exists()) {
                        deleteRepositoryTree(directory)
                    }
                    dao.updateRepositoryClone(current.id, RepositoryStatus.MISSING, "Local files deleted")
                }
            } catch (error: CancellationException) {
                throw error
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
