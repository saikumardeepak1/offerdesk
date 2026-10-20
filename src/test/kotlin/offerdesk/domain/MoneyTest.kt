package offerdesk.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun `formats dollars with grouping and two decimals`() {
        assertEquals("$1,234.56", Money(123_456).toString())
        assertEquals("$0.05", Money(5).toString())
        assertEquals("-$12.00", Money(-1_200).toString())
    }

    @Test
    fun `arithmetic stays in whole cents`() {
        assertEquals(Money(350), Money(100) + Money(250))
        assertEquals(Money(-150), Money(100) - Money(250))
        assertEquals(Money(1_200), Money(300) * 4)
    }

    @Test
    fun `overflow is an error, never a silent wrap`() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) * 2 }
    }

    @Test
    fun `rounds fractional cents half even`() {
        assertEquals(Money(12), Money.of(BigDecimal("0.125")))
        assertEquals(Money(14), Money.of(BigDecimal("0.135")))
        assertEquals(Money(13), Money.of(BigDecimal("0.1251")))
        assertEquals(Money(-12), Money.of(BigDecimal("-0.125")))
    }

    @Test
    fun `split puts the leftover cents on the first part`() {
        assertEquals(listOf(Money(2_503), Money(2_500), Money(2_500), Money(2_500)), Money(10_003).split(4))
        assertEquals(listOf(Money(3_334), Money(3_333), Money(3_333)), Money(10_000).split(3))
    }

    @Test
    fun `split of an even amount is perfectly level`() {
        assertEquals(List(12) { Money(10_000) }, Money(120_000).split(12))
    }

    @Test
    fun `split always sums back to the original`() {
        val gen = SeededGen(11)
        repeat(5_000) {
            val amount = Money(gen.long(1, 10_000_000))
            val parts = gen.int(1, 60)
            assertEquals(amount, amount.split(parts).sum())
        }
    }

    @Test
    fun `split rejects non positive part counts`() {
        assertFailsWith<IllegalArgumentException> { Money(100).split(0) }
    }

    @Test
    fun `sum of an empty list is zero`() {
        assertEquals(Money.ZERO, emptyList<Money>().sum())
    }

    @Test
    fun `compares by cents`() {
        assertTrue(Money(99) < Money(100))
        assertEquals(Money(7), listOf(Money(9), Money(7), Money(8)).min())
    }
}
