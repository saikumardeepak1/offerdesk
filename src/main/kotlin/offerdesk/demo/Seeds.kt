package offerdesk.demo

import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.domain.PlanKind
import offerdesk.domain.PlanTerms.Monthly0Apr
import offerdesk.domain.PlanTerms.MonthlyApr
import offerdesk.domain.PlanTerms.PayIn4
import offerdesk.economics.CatalogEntry
import offerdesk.economics.MerchantAgreement
import offerdesk.economics.OfferPolicy
import offerdesk.economics.RiskAssessment
import offerdesk.service.Deadlines
import offerdesk.service.FundingClient
import offerdesk.service.MerchantTermsClient
import offerdesk.service.OfferService
import offerdesk.service.RiskClient

/** Illustrative numbers only. */
object Seeds {
    private val max = Money(1_750_000)

    val policy = OfferPolicy(
        catalog = listOf(
            CatalogEntry(PayIn4, min = Money(5_000), max = Money(100_000)),
            CatalogEntry(Monthly0Apr(3), min = Money(10_000), max = max),
            CatalogEntry(Monthly0Apr(6), min = Money(25_000), max = max),
            CatalogEntry(Monthly0Apr(12), min = Money(50_000), max = max),
            CatalogEntry(MonthlyApr(12, Bps(1_599)), min = Money(10_000), max = max),
            CatalogEntry(MonthlyApr(24, Bps(1_999)), min = Money(50_000), max = max),
        ),
        profitFloor = Money(100),
    )

    val merchants: Map<String, MerchantAgreement> = listOf(
        MerchantAgreement(
            merchantId = "m_northwind",
            name = "Northwind Electronics",
            standardFee = Bps(400),
            promoFees = mapOf(3 to Bps(500), 6 to Bps(700), 12 to Bps(1_000)),
        ),
        MerchantAgreement(
            merchantId = "m_bluefin",
            name = "Bluefin Outdoor",
            standardFee = Bps(300),
            promoFees = mapOf(3 to Bps(300), 6 to Bps(350), 12 to Bps(400)),
        ),
        MerchantAgreement(
            merchantId = "m_cornerstone",
            name = "Cornerstone Furniture",
            standardFee = Bps(350),
            promoFees = emptyMap(),
            disabledKinds = setOf(PlanKind.MONTHLY_0_APR),
        ),
    ).associateBy { it.merchantId }

    val shoppers: Map<String, RiskAssessment> = mapOf(
        "s_prime" to RiskAssessment(Bps(200), Bps(8_000), "prime"),
        "s_near_prime" to RiskAssessment(Bps(600), Bps(8_000), "near-prime"),
        "s_subprime" to RiskAssessment(Bps(1_500), Bps(8_500), "subprime"),
    )

    val thinFile = RiskAssessment(Bps(800), Bps(8_500), "thin-file")

    val costOfFunds = Bps(550)

    fun service(
        merchants: MerchantTermsClient = InMemoryMerchantTerms(),
        risk: RiskClient = InMemoryRisk(),
        funding: FundingClient = InMemoryFunding(),
        deadlines: Deadlines = Deadlines(),
    ) = OfferService(merchants, risk, funding, policy, deadlines, initialCostOfFunds = costOfFunds)
}
