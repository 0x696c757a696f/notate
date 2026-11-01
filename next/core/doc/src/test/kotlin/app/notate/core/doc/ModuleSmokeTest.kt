package app.notate.core.doc

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleSmokeTest {
    @Test
    fun moduleNameMatches() {
        assertEquals("doc", MODULE_NAME)
    }
}
