package app.ti.agent

import app.ti.data.MessageEntity
import app.ti.data.ModelEntity
import app.ti.data.ProviderEntity
import app.ti.data.RepositoryEntity
import app.ti.data.SessionEntity
import app.ti.data.SettingEntity
import app.ti.data.TiDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeTiDao : TiDao {
    private val repositoryMap = MutableStateFlow<Map<String, RepositoryEntity>>(emptyMap())
    private val providerMap = MutableStateFlow<Map<String, ProviderEntity>>(emptyMap())
    private val modelMap = MutableStateFlow<Map<String, ModelEntity>>(emptyMap())
    private val sessionMap = MutableStateFlow<Map<String, SessionEntity>>(emptyMap())
    private val messageMap = MutableStateFlow<Map<String, MessageEntity>>(emptyMap())
    private val settingMap = MutableStateFlow<Map<String, String>>(emptyMap())

    val sessions: Map<String, SessionEntity> get() = sessionMap.value
    val messagesById: Map<String, MessageEntity> get() = messageMap.value

    fun seedRepository(value: RepositoryEntity) {
        repositoryMap.value += value.id to value
    }

    fun seedProvider(value: ProviderEntity) {
        providerMap.value += value.id to value
    }

    fun seedModel(value: ModelEntity) {
        modelMap.value += value.id to value
    }

    fun seedSession(value: SessionEntity) {
        sessionMap.value += value.id to value
    }

    fun seedMessage(value: MessageEntity) {
        messageMap.value += value.id to value
    }

    fun seedSetting(key: String, value: String) {
        settingMap.value += key to value
    }

    fun messageList(sessionId: String): List<MessageEntity> = ordered(sessionId)

    private fun ordered(sessionId: String) = messageMap.value.values
        .filter { it.sessionId == sessionId }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override fun observeRepositories(): Flow<List<RepositoryEntity>> =
        repositoryMap.map { it.values.sortedByDescending(RepositoryEntity::createdAt) }

    override suspend fun repositories(): List<RepositoryEntity> =
        repositoryMap.value.values.sortedByDescending(RepositoryEntity::createdAt)

    override fun observeRepository(id: String): Flow<RepositoryEntity?> = repositoryMap.map { it[id] }

    override suspend fun repository(id: String): RepositoryEntity? = repositoryMap.value[id]

    override suspend fun saveRepository(value: RepositoryEntity) {
        repositoryMap.value += value.id to value
    }

    override suspend fun deleteRepository(value: RepositoryEntity) {
        repositoryMap.value -= value.id
    }

    override suspend fun updateRepositoryClone(id: String, status: String, progress: String) {
        repositoryMap.value[id]?.let { repositoryMap.value += id to it.copy(status = status, progress = progress) }
    }

    override suspend fun interruptCloningRepositories() {
        repositoryMap.value = repositoryMap.value.mapValues { (_, repo) ->
            if (repo.status == "cloning") repo.copy(status = "failed", progress = "Clone interrupted") else repo
        }
    }

    override fun observeProviders(): Flow<List<ProviderEntity>> =
        providerMap.map { it.values.sortedBy { p -> p.name.lowercase() } }

    override suspend fun providers(): List<ProviderEntity> =
        providerMap.value.values.sortedBy { it.name.lowercase() }

    override fun observeProvider(id: String): Flow<ProviderEntity?> = providerMap.map { it[id] }

    override suspend fun provider(id: String): ProviderEntity? = providerMap.value[id]

    override suspend fun saveProvider(value: ProviderEntity) {
        providerMap.value += value.id to value
    }

    override suspend fun deleteProvider(value: ProviderEntity) {
        providerMap.value -= value.id
    }

    override fun observeModels(): Flow<List<ModelEntity>> =
        modelMap.map { it.values.sortedBy { m -> m.modelId.lowercase() } }

    override fun observeModels(providerId: String): Flow<List<ModelEntity>> =
        modelMap.map { it.values.filter { m -> m.providerId == providerId }.sortedBy { m -> m.modelId.lowercase() } }

    override suspend fun models(providerId: String): List<ModelEntity> = modelMap.value.values
        .filter { it.providerId == providerId }

    override suspend fun model(id: String): ModelEntity? = modelMap.value[id]

    override suspend fun saveModel(value: ModelEntity) {
        modelMap.value += value.id to value
    }

    override suspend fun deleteModel(value: ModelEntity) {
        modelMap.value -= value.id
    }

    override suspend fun markModelsUndiscovered(providerId: String) {
        modelMap.value = modelMap.value.mapValues { (_, model) ->
            if (model.providerId == providerId) model.copy(discovered = false) else model
        }
    }

    override fun observeSessions(repositoryId: String): Flow<List<SessionEntity>> =
        sessionMap.map { it.values.filter { s -> s.repositoryId == repositoryId }.sortedByDescending { s -> s.updatedAt } }

    override fun observeChatSessions(): Flow<List<SessionEntity>> =
        sessionMap.map { it.values.filter { s -> s.repositoryId == null }.sortedByDescending { s -> s.updatedAt } }

    override suspend fun defaultModel(): ModelEntity? {
        val provider = providerMap.value.values.sortedBy { it.name.lowercase() }.firstOrNull() ?: return null
        return modelMap.value.values.filter { it.providerId == provider.id }.sortedBy { it.modelId.lowercase() }.firstOrNull()
    }

    override fun observeSession(id: String): Flow<SessionEntity?> = sessionMap.map { it[id] }

    override suspend fun session(id: String): SessionEntity? = sessionMap.value[id]

    override suspend fun saveSession(value: SessionEntity) {
        sessionMap.value += value.id to value
    }

    override suspend fun deleteSession(value: SessionEntity) {
        sessionMap.value -= value.id
    }

    override suspend fun updateSessionStatus(id: String, status: String, now: Long) {
        sessionMap.value[id]?.let { sessionMap.value += id to it.copy(status = status, updatedAt = now) }
    }

    override suspend fun updateSessionTitle(id: String, title: String, now: Long) {
        sessionMap.value[id]?.let {
            sessionMap.value += id to it.copy(title = title, titleGenerated = true, updatedAt = now)
        }
    }

    override suspend fun updateSessionSummary(id: String, summary: String, through: Long, now: Long) {
        sessionMap.value[id]?.let {
            sessionMap.value += id to it.copy(summary = summary, summaryThroughAt = through, updatedAt = now)
        }
    }

    override suspend fun interruptActiveSessions() {
        sessionMap.value = sessionMap.value.mapValues { (_, session) ->
            if (session.status in setOf("running", "waiting")) session.copy(status = "interrupted") else session
        }
    }

    override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        messageMap.map { ordered(sessionId) }

    override suspend fun messages(sessionId: String): List<MessageEntity> = ordered(sessionId)

    override suspend fun saveMessage(value: MessageEntity) {
        messageMap.value += value.id to value
    }

    override suspend fun finishTool(id: String, content: String, status: String) {
        messageMap.value[id]?.let { messageMap.value += id to it.copy(content = content, status = status) }
    }

    override suspend fun compactMessages(ids: List<String>) {
        val targets = ids.toSet()
        messageMap.value = messageMap.value.mapValues { (_, message) ->
            if (message.id in targets) message.copy(compacted = true) else message
        }
    }

    override suspend fun interruptPendingTools() {
        messageMap.value = messageMap.value.mapValues { (_, message) ->
            if (message.role == "tool" && message.status == "pending") message.copy(status = "interrupted") else message
        }
    }

    override fun observeSetting(key: String): Flow<SettingEntity?> =
        settingMap.map { values -> values[key]?.let { SettingEntity(key, it) } }

    override suspend fun setting(key: String): SettingEntity? =
        settingMap.value[key]?.let { SettingEntity(key, it) }

    override suspend fun saveSetting(value: SettingEntity) {
        settingMap.value += value.key to value.value
    }
}