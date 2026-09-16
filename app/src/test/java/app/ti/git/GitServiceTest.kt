package app.ti.git

import org.junit.Assert.assertEquals
import org.junit.Test

class GitServiceTest {
    @Test
    fun rewritesOnlyGithubUrls() {
        val github = "https://github.com/acme/project.git"

        assertEquals(github, gitProxyUrl(github, "github"))
        assertEquals("https://gh-proxy.com/$github", gitProxyUrl(github, "gh-proxy"))
        assertEquals("https://gitclone.com/github.com/acme/project.git", gitProxyUrl(github, "gitclone"))
        assertEquals("https://gitlab.com/acme/project.git", gitProxyUrl("https://gitlab.com/acme/project.git", "gh-proxy"))
    }
}
