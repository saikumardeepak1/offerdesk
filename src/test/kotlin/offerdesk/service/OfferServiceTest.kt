package offerdesk.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import offerdesk.demo.Seeds
import offerdesk.domain.Bps
import offerdesk.domain.Money
import offerdesk.domain.PlanKind
import offerdesk.economics.Decision
import offerdesk.economics.RiskAssessment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class OfferServiceTest {
    private val request = QuoteRequest("m_northwind", "s_prime", Money(90_000))
    private val prime = RiskAssessment(Bps(200), Bps(8_000), "prime")

    private fun service(
        merchants: MerchantTermsClient = MerchantTermsClient { delay(40); Seeds.merchants[it] },
        risk: RiskClient = RiskClient { _, _ -> delay(120); prime },
        funding: FundingClient = FundingClient { delay(30); Bps(550) },
    ) = OfferService(
        merchants = merchants,
        risk = risk,
        funding = funding,
        policy = Seeds.policy,
        deadlines = Deadlines(merchantTerms = 200.milliseconds, risk = 300.milliseconds, funding = 100.milliseconds),
        initialCostOfFunds = Bps(600),
        newQuoteId = { "q-1" },
    )

    private fun Quote.offeredIds() = decisions.filterIsInstance<Decision.Offered>().map { it.terms.id }

    @Test
    fun `dependency calls run in parallel, so latency is the max not the sum`() = runTest {
        service().quote(request)
        assertEquals(120, testScheduler.currentTime)
    }

    @Test
    fun `healthy dependencies produce a full quote with nothing degraded`() = runTest {
        val quote = service().quote(request)
        assertEquals("q-1", quote.quoteId)
        assertEquals(Seeds.policy.catalog.map { it.terms.id }, quote.offeredIds())
        assertTrue(quote.degraded.isEmpty())
        assertFalse(quote.fundingRateStale)
        assertEquals(Bps(550), quote.costOfFunds)
    }

    @Test
    fun `slow risk times out at its deadline and hides every 0 percent offer`() = runTest {
        val quote = service(risk = { _, _ -> delay(5_000); prime }).quote(request)
        assertEquals(300, testScheduler.currentTime)
        assertEquals(listOf(Dependency.RISK), quote.degraded.map { it.dependency })
        assertEquals("timed out after 300ms", quote.degraded.single().cause)
        assertFalse(quote.offeredIds().any { it.startsWith("0apr") })
    }

    @Test
    fun `risk fallback is conservative for every order size`() = runTest {
        val svc = service(risk = { _, _ -> error("risk service down") })
        for (cents in 10_000L..1_750_000L step 25_000) {
            val quote = svc.quote(request.copy(amount = Money(cents)))
            val offeredPromos = quote.decisions.filterIsInstance<Decision.Offered>()
                .filter { it.terms.kind == PlanKind.MONTHLY_0_APR }
            assertTrue(offeredPromos.isEmpty(), "0% offered on fallback risk at ${Money(cents)}")
        }
    }

    @Test
    fun `a failing risk call is recorded with its cause and fallback`() = runTest {
        val quote = service(risk = { _, _ -> error("connection reset") }).quote(request)
        val degraded = quote.degraded.single()
        assertEquals(Dependency.RISK, degraded.dependency)
        assertEquals("failed: connection reset", degraded.cause)
        assertEquals("conservative default risk", degraded.fallback)
    }

    @Test
    fun `merchant terms failure leaves only standard interest bearing plans`() = runTest {
        val quote = service(merchants = { error("terms store unavailable") }).quote(request)
        assertEquals(listOf(Dependency.MERCHANT_TERMS), quote.degraded.map { it.dependency })
        assertTrue(quote.offeredIds().isNotEmpty())
        assertTrue(quote.offeredIds().all { it.startsWith("apr") })
    }

    @Test
    fun `merchant terms timeout is bounded by its own deadline`() = runTest {
        val quote = service(merchants = { awaitCancellation() }).quote(request)
        assertEquals(200, testScheduler.currentTime)
        assertEquals("timed out after 200ms", quote.degraded.single().cause)
    }

    @Test
    fun `funding failure reuses the last known rate and flags it stale`() = runTest {
        var up = true
        val svc = service(funding = { if (up) Bps(700) else error("rates feed down") })
        assertEquals(Bps(700), svc.quote(request).costOfFunds)
        up = false
        val quote = svc.quote(request)
        assertEquals(Bps(700), quote.costOfFunds)
        assertTrue(quote.fundingRateStale)
        assertEquals("last known rate 7.00%", quote.degraded.single().fallback)
    }

    @Test
    fun `funding failure before any success uses the configured starting rate`() = runTest {
        val quote = service(funding = { awaitCancellation() }).quote(request)
        assertEquals(Bps(600), quote.costOfFunds)
        assertTrue(quote.fundingRateStale)
        assertEquals(120, testScheduler.currentTime)
    }

    @Test
    fun `every dependency hanging still answers at the slowest deadline`() = runTest {
        val quote = service(
            merchants = { awaitCancellation() },
            risk = { _, _ -> awaitCancellation() },
            funding = { awaitCancellation() },
        ).quote(request)
        assertEquals(300, testScheduler.currentTime)
        assertEquals(Dependency.entries.toSet(), quote.degraded.map { it.dependency }.toSet())
    }

    @Test
    fun `unknown merchant fails the quote and cancels the other calls`() = runTest {
        val cancelled = mutableListOf<String>()
        val svc = service(
            merchants = { delay(10); null },
            risk = { _, _ -> trackCancel("risk", cancelled) },
            funding = { trackCancel("funding", cancelled) },
        )
        assertFailsWith<UnknownMerchantException> { svc.quote(request.copy(merchantId = "m_nope")) }
        assertEquals(10, testScheduler.currentTime)
        assertEquals(setOf("risk", "funding"), cancelled.toSet())
    }

    @Test
    fun `cancelling the request cancels all three child calls`() = runTest {
        val cancelled = mutableListOf<String>()
        val svc = service(
            merchants = { trackCancel("merchant", cancelled) },
            risk = { _, _ -> trackCancel("risk", cancelled) },
            funding = { trackCancel("funding", cancelled) },
        )
        val job = launch { svc.quote(request) }
        advanceTimeBy(50)
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(setOf("merchant", "risk", "funding"), cancelled.toSet())
        assertEquals(50, testScheduler.currentTime)
    }

    @Test
    fun `parent cancellation is not mistaken for a dependency failure`() = runTest {
        var quoteFinished = false
        val svc = service(risk = { _, _ -> delay(100); prime })
        val job = launch {
            svc.quote(request)
            quoteFinished = true
        }
        advanceTimeBy(60)
        job.cancel()
        job.join()
        assertFalse(quoteFinished)
        assertTrue(job.isCancelled)
    }

    private suspend fun <T> trackCancel(name: String, log: MutableList<String>): T =
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            log += name
            throw e
        }
}
