package app.ti

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRepositoryNameTest {
    @Test
    fun acceptsSimpleNamesAndTrimsWhitespace() {
        assertTrue(validLocalRepositoryName("demo"))
        assertTrue(validLocalRepositoryName("  demo-project  "))
    }

    @Test
    fun rejectsUnsafeOrEmptyNames() {
        assertFalse(validLocalRepositoryName(""))
        assertFalse(validLocalRepositoryName("   "))
        assertFalse(validLocalRepositoryName("."))
        assertFalse(validLocalRepositoryName(".."))
        assertFalse(validLocalRepositoryName("../outside"))
        assertFalse(validLocalRepositoryName("nested/name"))
        assertFalse(validLocalRepositoryName("nested\\name"))
        assertFalse(validLocalRepositoryName("x".repeat(65)))
    }
}
