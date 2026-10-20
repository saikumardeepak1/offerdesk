package offerdesk.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import offerdesk.demo.Seeds

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port) { offerDesk(Seeds.service()) }.start(wait = true)
}
