package app.ti.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "repositories", indices = [Index(value = ["localPath"], unique = true)])
data class RepositoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Null for repositories that were created/imported locally. */
    val remoteUrl: String?,
    val localPath: String,
    val username: String,
    val token: String,
    val createdAt: Long,
    val status: String = "ready",
    val progress: String = "",
    /** Identifies whether this repository was cloned from a remote or created locally. */
    val source: String = RepositorySource.REMOTE,
    /** Identifies how the local path is persisted; URI storage is reserved for SAF support. */
    val storageType: String = RepositoryStorageType.APP_PRIVATE,
)

/** Persisted repository source values. Keep these stable for Room migrations. */
object RepositorySource {
    const val REMOTE = "remote"
    const val LOCAL = "local"
}

/** Persisted storage values. `SHARED_URI` is reserved for a future SAF integration. */
object RepositoryStorageType {
    const val APP_PRIVATE = "app_private"
    const val SHARED_URI = "shared_uri"
}

/** Persisted repository lifecycle values used by background creation and recovery. */
object RepositoryStatus {
    const val CREATING = "creating"
    const val CLONING = "cloning"
    const val READY = "ready"
    const val PERMISSION_REQUIRED = "permission_required"
    const val INVALID = "invalid_repository"
    const val MISSING = "directory_missing"
    const val FAILED = "failed"
}

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val headers: String,
    val createdAt: Long,
)

@Entity(
    tableName = "models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("providerId"), Index(value = ["providerId", "modelId"], unique = true)],
)
data class ModelEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val modelId: String,
    val contextTokens: Int = 131_072,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val discovered: Boolean = true,
    val reasoningMode: Boolean? = null,
    val reasoningLevels: String? = null,
    val maxInputTokens: Int? = null,
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val inputModalities: String? = null,
    val outputModalities: String? = null,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = RepositoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["repositoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ModelEntity::class,
            parentColumns = ["id"],
            childColumns = ["modelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("repositoryId"), Index("modelId")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val repositoryId: String?,
    val modelId: String,
    val title: String = "New session",
    val titleGenerated: Boolean = false,
    val status: String = "idle",
    val summary: String? = null,
    val summaryThroughAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("toolCallId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val status: String = "complete",
    val compacted: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface TiDao {
    @Query("SELECT * FROM repositories ORDER BY createdAt DESC")
    fun observeRepositories(): Flow<List<RepositoryEntity>>

    @Query("SELECT * FROM repositories ORDER BY createdAt DESC")
    suspend fun repositories(): List<RepositoryEntity>

    @Query("SELECT * FROM repositories WHERE id = :id")
    fun observeRepository(id: String): Flow<RepositoryEntity?>

    @Query("SELECT * FROM repositories WHERE id = :id")
    suspend fun repository(id: String): RepositoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun saveRepository(value: RepositoryEntity)

    @Delete
    suspend fun deleteRepository(value: RepositoryEntity)

    @Query("UPDATE repositories SET status = :status, progress = :progress WHERE id = :id")
    suspend fun updateRepositoryClone(id: String, status: String, progress: String)

    @Query("UPDATE repositories SET status = 'failed', progress = 'Clone interrupted' WHERE status = 'cloning'")
    suspend fun interruptCloningRepositories()

    @Query("SELECT * FROM providers ORDER BY name COLLATE NOCASE")
    fun observeProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY name COLLATE NOCASE")
    suspend fun providers(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    fun observeProvider(id: String): Flow<ProviderEntity?>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun provider(id: String): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProvider(value: ProviderEntity)

    @Delete
    suspend fun deleteProvider(value: ProviderEntity)

    @Query("SELECT * FROM models ORDER BY modelId COLLATE NOCASE")
    fun observeModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY modelId COLLATE NOCASE")
    fun observeModels(providerId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId")
    suspend fun models(providerId: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun model(id: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveModel(value: ModelEntity)

    @Delete
    suspend fun deleteModel(value: ModelEntity)

    @Query("UPDATE models SET discovered = 0 WHERE providerId = :providerId")
    suspend fun markModelsUndiscovered(providerId: String)

    @Query("SELECT * FROM sessions WHERE repositoryId = :repositoryId ORDER BY updatedAt DESC")
    fun observeSessions(repositoryId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE repositoryId IS NULL ORDER BY updatedAt DESC")
    fun observeChatSessions(): Flow<List<SessionEntity>>

    @Query(
        "SELECT models.* FROM models INNER JOIN providers ON providers.id = models.providerId " +
            "ORDER BY providers.name COLLATE NOCASE, models.modelId COLLATE NOCASE LIMIT 1",
    )
    suspend fun defaultModel(): ModelEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(value: SessionEntity)

    @Delete
    suspend fun deleteSession(value: SessionEntity)

    @Query("UPDATE sessions SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateSessionStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET title = :title, titleGenerated = 1, updatedAt = :now WHERE id = :id")
    suspend fun updateSessionTitle(id: String, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET summary = :summary, summaryThroughAt = :through, updatedAt = :now WHERE id = :id")
    suspend fun updateSessionSummary(
        id: String,
        summary: String,
        through: Long,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE sessions SET status = 'interrupted' WHERE status IN ('running', 'waiting')")
    suspend fun interruptActiveSessions()

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt, id")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt, id")
    suspend fun messages(sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMessage(value: MessageEntity)

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id")
    suspend fun finishTool(id: String, content: String, status: String)

    @Query("UPDATE messages SET compacted = 1 WHERE id IN (:ids)")
    suspend fun compactMessages(ids: List<String>)

    @Query("UPDATE messages SET status = 'interrupted' WHERE role = 'tool' AND status = 'pending'")
    suspend fun interruptPendingTools()

    @Query("SELECT * FROM settings WHERE `key` = :key")
    fun observeSetting(key: String): Flow<SettingEntity?>

    @Query("SELECT * FROM settings WHERE `key` = :key")
    suspend fun setting(key: String): SettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSetting(value: SettingEntity)
}

@Database(
    entities = [
        RepositoryEntity::class,
        ProviderEntity::class,
        ModelEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        SettingEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class TiDatabase : RoomDatabase() {
    abstract fun dao(): TiDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE repositories ADD COLUMN status TEXT NOT NULL DEFAULT 'ready'")
                db.execSQL("ALTER TABLE repositories ADD COLUMN progress TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN reasoningMode INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN reasoningLevels TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN maxInputTokens INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN inputPrice REAL")
                db.execSQL("ALTER TABLE models ADD COLUMN outputPrice REAL")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN inputModalities TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN outputModalities TEXT")
            }
        }

internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions_new` (`id` TEXT NOT NULL, `repositoryId` TEXT, " +
                        "`modelId` TEXT NOT NULL, `title` TEXT NOT NULL, `titleGenerated` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `summary` TEXT, `summaryThroughAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`repositoryId`) REFERENCES `repositories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`modelId`) REFERENCES `models`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages_new` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `name` TEXT, `toolCallId` TEXT, " +
                        "`toolCallsJson` TEXT, `status` TEXT NOT NULL, `compacted` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions_new`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO `sessions_new` (`id`, `repositoryId`, `modelId`, `title`, `titleGenerated`, " +
                        "`status`, `summary`, `summaryThroughAt`, `createdAt`, `updatedAt`) " +
                        "SELECT `id`, `repositoryId`, `modelId`, `title`, `titleGenerated`, `status`, `summary`, " +
                        "`summaryThroughAt`, `createdAt`, `updatedAt` FROM `sessions`",
                )
                db.execSQL(
                    "INSERT INTO `messages_new` (`id`, `sessionId`, `role`, `content`, `name`, `toolCallId`, `toolCallsJson`, " +
                        "`status`, `compacted`, `createdAt`) SELECT `id`, `sessionId`, `role`, `content`, `name`, `toolCallId`, " +
                        "`toolCallsJson`, `status`, `compacted`, `createdAt` FROM `messages`",
                )
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("DROP TABLE `sessions`")
                db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_repositoryId` ON `sessions` (`repositoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_modelId` ON `sessions` (`modelId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_toolCallId` ON `messages` (`toolCallId`)")
            }
        }

        /** Make the remote optional while retaining every existing repository row. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // `sessions` and `messages` both reference repositories (directly or
                // indirectly). Rebuilding only the parent table would violate SQLite's
                // foreign-key checks as soon as an existing session is present, so rebuild
                // the dependency chain together and then restore the original indexes.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `repositories_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`remoteUrl` TEXT, `localPath` TEXT NOT NULL, `username` TEXT NOT NULL, `token` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `progress` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions_new` (`id` TEXT NOT NULL, `repositoryId` TEXT, " +
                        "`modelId` TEXT NOT NULL, `title` TEXT NOT NULL, `titleGenerated` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `summary` TEXT, `summaryThroughAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`repositoryId`) REFERENCES `repositories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`modelId`) REFERENCES `models`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages_new` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `name` TEXT, `toolCallId` TEXT, " +
                        "`toolCallsJson` TEXT, `status` TEXT NOT NULL, `compacted` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO `repositories_new` (`id`, `name`, `remoteUrl`, `localPath`, `username`, `token`, `createdAt`, `status`, `progress`) " +
                        "SELECT `id`, `name`, `remoteUrl`, `localPath`, `username`, `token`, `createdAt`, `status`, `progress` FROM `repositories`",
                )
                db.execSQL(
                    "INSERT INTO `sessions_new` (`id`, `repositoryId`, `modelId`, `title`, `titleGenerated`, `status`, `summary`, " +
                        "`summaryThroughAt`, `createdAt`, `updatedAt`) SELECT `id`, `repositoryId`, `modelId`, `title`, " +
                        "`titleGenerated`, `status`, `summary`, `summaryThroughAt`, `createdAt`, `updatedAt` FROM `sessions`",
                )
                db.execSQL(
                    "INSERT INTO `messages_new` (`id`, `sessionId`, `role`, `content`, `name`, `toolCallId`, `toolCallsJson`, " +
                        "`status`, `compacted`, `createdAt`) SELECT `id`, `sessionId`, `role`, `content`, `name`, `toolCallId`, " +
                        "`toolCallsJson`, `status`, `compacted`, `createdAt` FROM `messages`",
                )
                // New tables reference the old names until the rename completes.
                // Temporarily disable checks while swapping the dependency chain.
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("DROP TABLE `sessions`")
                db.execSQL("DROP TABLE `repositories`")
                db.execSQL("ALTER TABLE `repositories_new` RENAME TO `repositories`")
                db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_repositoryId` ON `sessions` (`repositoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_modelId` ON `sessions` (`modelId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_toolCallId` ON `messages` (`toolCallId`)")
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE repositories ADD COLUMN source TEXT NOT NULL DEFAULT 'remote'")
                db.execSQL("ALTER TABLE repositories ADD COLUMN storageType TEXT NOT NULL DEFAULT 'app_private'")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_repositories_localPath` ON `repositories` (`localPath`)")
            }
        }

        fun create(context: Context): TiDatabase =
            Room.databaseBuilder(context, TiDatabase::class.java, "ti.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .build()
    }
}

