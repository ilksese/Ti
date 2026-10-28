package app.ti.ui

import app.ti.data.RepositoryEntity
import app.ti.data.RepositoryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoriesStatusTest {
    private fun repository(status: String, progress: String = "") = RepositoryEntity(
        id = "repo",
        name = "Repo",
        remoteUrl = null,
        localPath = "/data/repositories/repo",
        username = "",
        token = "",
        createdAt = 0,
        status = status,
        progress = progress,
    )

    @Test
    fun statusesHaveActionableLabels() {
        assertEquals("Creating: Initializing Git", repositoryStatusText(repository(RepositoryStatus.CREATING, "Initializing Git")))
        assertEquals("Permission required: Access revoked", repositoryStatusText(repository(RepositoryStatus.PERMISSION_REQUIRED, "Access revoked")))
        assertEquals("Invalid repository: Directory is not a Git repository", repositoryStatusText(repository(RepositoryStatus.INVALID, "Directory is not a Git repository")))
        assertEquals("Missing directory", repositoryStatusText(repository(RepositoryStatus.MISSING)))
    }

    @Test
    fun readyStatusIncludesRepositoryLocation() {
        assertEquals("Ready: /data/repositories/repo", repositoryStatusText(repository(RepositoryStatus.READY)))
    }
}
