package com.kai.routes

import com.kai.core.MetaController
import com.kai.models.AgentRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("AgentRoutes")

fun Route.agentRoutes(metaController: MetaController) {

    /** POST /api/v1/agent — Tek seferlik istek/cevap */
    post("/api/v1/agent") {
        val request = try {
            call.receive<AgentRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Gecersiz istek: ${e.message}"))
            return@post
        }

        if (request.message.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "'message' bos olamaz"))
            return@post
        }

        val sessionId = request.sessionId ?: UUID.randomUUID().toString()

        logger.info("Agent request [session=$sessionId]: ${request.message.take(100)}")

        try {
            val response = metaController.process(
                userRequest = request.message,
                sessionId = sessionId
            )
            call.respond(response)
        } catch (e: Exception) {
            logger.error("Agent processing failed", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Islem hatasi: ${e.message}")
            )
        }
    }

    /** GET /api/v1/health — Sağlık kontrolü */
    get("/api/v1/health") {
        call.respond(
            mapOf(
                "status" to "ok",
                "service" to "KAI Agent",
                "version" to "0.1.0"
            )
        )
    }
}
