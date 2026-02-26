package com.kai.llm

import com.kai.core.*
import com.kai.models.CodeArtifact
import com.kai.tools.ToolDefinition
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Anthropic Claude API istemcisi.
 * Tool use, reasoning ve basit chat destekler.
 */
class AnthropicClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-5-20250929",
    private val maxTokens: Int = 4096
) : LLMClient {
    private val logger = LoggerFactory.getLogger(AnthropicClient::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }
    private val apiUrl = "https://api.anthropic.com/v1/messages"

    override suspend fun reason(
        system: String,
        trajectory: List<AgentStep>,
        availableTools: List<ToolDefinition>
    ): AgentStep {
        val messages = trajectoryToMessages(trajectory)

        // İlk mesaj yoksa kullanıcı mesajı ekle
        val finalMessages = if (messages.isEmpty()) {
            listOf(buildJsonObject {
                put("role", "user")
                put("content", "Görevi başlat. Düşün ve uygun aracı kullan.")
            })
        } else {
            messages
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonArray("messages") {
                finalMessages.forEach { add(it) }
            }
            if (availableTools.isNotEmpty()) {
                putJsonArray("tools") {
                    availableTools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.encodeToJsonElement(tool.inputSchema))
                        })
                    }
                }
            }
        }

        val response = httpClient.post(apiUrl) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        val responseBody = response.bodyAsText()

        if (response.status.value !in 200..299) {
            logger.error("Anthropic API error (${response.status}): $responseBody")
            return AgentStep.Error(
                message = "LLM API hatasi: ${response.status} - ${responseBody.take(200)}",
                recoverable = response.status.value in 429..599,
                suggestedAction = if (response.status.value == 429) "Rate limit, bekle ve tekrar dene" else null
            )
        }

        return parseResponse(responseBody)
    }

    override suspend fun chat(system: String, user: String): String {
        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            }
        }

        val response = httpClient.post(apiUrl) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        val responseBody = response.bodyAsText()
        val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
        val content = jsonResponse["content"]?.jsonArray?.firstOrNull()?.jsonObject
        return content?.get("text")?.jsonPrimitive?.content ?: ""
    }

    /** Anthropic API yanıtını AgentStep'e dönüştür */
    private fun parseResponse(responseBody: String): AgentStep {
        return try {
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val stopReason = jsonResponse["stop_reason"]?.jsonPrimitive?.content
            val content = jsonResponse["content"]?.jsonArray ?: return AgentStep.Error(
                "Bos yanit", recoverable = true
            )

            when (stopReason) {
                "tool_use" -> {
                    // Tool call yanıtı
                    val toolBlock = content.firstOrNull { block ->
                        block.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
                    }?.jsonObject

                    if (toolBlock != null) {
                        val toolName = toolBlock["name"]?.jsonPrimitive?.content ?: "unknown"
                        val input = toolBlock["input"]?.jsonObject ?: buildJsonObject { }

                        // Thinking text varsa reasoning olarak kullan
                        val thinkingText = content.firstOrNull { block ->
                            block.jsonObject["type"]?.jsonPrimitive?.content == "text"
                        }?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""

                        AgentStep.Act(
                            toolName = toolName,
                            toolInput = input.entries.associate { (k, v) ->
                                k to v.jsonPrimitive.content
                            },
                            reasoning = thinkingText
                        )
                    } else {
                        AgentStep.Error("Tool use beklendi ama bulunamadi", recoverable = true)
                    }
                }

                "end_turn", null -> {
                    // Normal text yanıtı → Answer veya Think olarak yorumla
                    val textContent = content.firstOrNull { block ->
                        block.jsonObject["type"]?.jsonPrimitive?.content == "text"
                    }?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""

                    // Code block varsa artifact olarak çıkar
                    val artifacts = extractCodeArtifacts(textContent)

                    if (artifacts.isNotEmpty() || textContent.length > 100) {
                        AgentStep.Answer(
                            content = textContent,
                            artifacts = artifacts
                        )
                    } else {
                        AgentStep.Think(
                            thought = textContent,
                            confidence = 0.5
                        )
                    }
                }

                else -> {
                    val text = content.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: responseBody
                    AgentStep.Think(thought = text)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse Anthropic response", e)
            AgentStep.Error(
                message = "Yanit parse hatasi: ${e.message}",
                recoverable = true
            )
        }
    }

    /** Markdown code bloklarından CodeArtifact'ları çıkar */
    private fun extractCodeArtifacts(text: String): List<CodeArtifact> {
        val codeBlockRegex = Regex("```(\\w+)?\\s*\\n([\\s\\S]*?)```")
        return codeBlockRegex.findAll(text).mapIndexed { index, match ->
            val language = match.groupValues[1].ifEmpty { "kotlin" }
            CodeArtifact(
                filename = "generated_${index + 1}.${languageExtension(language)}",
                language = language,
                content = match.groupValues[2].trim()
            )
        }.toList()
    }

    private fun languageExtension(language: String): String = when (language.lowercase()) {
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "xml" -> "xml"
        "json" -> "json"
        "yaml", "yml" -> "yml"
        "groovy", "gradle" -> "gradle.kts"
        else -> "txt"
    }

    /** Trajectory'yi Anthropic messages formatına dönüştür */
    private fun trajectoryToMessages(trajectory: List<AgentStep>): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()

        for (step in trajectory) {
            when (step) {
                is AgentStep.Think -> {
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", step.thought)
                    })
                }

                is AgentStep.Act -> {
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", "tool_${System.nanoTime()}")
                                put("name", step.toolName)
                                put("input", buildJsonObject {
                                    step.toolInput.forEach { (k, v) -> put(k, v) }
                                })
                            })
                        }
                    })
                }

                is AgentStep.Observe -> {
                    val resultText = when (step.result) {
                        is ToolResult.Success -> step.result.output
                        is ToolResult.Failure -> "ERROR: ${step.result.error}"
                    }
                    messages.add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", "tool_${step.toolName}")
                                put("content", resultText)
                            })
                        }
                    })
                }

                is AgentStep.Answer -> {
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", step.content)
                    })
                }

                is AgentStep.Error -> {
                    messages.add(buildJsonObject {
                        put("role", "user")
                        put("content", "HATA: ${step.message}. ${step.suggestedAction ?: "Devam et."}")
                    })
                }

                is AgentStep.Delegate -> {
                    // Delegation durumunda bilgi mesajı
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", "Gorevi ${step.targetAgent} ajanina devrediyorum: ${step.context.reason}")
                    })
                }
            }
        }

        return messages
    }
}
