package offerdesk.domain

data class Installment(
    val number: Int,
    val duePeriod: Int,
    val amount: Money,
    val principal: Money,
    val interest: Money,
    val remainingPrincipal: Money,
)

data class Schedule(
    val terms: PlanTerms,
    val principal: Money,
    val installments: List<Installment>,
) {
    val total: Money get() = installments.map { it.amount }.sum()
    val totalInterest: Money get() = installments.map { it.interest }.sum()

    /** The amount the shopper sees as "per payment": what all but at most one installment charge. */
    val regularPayment: Money
        get() = installments.groupingBy { it.amount }.eachCount().maxBy { it.value }.key

    companion object {
        fun of(terms: PlanTerms, principal: Money): Schedule {
            require(principal > Money.ZERO) { "amount financed must be positive, was $principal" }
            val installments = when (terms) {
                is PlanTerms.MonthlyApr -> Amortization.installments(principal, terms)
                else -> evenSplit(principal, terms)
            }
            return Schedule(terms, principal, installments)
        }

        private fun evenSplit(principal: Money, terms: PlanTerms): List<Installment> {
            var remaining = principal
            return principal.split(terms.installmentCount).mapIndexed { index, amount ->
                remaining -= amount
                Installment(index + 1, terms.firstDuePeriod + index, amount, amount, Money.ZERO, remaining)
            }
        }
    }
}
