package offerdesk.service

import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.economics.MerchantAgreement
import offerdesk.economics.RiskAssessment

fun interface MerchantTermsClient {
    /** The merchant's promo agreement, or null when the merchant does not exist. */
    suspend fun agreement(merchantId: String): MerchantAgreement?
}

fun interface RiskClient {
    suspend fun assess(shopperId: String, amount: Money): RiskAssessment
}

fun interface FundingClient {
    /** Annual cost of funds. */
    suspend fun costOfFunds(): Bps
}
