package offerdesk.demo

import kotlinx.coroutines.runBlocking
import offerdesk.domain.Money
import offerdesk.economics.Decision
import offerdesk.service.OfferService
import offerdesk.service.QuoteRequest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

private const val WIDTH = 58
private const val INDENT = "        "

private data class Scenario(val title: String, val service: OfferService, val request: QuoteRequest)

fun main() = runBlocking {
    val amount = Money(90_000)
    val scenarios = listOf(
        Scenario("Northwind Electronics, prime shopper", Seeds.service(), QuoteRequest("m_northwind", "s_prime", amount)),
        Scenario("Bluefin Outdoor, near-prime shopper", Seeds.service(), QuoteRequest("m_bluefin", "s_near_prime", amount)),
        Scenario(
            "Northwind, prime shopper, risk service slow",
            Seeds.service(risk = InMemoryRisk(latency = 2.seconds)),
            QuoteRequest("m_northwind", "s_prime", amount),
        ),
    )
    println("OfferDesk: three $amount checkouts (illustrative numbers)")
    scenarios.forEachIndexed { index, scenario ->
        val (quote, elapsed) = measureTimedValue { scenario.service.quote(scenario.request) }
        println()
        println("${index + 1}) ${scenario.title} [${elapsed.inWholeMilliseconds} ms]")
        quote.degraded.forEach {
            printWrapped("  degraded: ", "${it.dependency.name.lowercase()} ${it.cause}, using ${it.fallback}")
        }
        quote.decisions.forEach(::printDecision)
    }
}

private fun printDecision(decision: Decision) {
    val label = decision.terms.label
    when (decision) {
        is Decision.Offered -> {
            val s = decision.schedule
            val payments = "${s.installments.size} x ${s.regularPayment}"
            println("  %-6s%-24s %12s %11s".format("OFFER", label, payments, s.total))
        }
        is Decision.Hidden -> {
            println("  %-6s%s".format("HIDE", label))
            printWrapped(INDENT, decision.reason.removePrefix("$label hidden: "))
        }
    }
}

private fun printWrapped(lead: String, text: String) {
    var line = lead
    var fresh = true
    for (word in text.split(' ')) {
        if (!fresh && line.length + 1 + word.length > WIDTH) {
            println(line)
            line = INDENT
            fresh = true
        }
        line += if (fresh) word else " $word"
        fresh = false
    }
    println(line)
}
