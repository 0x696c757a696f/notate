package app.notate.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleSmokeTest {
    @Test
    fun moduleNameMatches() {
        assertEquals("model", MODULE_NAME)
    }
}
