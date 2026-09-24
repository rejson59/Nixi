package dev.nixi

import dev.nixi.tools.PhoneTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneParseTest {

    @Test
    fun `procent z i bez znaku`() {
        assertEquals(50, PhoneTools.parsePercent("50"))
        assertEquals(50, PhoneTools.parsePercent("50%"))
        assertEquals(0, PhoneTools.parsePercent("0"))
        assertEquals(100, PhoneTools.parsePercent("150"))
        assertNull(PhoneTools.parsePercent("głośniej"))
    }

    @Test
    fun `minutnik z minut i mm_ss`() {
        assertEquals(5 * 60, PhoneTools.parseSeconds("5 min"))
        assertEquals(90, PhoneTools.parseSeconds("1:30"))
        assertEquals(5 * 60, PhoneTools.parseSeconds("5"))
        assertEquals(2 * 3600, PhoneTools.parseSeconds("2 godziny"))
        assertNull(PhoneTools.parseSeconds("nic"))
    }
}
