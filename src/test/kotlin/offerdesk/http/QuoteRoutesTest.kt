package offerdesk.http

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import offerdesk.demo.InMemoryRisk
import offerdesk.demo.Seeds
import offerdesk.service.Deadlines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class QuoteRoutesTest {
    private fun quoteApp(
        service: offerdesk.service.OfferService = Seeds.service(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { offerDesk(service) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postQuote(body: String): HttpResponse =
        client.post("/v1/quotes") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun quoteBody(merchant: String = "m_northwind", shopper: String = "s_prime", cents: Long = 90_000) =
        """{"merchantId":"$merchant","shopperId":"$shopper","amountCents":$cents,"currency":"USD"}"""

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `health reports ok`() = quoteApp {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.json().str("status"))
    }

    @Test
    fun `quote returns offered plans with exact schedules`() = quoteApp {
        val response = postQuote(quoteBody())
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals(90_000, body.getValue("amountCents").jsonPrimitive.long)
        assertEquals("USD", body.str("currency"))
        val payIn4 = body.getValue("offered").jsonArray.first().jsonObject
        assertEquals("pay-in-4", payIn4.str("planId"))
        assertEquals("PAY_IN_4", payIn4.str("kind"))
        assertEquals("0.00%", payIn4.str("apr"))
        assertEquals(22_500, payIn4.getValue("paymentCents").jsonPrimitive.long)
        assertEquals("4 payments of $225.00, total $900.00", payIn4.str("summary"))
        val schedule = payIn4.getValue("schedule").jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 2, 3, 4), schedule.map { it.getValue("number").jsonPrimitive.int })
        assertEquals(listOf("at checkout", "in 2 weeks", "in 4 weeks", "in 6 weeks"), schedule.map { it.str("due") })
        assertEquals(90_000, schedule.sumOf { it.getValue("amountCents").jsonPrimitive.long })
    }

    @Test
    fun `apr plans expose apr, monthly payment, and interest`() = quoteApp {
        val offered = postQuote(quoteBody()).json().getValue("offered").jsonArray.map { it.jsonObject }
        val apr = offered.single { it.str("planId") == "apr-12m" }
        assertEquals("15.99%", apr.str("apr"))
        assertEquals("in 1 month", apr.getValue("schedule").jsonArray.first().jsonObject.str("due"))
        val total = apr.getValue("totalCents").jsonPrimitive.long
        val interest = apr.getValue("interestCents").jsonPrimitive.long
        assertEquals(90_000 + interest, total)
        assertTrue(apr.getValue("paymentCents").jsonPrimitive.long > 7_500)
    }

    @Test
    fun `hidden plans come back with their reasons`() = quoteApp {
        val body = postQuote(quoteBody(merchant = "m_bluefin", shopper = "s_near_prime")).json()
        val hidden = body.getValue("hidden").jsonArray.map { it.jsonObject }.associateBy { it.str("planId") }
        assertEquals(
            "0% for 12 months hidden: merchant fee $36.00 does not cover expected loss $43.20 + funding cost $26.81",
            hidden.getValue("0apr-12m").str("reason"),
        )
        assertTrue(body.getValue("degraded").jsonArray.isEmpty())
    }

    @Test
    fun `degraded dependencies are listed in the response`() = quoteApp(
        Seeds.service(risk = InMemoryRisk(latency = 5.seconds), deadlines = Deadlines(risk = 20.milliseconds)),
    ) {
        val body = postQuote(quoteBody()).json()
        val degraded = body.getValue("degraded").jsonArray.single().jsonObject
        assertEquals("risk", degraded.str("dependency"))
        assertEquals("timed out after 20ms", degraded.str("cause"))
        val offeredIds = body.getValue("offered").jsonArray.map { it.jsonObject.str("planId") }
        assertEquals(listOf("pay-in-4"), offeredIds)
    }

    @Test
    fun `each quote gets its own id`() = quoteApp {
        val first = postQuote(quoteBody()).json().str("quoteId")
        val second = postQuote(quoteBody()).json().str("quoteId")
        assertTrue(first.isNotBlank())
        assertNotEquals(first, second)
    }

    @Test
    fun `missing fields are all reported at once`() = quoteApp {
        val response = postQuote("{}")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val details = response.json().getValue("details").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("merchantId is required", "shopperId is required", "amountCents is required", "currency is required"),
            details,
        )
    }

    @Test
    fun `only USD is accepted`() = quoteApp {
        val response = postQuote(quoteBody().replace("USD", "EUR"))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(listOf("currency must be USD, got EUR"), response.details())
    }

    @Test
    fun `amount must be positive and within the order limit`() = quoteApp {
        assertEquals(listOf("amountCents must be positive"), postQuote(quoteBody(cents = 0)).details())
        assertEquals(
            listOf("amountCents must be at most 3000000 ($30,000.00)"),
            postQuote(quoteBody(cents = 3_000_001)).details(),
        )
    }

    @Test
    fun `malformed json is a 400 with a clear message`() = quoteApp {
        val response = postQuote("""{"merchantId": """)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("request body must be a JSON quote request", response.json().str("error"))
    }

    @Test
    fun `wrong field types are a 400`() = quoteApp {
        val response = postQuote(quoteBody().replace("90000", "\"lots\""))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `unknown merchant is a 404`() = quoteApp {
        val response = postQuote(quoteBody(merchant = "m_nobody"))
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("unknown merchant m_nobody", response.json().str("error"))
    }

    private suspend fun HttpResponse.details() =
        json().getValue("details").jsonArray.map { it.jsonPrimitive.content }
}
