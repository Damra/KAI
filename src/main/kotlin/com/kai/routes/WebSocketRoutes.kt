package com.kai.routes

import com.kai.core.MetaController
import com.kai.models.AgentRequest
import com.kai.models.StreamEvent
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("WebSocketRoutes")
private val json = Json { encodeDefaults = true }

fun Route.webSocketRoutes(metaController: MetaController) {

    /** WebSocket /ws/agent — Gerçek zamanlı streaming */
    webSocket("/ws/agent") {
        // Auth: query param ?token=xxx (browser WS API custom header gönderemez)
        val expectedKey = System.getenv("KAI_API_KEY") ?: "dev-key"
        val token = call.parameters["token"]
        if (token != expectedKey) {
            send(Frame.Text(json.encodeToString<StreamEvent>(
                StreamEvent.Error("Unauthorized: invalid or missing token", recoverable = false)
            )))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        val sessionId = call.parameters["session"] ?: UUID.randomUUID().toString()
        logger.info("WebSocket connected [session=$sessionId]")

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    logger.debug("WS received: ${text.take(200)}")

                    val request = try {
                        json.decodeFromString<AgentRequest>(text)
                    } catch (e: Exception) {
                        send(Frame.Text(json.encodeToString<StreamEvent>(
                            StreamEvent.Error("Gecersiz istek: ${e.message}", recoverable = true)
                        )))
                        continue
                    }

                    if (request.message.isBlank()) {
                        send(Frame.Text(json.encodeToString<StreamEvent>(
                            StreamEvent.Error("'message' bos olamaz", recoverable = true)
                        )))
                        continue
                    }

                    try {
                        metaController.process(
                            userRequest = request.message,
                            sessionId = sessionId
                        ) { event ->
                            val eventJson = json.encodeToString<StreamEvent>(event)
                            logger.info("WS sending event: ${eventJson.take(200)}")
                            send(Frame.Text(eventJson))
                        }
                    } catch (e: Exception) {
                        logger.error("WS processing failed", e)
                        send(Frame.Text(json.encodeToString<StreamEvent>(
                            StreamEvent.Error("Islem hatasi: ${e.message}", recoverable = false)
                        )))
                    }
                }
            }
        } catch (e: Exception) {
            logger.info("WebSocket disconnected [session=$sessionId]: ${e.message}")
        } finally {
            logger.info("WebSocket closed [session=$sessionId]")
        }
    }
}
