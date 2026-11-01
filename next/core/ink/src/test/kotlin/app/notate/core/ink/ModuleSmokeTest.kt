package app.notate.core.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleSmokeTest {
    @Test
    fun moduleNameMatches() {
        assertEquals("ink", MODULE_NAME)
    }
}
