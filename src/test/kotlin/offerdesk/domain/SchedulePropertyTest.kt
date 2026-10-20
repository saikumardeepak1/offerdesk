package offerdesk.domain

import offerdesk.domain.PlanTerms.Monthly0Apr
import offerdesk.domain.PlanTerms.MonthlyApr
import offerdesk.domain.PlanTerms.PayIn4
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulePropertyTest {
    private val cases = 2_000
    private val terms = listOf(3, 6, 12, 18, 24, 36)

    private fun forEachPlan(seed: Long, check: (PlanTerms, Money) -> Unit) {
        val gen = SeededGen(seed)
        repeat(cases) {
            val principal = Money(gen.long(5_000, 3_000_000))
            val months = gen.pick(terms)
            val plan = when (gen.int(0, 2)) {
                0 -> PayIn4
                1 -> Monthly0Apr(months)
                else -> MonthlyApr(months, Bps(gen.int(1, 3_600)))
            }
            check(plan, principal)
        }
    }

    @Test
    fun `installments sum exactly to the stated total`() = forEachPlan(1) { plan, principal ->
        val schedule = Schedule.of(plan, principal)
        assertEquals(schedule.total, schedule.installments.map { it.amount }.sum(), "$plan $principal")
        assertEquals(plan.installmentCount, schedule.installments.size)
    }

    @Test
    fun `total is principal plus interest and nothing else`() = forEachPlan(2) { plan, principal ->
        val schedule = Schedule.of(plan, principal)
        assertEquals(principal, schedule.installments.map { it.principal }.sum(), "$plan $principal")
        assertEquals(principal + schedule.totalInterest, schedule.total)
    }

    @Test
    fun `zero percent plans never charge interest`() = forEachPlan(3) { plan, principal ->
        if (plan.isPromotional) {
            val schedule = Schedule.of(plan, principal)
            assertEquals(principal, schedule.total, "$plan $principal")
            assertTrue(schedule.installments.all { it.interest == Money.ZERO })
        }
    }

    @Test
    fun `interest is simple interest on the remaining principal only`() = forEachPlan(4) { plan, principal ->
        if (plan is MonthlyApr) {
            var remaining = principal
            for (installment in Schedule.of(plan, principal).installments) {
                assertEquals(monthlyInterest(remaining, plan.apr), installment.interest, "$plan $principal")
                remaining -= installment.principal
                assertEquals(remaining, installment.remainingPrincipal)
            }
        }
    }

    @Test
    fun `remaining principal strictly declines to zero`() = forEachPlan(5) { plan, principal ->
        val balances = Schedule.of(plan, principal).installments.map { it.remainingPrincipal }
        assertTrue(balances.zipWithNext().all { (a, b) -> b < a }, "$plan $principal $balances")
        assertEquals(Money.ZERO, balances.last())
    }

    @Test
    fun `total interest stays below flat interest on the full principal`() = forEachPlan(6) { plan, principal ->
        if (plan is MonthlyApr) {
            val interest = Schedule.of(plan, principal).totalInterest
            val flatTimes120k = principal.cents * plan.apr.value * plan.installmentCount
            assertTrue(interest.cents * 120_000 <= flatTimes120k, "$plan $principal interest=$interest")
        }
    }

    @Test
    fun `only one installment differs from the advertised payment`() = forEachPlan(7) { plan, principal ->
        val schedule = Schedule.of(plan, principal)
        val odd = schedule.installments.filter { it.amount != schedule.regularPayment }
        assertTrue(odd.size <= 1, "$plan $principal")
        odd.forEach {
            val gap = (it.amount - schedule.regularPayment).cents
            if (plan.isPromotional) {
                assertEquals(1, it.number, "leftover cents go first: $plan $principal")
                assertTrue(gap in 1 until plan.installmentCount)
            } else {
                assertEquals(plan.installmentCount, it.number, "payoff goes last: $plan $principal")
                assertTrue(abs(gap) < plan.installmentCount, "$plan $principal gap=$gap")
            }
        }
    }

    private fun monthlyInterest(balance: Money, apr: Bps): Money = Money(
        BigDecimal.valueOf(balance.cents * apr.value)
            .divide(BigDecimal.valueOf(120_000), 0, RoundingMode.HALF_EVEN)
            .longValueExact(),
    )
}
