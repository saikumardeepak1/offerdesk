package offerdesk.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BpsTest {
    @Test
    fun `formats as a percentage`() {
        assertEquals("15.99%", Bps(1_599).toString())
        assertEquals("0.00%", Bps(0).toString())
        assertEquals("5.50%", Bps(550).toString())
    }

    @Test
    fun `applies to money with half even rounding`() {
        assertEquals(Money(3_360), Bps(280) * Money(120_000))
        assertEquals(Money(2), Bps(250) * Money(100))
        assertEquals(Money(4), Bps(350) * Money(100))
    }

    @Test
    fun `rejects negative rates`() {
        assertFailsWith<IllegalArgumentException> { Bps(-1) }
    }
}
