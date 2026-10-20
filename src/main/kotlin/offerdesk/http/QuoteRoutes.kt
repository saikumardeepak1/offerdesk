package offerdesk.http

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import offerdesk.domain.Money
import offerdesk.service.OfferService
import offerdesk.service.QuoteRequest
import offerdesk.service.UnknownMerchantException

const val MAX_ORDER_CENTS = 3_000_000L

fun Application.offerDesk(service: OfferService) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(Health("ok")) }

        post("/v1/quotes") {
            val dto = try {
                call.receive<QuoteRequestDto>()
            } catch (e: BadRequestException) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("request body must be a JSON quote request"))
            }
            val problems = dto.problems()
            if (problems.isNotEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid quote request", problems))
            }
            val request = QuoteRequest(dto.merchantId!!, dto.shopperId!!, Money(dto.amountCents!!))
            try {
                call.respond(service.quote(request).toResponse())
            } catch (e: UnknownMerchantException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message!!))
            }
        }
    }
}

internal fun QuoteRequestDto.problems(): List<String> = buildList {
    if (merchantId.isNullOrBlank()) add("merchantId is required")
    if (shopperId.isNullOrBlank()) add("shopperId is required")
    when {
        amountCents == null -> add("amountCents is required")
        amountCents <= 0 -> add("amountCents must be positive")
        amountCents > MAX_ORDER_CENTS -> add("amountCents must be at most $MAX_ORDER_CENTS (${Money(MAX_ORDER_CENTS)})")
    }
    when {
        currency == null -> add("currency is required")
        currency != "USD" -> add("currency must be USD, got $currency")
    }
}
