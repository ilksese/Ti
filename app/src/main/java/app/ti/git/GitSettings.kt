package app.ti.git

import app.ti.data.RepositoryEntity
import app.ti.data.TiDao

suspend fun TiDao.withGlobalGitToken(repo: RepositoryEntity): RepositoryEntity {
    val token = setting("git_token")?.value ?: repo.token
    return repo.copy(token = token)
}

suspend fun TiDao.gitProxy(): String = setting("git_proxy")?.value ?: "github"
