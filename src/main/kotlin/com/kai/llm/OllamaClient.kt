package com.kai.llm

import com.kai.core.*
import com.kai.models.CodeArtifact
import com.kai.tools.ToolDefinition
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Ollama lokal LLM istemcisi.
 *
 * Ollama API: http://localhost:11434/api/chat
 * Tool use destekli modeller: qwen2.5-coder, llama3.1, mistral-nemo, vb.
 *
 * Kurulum:
 *   1) https://ollama.com adresinden indir
 *   2) ollama pull qwen2.5-coder:7b
 *   3) ollama serve (arka planda çalışır)
 */
class OllamaClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "qwen2.5-coder:7b",
    private val temperature: Double = 0.3
) : LLMClient {
    private val logger = LoggerFactory.getLogger(OllamaClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    override suspend fun reason(
        system: String,
        trajectory: List<AgentStep>,
        availableTools: List<ToolDefinition>
    ): AgentStep {
        val messages = mutableListOf<JsonObject>()

        // System prompt
        messages.add(buildJsonObject {
            put("role", "system")
            put("content", system)
        })

        // Trajectory → messages
        messages.addAll(trajectoryToMessages(trajectory))

        // İlk mesaj yoksa başlat
        if (messages.size == 1) {
            messages.add(buildJsonObject {
                put("role", "user")
                put("content", "Gorevi baslat. Dusun ve uygun araci kullan.")
            })
        }

        val requestBody = buildJsonObject {
            put("model", model)
            putJsonArray("messages") { messages.forEach { add(it) } }
            put("stream", false)
            put("temperature", temperature)

            // Tool definitions (Ollama tool use formatı)
            if (availableTools.isNotEmpty()) {
                putJsonArray("tools") {
                    availableTools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", buildJsonObject {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        tool.inputSchema.properties.forEach { (name, prop) ->
                                            putJsonObject(name) {
                                                put("type", prop.type)
                                                put("description", prop.description)
                                            }
                                        }
                                    }
                                    putJsonArray("required") {
                                        tool.inputSchema.required.forEach { add(it) }
                                    }
                                })
                            })
                        })
                    }
                }
            }
        }

        return try {
            val response = httpClient.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), requestBody))
            }

            val body = response.bodyAsText()

            if (response.status.value !in 200..299) {
                logger.error("Ollama API error (${response.status}): ${body.take(300)}")
                return AgentStep.Error(
                    message = "Ollama API hatasi: ${response.status}",
                    recoverable = true,
                    suggestedAction = "Ollama servisinin calistigini kontrol et: ollama serve"
                )
            }

            parseResponse(body)
        } catch (e: Exception) {
            logger.error("Ollama connection failed", e)
            AgentStep.Error(
                message = "Ollama baglanti hatasi: ${e.message}. 'ollama serve' calistigindan emin ol.",
                recoverable = false
            )
        }
    }

    override suspend fun chat(system: String, user: String): String {
        val requestBody = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            }
            put("stream", false)
            put("temperature", temperature)
        }

        return try {
            val response = httpClient.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), requestBody))
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val message = body["message"]?.jsonObject
            message?.get("content")?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            logger.error("Ollama chat failed", e)
            ""
        }
    }

    /** Ollama yanıtını AgentStep'e dönüştür */
    private fun parseResponse(responseBody: String): AgentStep {
        return try {
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val message = jsonResponse["message"]?.jsonObject
                ?: return AgentStep.Error("Bos Ollama yaniti", recoverable = true)

            val content = message["content"]?.jsonPrimitive?.content ?: ""
            val toolCalls = message["tool_calls"]?.jsonArray

            // Tool call varsa → Act
            if (toolCalls != null && toolCalls.isNotEmpty()) {
                val toolCall = toolCalls.first().jsonObject
                val function = toolCall["function"]?.jsonObject

                if (function != null) {
                    val toolName = function["name"]?.jsonPrimitive?.content ?: "unknown"
                    val arguments = function["arguments"]?.jsonObject ?: buildJsonObject { }

                    return AgentStep.Act(
                        toolName = toolName,
                        toolInput = arguments.entries.associate { (k, v) ->
                            k to (v.jsonPrimitive.contentOrNull ?: v.toString())
                        },
                        reasoning = content.ifEmpty { "Tool cagiriliyor: $toolName" }
                    )
                }
            }

            // Content içinde JSON tool call varsa parse et (bazı modeller tool_calls yerine content'e yazar)
            val inlineToolCall = tryParseInlineToolCall(content)
            if (inlineToolCall != null) {
                return inlineToolCall
            }

            // Code block varsa → Answer
            val artifacts = extractCodeArtifacts(content)
            if (artifacts.isNotEmpty() || content.length > 100) {
                AgentStep.Answer(content = content, artifacts = artifacts)
            } else {
                AgentStep.Think(thought = content, confidence = 0.5)
            }
        } catch (e: Exception) {
            logger.error("Failed to parse Ollama response", e)
            AgentStep.Error("Ollama yanit parse hatasi: ${e.message}", recoverable = true)
        }
    }

    /**
     * Bazı modeller tool call'ları JSON olarak content içine yazar.
     * Örnek: {"name": "web_search", "arguments": {"query": "..."}}
     */
    private fun tryParseInlineToolCall(content: String): AgentStep.Act? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        return try {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val args = obj["arguments"]?.jsonObject ?: return null

            AgentStep.Act(
                toolName = name,
                toolInput = args.entries.associate { (k, v) ->
                    k to (v.jsonPrimitive.contentOrNull ?: v.toString())
                },
                reasoning = "Tool cagiriliyor (inline): $name"
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Markdown code bloklarından artifact çıkar */
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
        else -> "txt"
    }

    /** Trajectory → Ollama messages */
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
                    // Ollama tool call formatı
                    messages.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", step.reasoning)
                        putJsonArray("tool_calls") {
                            add(buildJsonObject {
                                put("function", buildJsonObject {
                                    put("name", step.toolName)
                                    put("arguments", buildJsonObject {
                                        step.toolInput.forEach { (k, v) -> put(k, v) }
                                    })
                                })
                            })
                        }
                    })
                }

                is AgentStep.Observe -> {
                    val resultText = when (step.result) {
                        is ToolResult.Success -> step.result.output
                        is ToolResult.Failure -> "HATA: ${step.result.error}"
                    }
                    messages.add(buildJsonObject {
                        put("role", "tool")
                        put("content", resultText)
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

/**
 * Ollama embedding client.
 * ollama pull nomic-embed-text (veya mxbai-embed-large)
 */
class OllamaEmbeddingClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text"
) : EmbeddingClient {
    private val logger = LoggerFactory.getLogger(OllamaEmbeddingClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun embed(text: String): List<Float> {
        return try {
            val requestBody = buildJsonObject {
                put("model", model)
                put("prompt", text)
            }

            val response = httpClient.post("$baseUrl/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), requestBody))
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["embedding"]?.jsonArray?.map { it.jsonPrimitive.float } ?: emptyList()
        } catch (e: Exception) {
            logger.error("Ollama embedding failed", e)
            emptyList()
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<List<Float>> {
        return texts.map { embed(it) }
    }
}
