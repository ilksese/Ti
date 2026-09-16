package app.ti.git

import app.ti.data.RepositoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

class GitService {
    suspend fun clone(
        url: String,
        directory: File,
        username: String,
        token: String,
        progress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val command = Git.cloneRepository()
            .setURI(url)
            .setDirectory(directory)
            .setProgressMonitor(TextProgressMonitor(progress) { !context.isActive })
        credentials(username, token)?.let(command::setCredentialsProvider)
        command.call().close()
    }

    suspend fun configureRemote(repo: RepositoryEntity, proxy: String) = withGit(repo) { git ->
        git.repository.config.apply {
            setString("remote", "origin", "url", gitProxyUrl(repo.remoteUrl, proxy))
            setString("remote", "origin", "pushurl", repo.remoteUrl)
            save()
        }
    }

    suspend fun status(repo: RepositoryEntity): String = withGit(repo) { git ->
        val status = git.status().call()
        buildList {
            status.added.forEach { add("A  $it") }
            status.changed.forEach { add("M  $it") }
            status.modified.forEach { add(" M $it") }
            status.missing.forEach { add(" D $it") }
            status.removed.forEach { add("D  $it") }
            status.untracked.forEach { add("?? $it") }
            status.conflicting.forEach { add("UU $it") }
        }.distinct().sorted().joinToString("\n").ifBlank { "Working tree clean" }
    }

    suspend fun diff(repo: RepositoryEntity, path: String? = null): String = withGit(repo) { git ->
        val output = ByteArrayOutputStream()
        val formatter = DiffFormatter(output).apply { setRepository(git.repository) }
        try {
            val filter = path?.takeIf { it.isNotBlank() }?.let(PathFilter::create)
            val head = git.repository.resolve("HEAD^{tree}")
            val reader = git.repository.newObjectReader()
            reader.use {
                val oldTree = org.eclipse.jgit.treewalk.CanonicalTreeParser().apply {
                    if (head != null) reset(reader, head)
                }
                val newTree = org.eclipse.jgit.dircache.DirCacheIterator(git.repository.readDirCache())
                formatter.scan(oldTree, newTree)
                    .filter { filter == null || it.newPath == path || it.oldPath == path }
                    .forEach(formatter::format)
                git.diff().setCached(false).apply { if (filter != null) setPathFilter(filter) }.call().forEach(formatter::format)
            }
            val untracked = git.status().call().untracked.filter { path == null || it == path }
            untracked.forEach { name ->
                val file = File(repo.localPath, name)
                val text = runCatching { file.readText().take(200_000) }.getOrElse { "[binary or unreadable]" }
                output.write("diff --git a/$name b/$name\n--- /dev/null\n+++ b/$name\n".toByteArray())
                text.lineSequence().forEach { output.write("+$it\n".toByteArray()) }
            }
            output.toString(Charsets.UTF_8.name()).ifBlank { "No diff" }
        } finally {
            formatter.close()
        }
    }

    suspend fun commit(repo: RepositoryEntity, message: String, authorName: String, authorEmail: String): String =
        withGit(repo) { git ->
            require(message.isNotBlank()) { "Commit message is required" }
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            val commit = git.commit()
                .setMessage(message)
                .setAuthor(authorName, authorEmail)
                .setCommitter(authorName, authorEmail)
                .call()
            commit.abbreviate(8).name()
        }

    suspend fun pull(repo: RepositoryEntity): String = withGit(repo) { git ->
        check(git.status().call().isClean) { "Pull requires a clean working tree" }
        val command = git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
        val fetchUrl = git.repository.config.getString("remote", "origin", "url").orEmpty()
        if (!isThirdPartyProxy(fetchUrl)) credentials(repo.username, repo.token)?.let(command::setCredentialsProvider)
        val result = command.call()
        check(result.isSuccessful) { "Pull failed: ${result.mergeResult ?: result.rebaseResult}" }
        result.mergeResult?.mergeStatus?.toString() ?: "Already up to date"
    }

    suspend fun push(repo: RepositoryEntity): String = withGit(repo) { git ->
        val branch = git.repository.branch
        val command = git.push()
            .setRemote("origin")
            .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
        credentials(repo.username, repo.token)?.let(command::setCredentialsProvider)
        val updates = command.call().flatMap { it.remoteUpdates }
        val failed = updates.filter { it.status !in setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE) }
        check(failed.isEmpty()) { failed.joinToString { "${it.remoteName}: ${it.status} ${it.message.orEmpty()}" } }
        git.repository.config.apply {
            setString("branch", branch, "remote", "origin")
            setString("branch", branch, "merge", "refs/heads/$branch")
            save()
        }
        updates.joinToString { "${it.remoteName}: ${it.status}" }.ifBlank { "Nothing to push" }
    }

    suspend fun branches(repo: RepositoryEntity): List<String> = withGit(repo) { git ->
        val current = git.repository.branch
        git.branchList().call().map { it.name.removePrefix("refs/heads/") }.sorted().map {
            if (it == current) "* $it" else "  $it"
        }
    }

    suspend fun createBranch(repo: RepositoryEntity, name: String): String = withGit(repo) { git ->
        git.branchCreate().setName(name).call().name.removePrefix("refs/heads/")
    }

    suspend fun switchBranch(repo: RepositoryEntity, name: String): String = withGit(repo) { git ->
        git.checkout().setName(name).call().name.removePrefix("refs/heads/")
    }

    suspend fun deleteBranch(repo: RepositoryEntity, name: String): String = withGit(repo) { git ->
        git.branchDelete().setBranchNames(name).setForce(false).call().joinToString()
    }

    private suspend fun <T> withGit(repo: RepositoryEntity, block: (Git) -> T): T = withContext(Dispatchers.IO) {
        Git.open(File(repo.localPath)).use(block)
    }

    private fun credentials(username: String, token: String) = token.takeIf { it.isNotBlank() }?.let {
        UsernamePasswordCredentialsProvider(username.ifBlank { "git" }, it)
    }

    private class TextProgressMonitor(
        private val update: (String) -> Unit,
        private val cancelled: () -> Boolean,
    ) : ProgressMonitor {
        override fun start(totalTasks: Int) = Unit
        override fun beginTask(title: String, totalWork: Int) = update(title)
        override fun update(completed: Int) = Unit
        override fun endTask() = Unit
        override fun isCancelled() = cancelled()
        override fun showDuration(enabled: Boolean) = Unit
    }
}

internal fun gitProxyUrl(url: String, proxy: String): String = runCatching {
    val uri = URI(url)
    if (!uri.host.equals("github.com", ignoreCase = true)) return url
    when (proxy) {
        "gh-proxy" -> "https://gh-proxy.com/$url"
        "gitclone" -> "https://gitclone.com/github.com${uri.rawPath}"
        else -> url
    }
}.getOrDefault(url)

private fun isThirdPartyProxy(url: String): Boolean = runCatching {
    URI(url).host.lowercase() in setOf("gh-proxy.com", "gitclone.com")
}.getOrDefault(false)
