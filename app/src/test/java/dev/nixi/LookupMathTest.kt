package dev.nixi

import dev.nixi.tools.LookupTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LookupMathTest {

    @Test
    fun `proste dzialania`() {
        assertEquals("12.0 * 8.0 = 96", LookupTools.tryMath("12*8"))
        assertEquals("2.0 + 2.0 = 4", LookupTools.tryMath("ile to 2+2"))
        assertNotNull(LookupTools.tryMath("10 / 4"))
        assertNull(LookupTools.tryMath("pogoda Katowice"))
    }
}
