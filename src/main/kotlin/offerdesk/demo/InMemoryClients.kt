package offerdesk.demo

import kotlinx.coroutines.delay
import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.economics.MerchantAgreement
import offerdesk.economics.RiskAssessment
import offerdesk.service.FundingClient
import offerdesk.service.MerchantTermsClient
import offerdesk.service.RiskClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class InMemoryMerchantTerms(
    private val agreements: Map<String, MerchantAgreement> = Seeds.merchants,
    private val latency: Duration = 40.milliseconds,
) : MerchantTermsClient {
    override suspend fun agreement(merchantId: String): MerchantAgreement? {
        delay(latency)
        return agreements[merchantId]
    }
}

class InMemoryRisk(
    private val shoppers: Map<String, RiskAssessment> = Seeds.shoppers,
    private val unknownShopper: RiskAssessment = Seeds.thinFile,
    private val latency: Duration = 120.milliseconds,
) : RiskClient {
    override suspend fun assess(shopperId: String, amount: Money): RiskAssessment {
        delay(latency)
        return shoppers[shopperId] ?: unknownShopper
    }
}

class InMemoryFunding(
    private val rate: Bps = Seeds.costOfFunds,
    private val latency: Duration = 30.milliseconds,
) : FundingClient {
    override suspend fun costOfFunds(): Bps {
        delay(latency)
        return rate
    }
}
