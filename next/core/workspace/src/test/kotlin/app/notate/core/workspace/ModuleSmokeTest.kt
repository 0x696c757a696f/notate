package app.notate.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleSmokeTest {
    @Test
    fun moduleNameMatches() {
        assertEquals("workspace", MODULE_NAME)
    }
}
