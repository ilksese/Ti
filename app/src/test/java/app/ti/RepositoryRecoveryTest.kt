package app.ti

import app.ti.data.RepositoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositoryRecoveryTest {
    @Test
    fun interruptedCreationWithGitRepositoryBecomesReady() {
        val result = repositoryRecoveryResult(
            previousStatus = RepositoryStatus.CREATING,
            exists = true,
            isDirectory = true,
            isGitRepository = true,
        )

        assertEquals(RepositoryStatus.READY, result?.status)
        assertEquals("", result?.progress)
    }

    @Test
    fun interruptedCloneWithoutGitRepositoryBecomesRetryableFailure() {
        val result = repositoryRecoveryResult(
            previousStatus = RepositoryStatus.CLONING,
            exists = true,
            isDirectory = true,
            isGitRepository = false,
        )

        assertEquals(RepositoryStatus.FAILED, result?.status)
        assertEquals("Operation interrupted; tap Retry to check again", result?.progress)
    }

    @Test
    fun missingDirectoryIsReportedBeforeGitInspection() {
        val result = repositoryRecoveryResult(
            previousStatus = RepositoryStatus.READY,
            exists = false,
            isDirectory = false,
            isGitRepository = false,
        )

        assertEquals(RepositoryStatus.MISSING, result?.status)
        assertEquals("Directory is missing", result?.progress)
    }

    @Test
    fun readyNonGitDirectoryBecomesInvalid() {
        val result = repositoryRecoveryResult(
            previousStatus = RepositoryStatus.READY,
            exists = true,
            isDirectory = true,
            isGitRepository = false,
        )

        assertEquals(RepositoryStatus.INVALID, result?.status)
        assertEquals("Directory is not a Git repository", result?.progress)
    }

    @Test
    fun readyGitRepositoryDoesNotWriteAStateChange() {
        val result = repositoryRecoveryResult(
            previousStatus = RepositoryStatus.READY,
            exists = true,
            isDirectory = true,
            isGitRepository = true,
        )

        assertNull(result)
    }

    @Test
    fun inaccessibleDirectoryRequiresPermission() {
        val result = repositoryRecoveryResult(
            previousStatus = RepositoryStatus.READY,
            exists = true,
            isDirectory = true,
            isGitRepository = false,
            permissionDenied = true,
        )

        assertEquals(RepositoryStatus.PERMISSION_REQUIRED, result?.status)
        assertEquals("Permission required", result?.progress)
    }
}
