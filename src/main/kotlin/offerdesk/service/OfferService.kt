package offerdesk.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import offerdesk.domain.Bps
import offerdesk.economics.OfferPolicy
import offerdesk.economics.RiskAssessment
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

class OfferService(
    private val merchants: MerchantTermsClient,
    private val risk: RiskClient,
    private val funding: FundingClient,
    private val policy: OfferPolicy,
    private val deadlines: Deadlines = Deadlines(),
    initialCostOfFunds: Bps,
    private val conservativeRisk: RiskAssessment = CONSERVATIVE_RISK,
    private val newQuoteId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lastKnownCostOfFunds = AtomicReference(initialCostOfFunds)

    /**
     * Fetches all three inputs at once inside one scope: if the caller is cancelled, or the
     * merchant turns out not to exist, every in-flight call is cancelled with it.
     */
    suspend fun quote(request: QuoteRequest): Quote = coroutineScope {
        val merchantCall = async { fetch(deadlines.merchantTerms) { merchants.agreement(request.merchantId) } }
        val riskCall = async { fetch(deadlines.risk) { risk.assess(request.shopperId, request.amount) } }
        val fundingCall = async { fetch(deadlines.funding) { funding.costOfFunds() } }

        val degraded = mutableListOf<Degradation>()

        val agreement = when (val result = merchantCall.await()) {
            is Fetched.Ok -> result.value ?: throw UnknownMerchantException(request.merchantId)
            is Fetched.Failed -> null.also {
                degraded += Degradation(Dependency.MERCHANT_TERMS, result.cause, "standard plans only")
            }
        }
        val assessment = when (val result = riskCall.await()) {
            is Fetched.Ok -> result.value
            is Fetched.Failed -> conservativeRisk.also {
                degraded += Degradation(Dependency.RISK, result.cause, "conservative default risk")
            }
        }
        val costOfFunds = when (val result = fundingCall.await()) {
            is Fetched.Ok -> result.value.also(lastKnownCostOfFunds::set)
            is Fetched.Failed -> lastKnownCostOfFunds.get().also {
                degraded += Degradation(Dependency.FUNDING, result.cause, "last known rate $it")
            }
        }

        Quote(
            quoteId = newQuoteId(),
            request = request,
            decisions = policy.evaluate(request.amount, agreement, assessment, costOfFunds),
            degraded = degraded,
            costOfFunds = costOfFunds,
            fundingRateStale = degraded.any { it.dependency == Dependency.FUNDING },
        )
    }

    private sealed interface Fetched<out T> {
        data class Ok<T>(val value: T) : Fetched<T>

        data class Failed(val cause: String) : Fetched<Nothing>
    }

    private suspend fun <T> fetch(deadline: Duration, call: suspend () -> T): Fetched<T> =
        try {
            Fetched.Ok(withTimeout(deadline) { call() })
        } catch (e: TimeoutCancellationException) {
            Fetched.Failed("timed out after $deadline")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Fetched.Failed("failed: ${e.message ?: e::class.simpleName}")
        }

    companion object {
        /** Used when risk is unknown: high enough that merchant-funded 0% plans do not pencil out. */
        val CONSERVATIVE_RISK = RiskAssessment(annualDefault = Bps(2_500), lossGivenDefault = Bps(9_000), tier = "fallback")
    }
}
