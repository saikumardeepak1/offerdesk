package offerdesk.economics

import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.domain.PlanKind
import offerdesk.domain.PlanTerms.Monthly0Apr
import offerdesk.domain.PlanTerms.MonthlyApr
import offerdesk.domain.PlanTerms.PayIn4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OfferPolicyTest {
    private val catalog = listOf(
        CatalogEntry(PayIn4, min = Money(5_000), max = Money(100_000)),
        CatalogEntry(Monthly0Apr(12), min = Money(50_000), max = Money(1_750_000)),
        CatalogEntry(MonthlyApr(12, Bps(1_200)), min = Money(10_000), max = Money(1_750_000)),
    )
    private val policy = OfferPolicy(catalog, profitFloor = Money.ZERO)
    private val lowRisk = RiskAssessment(Bps(200), Bps(8_000), "low")
    private val midRisk = RiskAssessment(Bps(600), Bps(8_000), "mid")
    private val generous = MerchantAgreement(
        merchantId = "m1",
        name = "Northwind Electronics",
        standardFee = Bps(400),
        promoFees = mapOf(12 to Bps(1_000)),
    )
    private val thin = generous.copy(name = "Bluefin Outdoor", promoFees = mapOf(12 to Bps(400)))

    private fun decide(
        amount: Money,
        merchant: MerchantAgreement? = generous,
        risk: RiskAssessment = lowRisk,
        floor: Money = Money.ZERO,
    ) = OfferPolicy(catalog, floor).evaluate(amount, merchant, risk, Bps(550)).associateBy { it.terms.id }

    @Test
    fun `offers every plan when the economics work`() {
        val decisions = decide(Money(90_000))
        assertEquals(listOf("pay-in-4", "0apr-12m", "apr-12m"), decisions.keys.toList())
        decisions.values.forEach { assertIs<Decision.Offered>(it) }
    }

    @Test
    fun `offered plans carry their exact schedule`() {
        val offered = assertIs<Decision.Offered>(decide(Money(120_000))["0apr-12m"])
        assertEquals(Money(120_000), offered.schedule.total)
        assertEquals(Money(6_505), offered.economics.expectedProfit)
    }

    @Test
    fun `hides an unprofitable promotion with the numbers that sink it`() {
        val hidden = assertIs<Decision.Hidden>(decide(Money(120_000), thin, midRisk)["0apr-12m"])
        assertEquals(
            "0% for 12 months hidden: merchant fee $48.00 does not cover " +
                "expected loss $57.60 + funding cost $35.75",
            hidden.reason,
        )
    }

    @Test
    fun `apr reason counts interest alongside the merchant fee`() {
        val risky = RiskAssessment(Bps(2_500), Bps(9_000), "high")
        val hidden = assertIs<Decision.Hidden>(decide(Money(100_000), risk = risky)["apr-12m"])
        assertEquals(
            "12.00% APR for 12 months hidden: merchant fee $40.00 + interest $66.19 " +
                "does not cover expected loss $225.00 + funding cost $30.33",
            hidden.reason,
        )
    }

    @Test
    fun `profit exactly at the floor is offered`() {
        val decisions = decide(Money(120_000), floor = Money(6_505))
        assertIs<Decision.Offered>(decisions["0apr-12m"])
    }

    @Test
    fun `one cent under the floor is hidden with the gap spelled out`() {
        val hidden = assertIs<Decision.Hidden>(decide(Money(120_000), floor = Money(6_506))["0apr-12m"])
        assertEquals("0% for 12 months hidden: expected profit $65.05 is below the $65.06 floor", hidden.reason)
    }

    @Test
    fun `pay in 4 is only for orders under its cap`() {
        val hidden = assertIs<Decision.Hidden>(decide(Money(120_000))["pay-in-4"])
        assertEquals("Pay in 4 hidden: order $1,200.00 is above the $1,000.00 limit", hidden.reason)
    }

    @Test
    fun `plans have a minimum order`() {
        val hidden = assertIs<Decision.Hidden>(decide(Money(30_000))["0apr-12m"])
        assertEquals("0% for 12 months hidden: order $300.00 is below the $500.00 minimum", hidden.reason)
    }

    @Test
    fun `a merchant can turn off a plan kind`() {
        val noPromos = generous.copy(name = "Cornerstone Furniture", disabledKinds = setOf(PlanKind.MONTHLY_0_APR))
        val hidden = assertIs<Decision.Hidden>(decide(Money(90_000), noPromos)["0apr-12m"])
        assertEquals("0% for 12 months hidden: Cornerstone Furniture has turned off 0% APR plans", hidden.reason)
    }

    @Test
    fun `a promotion needs a merchant fee agreed for that exact term`() {
        val sixOnly = generous.copy(promoFees = mapOf(6 to Bps(700)))
        val hidden = assertIs<Decision.Hidden>(decide(Money(90_000), sixOnly)["0apr-12m"])
        assertEquals("0% for 12 months hidden: Northwind Electronics does not fund a 12 month promotion", hidden.reason)
    }

    @Test
    fun `without merchant terms only interest bearing plans can show`() {
        val decisions = decide(Money(90_000), merchant = null)
        val payIn4 = assertIs<Decision.Hidden>(decisions["pay-in-4"])
        assertEquals("Pay in 4 hidden: merchant terms unavailable, so merchant funded plans are paused", payIn4.reason)
        assertIs<Decision.Hidden>(decisions["0apr-12m"])
        assertIs<Decision.Offered>(decisions["apr-12m"])
    }

    @Test
    fun `without merchant terms apr plans are priced with no merchant fee`() {
        val offered = assertIs<Decision.Offered>(decide(Money(90_000), merchant = null)["apr-12m"])
        assertEquals(Money.ZERO, offered.economics.merchantFee)
    }

    @Test
    fun `merchant switches are checked before order limits`() {
        val noPayIn4 = generous.copy(disabledKinds = setOf(PlanKind.PAY_IN_4))
        val hidden = assertIs<Decision.Hidden>(decide(Money(120_000), noPayIn4)["pay-in-4"])
        assertEquals("Pay in 4 hidden: Northwind Electronics has turned off Pay in 4", hidden.reason)
    }

    @Test
    fun `decisions keep catalog order`() {
        assertEquals(catalog.map { it.terms.id }, policy.evaluate(Money(90_000), generous, lowRisk, Bps(550)).map { it.terms.id })
    }
}
