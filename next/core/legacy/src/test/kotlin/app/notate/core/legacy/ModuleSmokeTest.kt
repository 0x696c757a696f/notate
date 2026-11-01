package app.notate.core.legacy

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleSmokeTest {
    @Test
    fun moduleNameMatches() {
        assertEquals("legacy", MODULE_NAME)
    }
}
