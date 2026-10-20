package offerdesk.economics

import offerdesk.domain.Bps
import offerdesk.domain.PlanKind
import offerdesk.domain.PlanTerms

/** What a merchant agreed to pay per order: a standard fee, plus richer fees for 0% terms it funds. */
data class MerchantAgreement(
    val merchantId: String,
    val name: String,
    val standardFee: Bps,
    val promoFees: Map<Int, Bps>,
    val disabledKinds: Set<PlanKind> = emptySet(),
) {
    fun feeFor(terms: PlanTerms): Bps? = when (terms) {
        is PlanTerms.Monthly0Apr -> promoFees[terms.months]
        else -> standardFee
    }
}

data class RiskAssessment(
    val annualDefault: Bps,
    val lossGivenDefault: Bps,
    val tier: String,
)
