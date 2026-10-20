package offerdesk.economics

import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.domain.PlanTerms.Monthly0Apr
import offerdesk.domain.PlanTerms.MonthlyApr
import offerdesk.domain.PlanTerms.PayIn4
import offerdesk.domain.Schedule
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitEconomicsTest {
    private val lowRisk = RiskAssessment(annualDefault = Bps(200), lossGivenDefault = Bps(8_000), tier = "low")
    private val midRisk = RiskAssessment(annualDefault = Bps(600), lossGivenDefault = Bps(8_000), tier = "mid")
    private val costOfFunds = Bps(550)

    @Test
    fun `generous merchant funds a 12 month promotion with room to spare`() {
        val schedule = Schedule.of(Monthly0Apr(12), Money(120_000))
        val economics = UnitEconomics.of(schedule, Bps(1_000), lowRisk, costOfFunds)
        assertEquals(Money(12_000), economics.merchantFee)
        assertEquals(Money.ZERO, economics.interest)
        assertEquals(Money(1_920), economics.expectedLoss)
        assertEquals(Money(3_575), economics.fundingCost)
        assertEquals(Money(6_505), economics.expectedProfit)
    }

    @Test
    fun `thin merchant fee loses money on a 12 month promotion`() {
        val schedule = Schedule.of(Monthly0Apr(12), Money(120_000))
        val economics = UnitEconomics.of(schedule, Bps(400), midRisk, costOfFunds)
        assertEquals(Money(4_800), economics.merchantFee)
        assertEquals(Money(5_760), economics.expectedLoss)
        assertEquals(Money(-4_535), economics.expectedProfit)
    }

    @Test
    fun `pay in 4 carries six weeks of risk and funding`() {
        val schedule = Schedule.of(PayIn4, Money(40_000))
        val economics = UnitEconomics.of(schedule, Bps(400), lowRisk, costOfFunds)
        assertEquals(Money(1_600), economics.merchantFee)
        assertEquals(Money(74), economics.expectedLoss)
        assertEquals(Money(127), economics.fundingCost)
        assertEquals(Money(1_399), economics.expectedProfit)
    }

    @Test
    fun `apr plans earn interest on top of the merchant fee`() {
        val schedule = Schedule.of(MonthlyApr(12, Bps(1_200)), Money(100_000))
        val economics = UnitEconomics.of(schedule, Bps(400), midRisk, costOfFunds)
        assertEquals(Money(4_000), economics.merchantFee)
        assertEquals(Money(6_619), economics.interest)
        assertEquals(Money(4_800), economics.expectedLoss)
        assertEquals(Money(3_033), economics.fundingCost)
        assertEquals(Money(2_786), economics.expectedProfit)
    }

    @Test
    fun `funding cost uses the declining balance, not the full amount`() {
        val schedule = Schedule.of(Monthly0Apr(12), Money(120_000))
        val economics = UnitEconomics.of(schedule, Bps(0), lowRisk, Bps(1_200))
        val fullAmountForAYear = Money(14_400)
        assertEquals(Money(7_800), economics.fundingCost)
        assertEquals(true, economics.fundingCost < fullAmountForAYear)
    }

    @Test
    fun `default probability is capped at certainty for very long risky terms`() {
        val certainDefault = RiskAssessment(Bps(9_000), Bps(10_000), "extreme")
        val schedule = Schedule.of(Monthly0Apr(36), Money(100_000))
        val economics = UnitEconomics.of(schedule, Bps(0), certainDefault, Bps(0))
        assertEquals(Money(100_000), economics.expectedLoss)
    }

    @Test
    fun `expected profit is revenue minus loss minus funding`() {
        val economics = UnitEconomics(Money(500), Money(250), Money(300), Money(125))
        assertEquals(Money(325), economics.expectedProfit)
    }
}
