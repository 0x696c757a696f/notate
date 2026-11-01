package app.notate.core.testing

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleSmokeTest {
    @Test
    fun moduleNameMatches() {
        assertEquals("testing", MODULE_NAME)
    }
}
