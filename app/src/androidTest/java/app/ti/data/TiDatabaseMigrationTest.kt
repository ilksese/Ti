package app.ti.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TiDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "ti-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migratingFromVersion1To5ProducesValidSchemaAndKeepsData() {
        context.deleteDatabase(dbName)
        createVersion1Database()

        val database = Room.databaseBuilder(context, TiDatabase::class.java, dbName)
            .addMigrations(
                TiDatabase.MIGRATION_1_2,
                TiDatabase.MIGRATION_2_3,
                TiDatabase.MIGRATION_3_4,
                TiDatabase.MIGRATION_4_5,
            )
            .build()

        try {
            runBlocking(Dispatchers.IO) {
                database.openHelper.writableDatabase
                val dao = database.dao()

                assertEquals(listOf("r1"), dao.repositories().map { it.id })
                assertEquals("ready", dao.repository("r1")!!.status)
                assertEquals("seamaid", dao.provider("p1")!!.name)
                assertEquals(200_000, dao.model("m1")!!.contextTokens)
                assertNull(dao.model("m1")!!.reasoningMode)
                assertNull(dao.model("m1")!!.inputModalities)
                assertFalse(dao.session("s1")!!.titleGenerated)
                assertEquals("r1", dao.session("s1")!!.repositoryId)
                assertEquals(listOf("msg1"), dao.messages("s1").map { it.id })

                dao.saveSession(
                    SessionEntity(
                        id = "chat1",
                        repositoryId = null,
                        modelId = "m1",
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                )
                assertNull(dao.session("chat1")!!.repositoryId)
                assertEquals(listOf("chat1"), dao.observeChatSessions().first().map { it.id })
            }
        } finally {
            database.close()
        }
    }

    private fun createVersion1Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `repositories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`remoteUrl` TEXT NOT NULL, `localPath` TEXT NOT NULL, `username` TEXT NOT NULL, " +
                    "`token` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`baseUrl` TEXT NOT NULL, `apiKey` TEXT NOT NULL, `headers` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `models` (`id` TEXT NOT NULL, `providerId` TEXT NOT NULL, " +
                    "`modelId` TEXT NOT NULL, `contextTokens` INTEGER NOT NULL, `temperature` REAL, `topP` REAL, " +
                    "`maxTokens` INTEGER, `discovered` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`providerId`) REFERENCES `providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_models_providerId` ON `models` (`providerId`)")
            legacy.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_models_providerId_modelId` ON `models` (`providerId`, `modelId`)",
            )
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `sessions` (`id` TEXT NOT NULL, `repositoryId` TEXT NOT NULL, " +
                    "`modelId` TEXT NOT NULL, `title` TEXT NOT NULL, `titleGenerated` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, `summary` TEXT, `summaryThroughAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`repositoryId`) REFERENCES `repositories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`modelId`) REFERENCES `models`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_repositoryId` ON `sessions` (`repositoryId`)")
            legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_modelId` ON `sessions` (`modelId`)")
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `name` TEXT, `toolCallId` TEXT, " +
                    "`toolCallsJson` TEXT, `status` TEXT NOT NULL, `compacted` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
            legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_toolCallId` ON `messages` (`toolCallId`)")
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            )

            legacy.execSQL(
                "INSERT INTO repositories (id, name, remoteUrl, localPath, username, token, createdAt) " +
                    "VALUES ('r1', 'repo', 'https://github.com/acme/repo.git', '/tmp/r1', 'u', 't', 1)",
            )
            legacy.execSQL(
                "INSERT INTO providers (id, name, baseUrl, apiKey, headers, createdAt) " +
                    "VALUES ('p1', 'seamaid', 'https://api.test/v1', 'key', '', 1)",
            )
            legacy.execSQL(
                "INSERT INTO models (id, providerId, modelId, contextTokens, temperature, topP, maxTokens, discovered) " +
                    "VALUES ('m1', 'p1', 'gpt', 200000, NULL, NULL, NULL, 1)",
            )
            legacy.execSQL(
                "INSERT INTO sessions (id, repositoryId, modelId, title, titleGenerated, status, summary, " +
                    "summaryThroughAt, createdAt, updatedAt) VALUES ('s1', 'r1', 'm1', 'title', 0, 'idle', NULL, NULL, 1, 1)",
            )
            legacy.execSQL(
                "INSERT INTO messages (id, sessionId, role, content, name, toolCallId, toolCallsJson, status, " +
                    "compacted, createdAt) VALUES ('msg1', 's1', 'user', 'hi', NULL, NULL, NULL, 'complete', 0, 1)",
            )

            legacy.version = 1
        } finally {
            legacy.close()
        }
    }
}