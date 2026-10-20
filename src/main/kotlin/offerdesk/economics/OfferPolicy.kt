package offerdesk.economics

import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.domain.PlanKind
import offerdesk.domain.PlanTerms
import offerdesk.domain.Schedule

data class CatalogEntry(val terms: PlanTerms, val min: Money, val max: Money)

sealed interface Decision {
    val terms: PlanTerms

    data class Offered(val schedule: Schedule, val economics: UnitEconomics) : Decision {
        override val terms get() = schedule.terms
    }

    data class Hidden(override val terms: PlanTerms, val reason: String) : Decision
}

/**
 * Decides, plan by plan, what a shopper sees. A plan is shown only when it is eligible and its
 * expected profit reaches [profitFloor]; every hidden plan says why in plain numbers.
 */
class OfferPolicy(val catalog: List<CatalogEntry>, val profitFloor: Money) {
    fun evaluate(
        amount: Money,
        merchant: MerchantAgreement?,
        risk: RiskAssessment,
        costOfFunds: Bps,
    ): List<Decision> = catalog.map { entry -> decide(entry, amount, merchant, risk, costOfFunds) }

    private fun decide(
        entry: CatalogEntry,
        amount: Money,
        merchant: MerchantAgreement?,
        risk: RiskAssessment,
        costOfFunds: Bps,
    ): Decision {
        val terms = entry.terms
        fun hidden(why: String) = Decision.Hidden(terms, "${terms.label} hidden: $why")

        val fee = when {
            merchant == null && terms.isPromotional ->
                return hidden("merchant terms unavailable, so merchant funded plans are paused")
            merchant == null -> Bps.ZERO
            terms.kind in merchant.disabledKinds ->
                return hidden("${merchant.name} has turned off ${terms.kind.displayName}")
            else -> merchant.feeFor(terms)
                ?: return hidden("${merchant.name} does not fund a ${terms.installmentCount} month promotion")
        }
        if (amount < entry.min) return hidden("order $amount is below the ${entry.min} minimum")
        if (amount > entry.max) return hidden("order $amount is above the ${entry.max} limit")

        val schedule = Schedule.of(terms, amount)
        val economics = UnitEconomics.of(schedule, fee, risk, costOfFunds)
        return when {
            economics.revenue < economics.cost -> hidden(shortfall(economics))
            economics.expectedProfit < profitFloor ->
                hidden("expected profit ${economics.expectedProfit} is below the $profitFloor floor")
            else -> Decision.Offered(schedule, economics)
        }
    }

    private fun shortfall(e: UnitEconomics): String {
        val revenue = if (e.interest == Money.ZERO) {
            "merchant fee ${e.merchantFee}"
        } else {
            "merchant fee ${e.merchantFee} + interest ${e.interest}"
        }
        return "$revenue does not cover expected loss ${e.expectedLoss} + funding cost ${e.fundingCost}"
    }
}

val PlanKind.displayName: String
    get() = when (this) {
        PlanKind.PAY_IN_4 -> "Pay in 4"
        PlanKind.MONTHLY_0_APR -> "0% APR plans"
        PlanKind.MONTHLY_APR -> "monthly APR plans"
    }
