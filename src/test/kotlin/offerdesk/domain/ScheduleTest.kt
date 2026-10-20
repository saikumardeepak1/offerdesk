package offerdesk.domain

import offerdesk.domain.PlanTerms.Monthly0Apr
import offerdesk.domain.PlanTerms.MonthlyApr
import offerdesk.domain.PlanTerms.PayIn4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScheduleTest {
    @Test
    fun `pay in 4 is four biweekly payments starting at checkout`() {
        val schedule = Schedule.of(PayIn4, Money(10_003))
        assertEquals(listOf(Money(2_503), Money(2_500), Money(2_500), Money(2_500)), schedule.amounts())
        assertEquals(listOf(0, 1, 2, 3), schedule.installments.map { it.duePeriod })
        assertEquals(Money(2_500), schedule.regularPayment)
    }

    @Test
    fun `zero percent plans total exactly the principal`() {
        val schedule = Schedule.of(Monthly0Apr(12), Money(120_000))
        assertEquals(Money(120_000), schedule.total)
        assertEquals(Money.ZERO, schedule.totalInterest)
        assertEquals(List(12) { Money(10_000) }, schedule.amounts())
        assertEquals((1..12).toList(), schedule.installments.map { it.duePeriod })
    }

    @Test
    fun `leftover cents land on the first monthly payment`() {
        val schedule = Schedule.of(Monthly0Apr(6), Money(100_000))
        assertEquals(Money(16_670), schedule.installments.first().amount)
        assertEquals(List(5) { Money(16_666) }, schedule.amounts().drop(1))
        assertEquals(Money(16_666), schedule.regularPayment)
    }

    @Test
    fun `hand computed amortization for 1000 dollars at 12 percent over 12 months`() {
        val schedule = Schedule.of(MonthlyApr(12, Bps(1_200)), Money(100_000))
        assertEquals(Money(8_885), schedule.regularPayment)
        assertEquals(List(11) { Money(8_885) } + Money(8_884), schedule.amounts())
        assertEquals(Money(106_619), schedule.total)
        assertEquals(Money(6_619), schedule.totalInterest)
        val interest = schedule.installments.map { it.interest.cents }
        assertEquals(listOf(1_000L, 921, 842, 761, 680, 598, 515, 431, 347, 261, 175, 88), interest)
    }

    @Test
    fun `first month interest is one month of simple interest on the full principal`() {
        val schedule = Schedule.of(MonthlyApr(24, Bps(1_599)), Money(50_000))
        assertEquals(Money(666), schedule.installments.first().interest)
        assertEquals(Money(2_448), schedule.regularPayment)
    }

    @Test
    fun `every apr payment but the payoff matches the advertised monthly payment`() {
        val schedule = Schedule.of(MonthlyApr(24, Bps(1_999)), Money(345_678))
        assertEquals(23, schedule.amounts().count { it == schedule.regularPayment })
        assertEquals(schedule.regularPayment, schedule.installments.first().amount)
    }

    @Test
    fun `principal portions repay exactly the amount financed`() {
        val schedule = Schedule.of(MonthlyApr(18, Bps(2_999)), Money(87_654))
        assertEquals(Money(87_654), schedule.installments.map { it.principal }.sum())
        assertEquals(schedule.total, schedule.principal + schedule.totalInterest)
    }

    @Test
    fun `remaining principal after the last payment is zero`() {
        val schedule = Schedule.of(MonthlyApr(12, Bps(1_599)), Money(99_999))
        assertEquals(Money.ZERO, schedule.installments.last().remainingPrincipal)
    }

    @Test
    fun `rejects a non positive amount financed`() {
        assertFailsWith<IllegalArgumentException> { Schedule.of(PayIn4, Money.ZERO) }
    }

    @Test
    fun `plan terms validate their own shape`() {
        assertFailsWith<IllegalArgumentException> { Monthly0Apr(0) }
        assertFailsWith<IllegalArgumentException> { MonthlyApr(12, Bps(0)) }
    }

    @Test
    fun `plans describe themselves in shopper language`() {
        assertEquals("Pay in 4", PayIn4.label)
        assertEquals("0% for 12 months", Monthly0Apr(12).label)
        assertEquals("15.99% APR for 24 months", MonthlyApr(24, Bps(1_599)).label)
        assertEquals("apr-24m", MonthlyApr(24, Bps(1_599)).id)
    }

    @Test
    fun `promotional means the shopper pays no interest`() {
        assertEquals(true, PayIn4.isPromotional)
        assertEquals(true, Monthly0Apr(6).isPromotional)
        assertEquals(false, MonthlyApr(6, Bps(999)).isPromotional)
    }

    private fun Schedule.amounts() = installments.map { it.amount }
}
