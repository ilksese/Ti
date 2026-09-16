package app.ti

import android.content.Context
import app.ti.agent.AgentRunManager
import app.ti.agent.RepoFiles
import app.ti.data.RepositoryEntity
import app.ti.data.TiDatabase
import app.ti.git.GitService
import app.ti.git.gitProxy
import app.ti.git.gitProxyUrl
import app.ti.llm.OpenAiClient
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cloneJobs = ConcurrentHashMap<String, Job>()
    val agents = AgentRunManager(dao, llm, files, git, scope)

    init {
        scope.launch(Dispatchers.IO) {
            dao.interruptActiveSessions()
            dao.interruptPendingTools()
            dao.interruptCloningRepositories()
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

    fun deleteRepository(repo: RepositoryEntity) {
        val cloneJob = cloneJobs.remove(repo.id)
        scope.launch {
            dao.deleteRepository(repo)
            cloneJob?.cancelAndJoin()
            withContext(Dispatchers.IO) { File(repo.localPath).deleteRecursively() }
        }
    }
}
