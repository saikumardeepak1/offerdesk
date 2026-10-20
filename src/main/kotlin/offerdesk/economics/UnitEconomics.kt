package offerdesk.economics

import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.domain.Schedule
import java.math.BigDecimal
import java.math.MathContext

data class UnitEconomics(
    val merchantFee: Money,
    val interest: Money,
    val expectedLoss: Money,
    val fundingCost: Money,
) {
    val revenue: Money get() = merchantFee + interest
    val cost: Money get() = expectedLoss + fundingCost
    val expectedProfit: Money get() = revenue - cost

    companion object {
        private val math = MathContext.DECIMAL128

        fun of(schedule: Schedule, merchantFee: Bps, risk: RiskAssessment, costOfFunds: Bps) = UnitEconomics(
            merchantFee = merchantFee * schedule.principal,
            interest = schedule.totalInterest,
            expectedLoss = expectedLoss(schedule, risk),
            fundingCost = fundingCost(schedule, costOfFunds),
        )

        /** Principal x probability of default over the plan's life (capped at 1) x loss given default. */
        private fun expectedLoss(schedule: Schedule, risk: RiskAssessment): Money {
            val years = years(schedule.installments.last().duePeriod, schedule)
            val pd = (risk.annualDefault.toBigDecimal() * years).min(BigDecimal.ONE)
            return Money.of(schedule.principal.toBigDecimal() * pd * risk.lossGivenDefault.toBigDecimal())
        }

        /** Cost of funds on the principal actually outstanding during each period. */
        private fun fundingCost(schedule: Schedule, costOfFunds: Bps): Money {
            var outstanding = schedule.principal
            var lastDue = 0
            var balanceYears = BigDecimal.ZERO
            for (installment in schedule.installments) {
                val elapsed = years(installment.duePeriod - lastDue, schedule)
                balanceYears += outstanding.toBigDecimal() * elapsed
                outstanding = installment.remainingPrincipal
                lastDue = installment.duePeriod
            }
            return Money.of(balanceYears * costOfFunds.toBigDecimal())
        }

        private fun years(periods: Int, schedule: Schedule): BigDecimal =
            BigDecimal.valueOf(periods.toLong()).divide(BigDecimal.valueOf(schedule.terms.periodsPerYear.toLong()), math)
    }
}
