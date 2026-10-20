package offerdesk.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Simple interest on declining principal. Each month's interest is the monthly rate times the
 * principal still owed, rounded half even to the cent, so interest is never charged on interest.
 */
internal object Amortization {
    private val math = MathContext.DECIMAL128

    fun installments(principal: Money, terms: PlanTerms.MonthlyApr): List<Installment> {
        val payment = levelPayment(principal, terms.apr, terms.months)
        var remaining = principal
        return (1..terms.months).map { number ->
            val interest = monthlyInterest(remaining, terms.apr)
            val amount = if (number < terms.months) payment else remaining + interest
            val principalPart = amount - interest
            check(principalPart > Money.ZERO) { "payment $payment does not reduce principal $remaining" }
            remaining -= principalPart
            Installment(number, number, amount, principalPart, interest, remaining)
        }
    }

    fun monthlyInterest(balance: Money, apr: Bps): Money = Money(
        BigDecimal.valueOf(balance.cents)
            .multiply(BigDecimal.valueOf(apr.value.toLong()))
            .divide(BigDecimal.valueOf(12 * 10_000L), 0, RoundingMode.HALF_EVEN)
            .longValueExact(),
    )

    /** Standard formula: P * r / (1 - (1 + r)^-n), rounded half even to the cent. */
    fun levelPayment(principal: Money, apr: Bps, months: Int): Money {
        val rate = apr.toBigDecimal().divide(BigDecimal.valueOf(12), math)
        val growth = BigDecimal.ONE.add(rate).pow(months, math)
        val payment = principal.toBigDecimal().multiply(rate).multiply(growth)
            .divide(growth.subtract(BigDecimal.ONE), math)
        return Money.of(payment)
    }
}
