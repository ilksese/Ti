package app.ti.testing

import android.content.Context
import androidx.room.Room
import app.ti.AppContainer
import app.ti.data.TiDatabase
import app.ti.git.GitService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Owns an isolated disk database and repository root; never opens the user's ti.db. */
class RepositoryTestEnvironment(private val context: Context) {
    private val id = UUID.randomUUID().toString()
    val directory = File(context.cacheDir, "repository-test-$id").apply { mkdirs() }
    val root = File(directory, "repositories").apply { mkdirs() }
    val databaseName = "repository-test-$id.db"
    private var database: TiDatabase? = null
    private var container: AppContainer? = null

    fun openDatabase(): TiDatabase {
        check(database == null) { "Close the previous database before reopening it" }
        return Room.databaseBuilder(context, TiDatabase::class.java, databaseName)
            .build()
            .also { database = it }
    }

    fun openContainer(
        git: GitService = GitService(),
        initializeNewRepository: suspend (File) -> Unit = git::init,
        initializeExistingRepository: suspend (File) -> Unit = git::initialize,
    ): AppContainer {
        check(container == null) { "Close the previous container before reopening it" }
        return AppContainer(
            context = context,
            database = database ?: openDatabase(),
            repositoryRoot = root,
            git = git,
            initializeNewRepository = initializeNewRepository,
            initializeExistingRepository = initializeExistingRepository,
        ).also { container = it }
    }

    suspend fun closeContainer() {
        val current = container
        if (current != null) current.close() else withContext(Dispatchers.IO) { database?.close() }
        container = null
        database = null
    }

    suspend fun close() {
        closeContainer()
        withContext(Dispatchers.IO) {
            context.deleteDatabase(databaseName)
            directory.deleteRecursively()
        }
    }
}
