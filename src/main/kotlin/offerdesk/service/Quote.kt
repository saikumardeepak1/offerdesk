package offerdesk.service

import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.economics.Decision
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class QuoteRequest(val merchantId: String, val shopperId: String, val amount: Money)

enum class Dependency { MERCHANT_TERMS, RISK, FUNDING }

data class Degradation(val dependency: Dependency, val cause: String, val fallback: String)

data class Quote(
    val quoteId: String,
    val request: QuoteRequest,
    val decisions: List<Decision>,
    val degraded: List<Degradation>,
    val costOfFunds: Bps,
    val fundingRateStale: Boolean,
) {
    val offered: List<Decision.Offered> get() = decisions.filterIsInstance<Decision.Offered>()
    val hidden: List<Decision.Hidden> get() = decisions.filterIsInstance<Decision.Hidden>()
}

data class Deadlines(
    val merchantTerms: Duration = 200.milliseconds,
    val risk: Duration = 300.milliseconds,
    val funding: Duration = 100.milliseconds,
)

class UnknownMerchantException(val merchantId: String) : RuntimeException("unknown merchant $merchantId")
