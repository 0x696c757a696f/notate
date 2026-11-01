package app.notate.core.web

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleSmokeTest {
    @Test
    fun moduleNameMatches() {
        assertEquals("web", MODULE_NAME)
    }
}
