package offerdesk.http

import kotlinx.serialization.Serializable
import offerdesk.domain.Installment
import offerdesk.domain.PlanTerms
import offerdesk.economics.Decision
import offerdesk.service.Degradation
import offerdesk.service.Quote

@Serializable
data class QuoteRequestDto(
    val merchantId: String? = null,
    val shopperId: String? = null,
    val amountCents: Long? = null,
    val currency: String? = null,
)

@Serializable
data class QuoteResponse(
    val quoteId: String,
    val merchantId: String,
    val shopperId: String,
    val amountCents: Long,
    val currency: String,
    val offered: List<OfferedPlan>,
    val hidden: List<HiddenPlan>,
    val degraded: List<DegradedDependency>,
    val fundingRateStale: Boolean,
)

@Serializable
data class OfferedPlan(
    val planId: String,
    val kind: String,
    val label: String,
    val apr: String,
    val installmentCount: Int,
    val paymentCents: Long,
    val totalCents: Long,
    val interestCents: Long,
    val summary: String,
    val schedule: List<InstallmentDto>,
)

@Serializable
data class InstallmentDto(
    val number: Int,
    val due: String,
    val amountCents: Long,
    val principalCents: Long,
    val interestCents: Long,
)

@Serializable
data class HiddenPlan(val planId: String, val kind: String, val label: String, val reason: String)

@Serializable
data class DegradedDependency(val dependency: String, val cause: String, val fallback: String)

@Serializable
data class ErrorResponse(val error: String, val details: List<String> = emptyList())

@Serializable
data class Health(val status: String)

fun Quote.toResponse() = QuoteResponse(
    quoteId = quoteId,
    merchantId = request.merchantId,
    shopperId = request.shopperId,
    amountCents = request.amount.cents,
    currency = "USD",
    offered = offered.map { it.toDto() },
    hidden = hidden.map { HiddenPlan(it.terms.id, it.terms.kind.name, it.terms.label, it.reason) },
    degraded = degraded.map { it.toDto() },
    fundingRateStale = fundingRateStale,
)

private fun Decision.Offered.toDto(): OfferedPlan {
    val terms = schedule.terms
    return OfferedPlan(
        planId = terms.id,
        kind = terms.kind.name,
        label = terms.label,
        apr = terms.apr.toString(),
        installmentCount = terms.installmentCount,
        paymentCents = schedule.regularPayment.cents,
        totalCents = schedule.total.cents,
        interestCents = schedule.totalInterest.cents,
        summary = "${terms.installmentCount} payments of ${schedule.regularPayment}, total ${schedule.total}",
        schedule = schedule.installments.map { it.toDto(terms) },
    )
}

private fun Installment.toDto(terms: PlanTerms) = InstallmentDto(
    number = number,
    due = due(terms, duePeriod),
    amountCents = amount.cents,
    principalCents = principal.cents,
    interestCents = interest.cents,
)

private fun due(terms: PlanTerms, period: Int): String = when {
    period == 0 -> "at checkout"
    terms is PlanTerms.PayIn4 -> "in ${period * 2} weeks"
    period == 1 -> "in 1 month"
    else -> "in $period months"
}

private fun Degradation.toDto() =
    DegradedDependency(dependency.name.lowercase().replace('_', '-'), cause, fallback)
