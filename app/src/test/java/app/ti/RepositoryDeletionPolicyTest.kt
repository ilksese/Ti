package app.ti

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryDeletionPolicyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun allowsOnlyDirectChildOfRepositoryRoot() {
        val root = temp.newFolder("repositories").canonicalFile
        val repository = File(root, "project").canonicalFile

        assertTrue(isSafeRepositoryPath(root, repository))
        assertFalse(isSafeRepositoryPath(root, root))
        assertFalse(isSafeRepositoryPath(root, File(root, "project/src").canonicalFile))
    }

    @Test
    fun rejectsPathsOutsideRepositoryRoot() {
        val root = temp.newFolder("repositories").canonicalFile
        val outside = temp.newFolder("outside").canonicalFile

        assertFalse(isSafeRepositoryPath(root, outside))
    }
}
