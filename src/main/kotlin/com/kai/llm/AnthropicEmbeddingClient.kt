package com.kai.llm

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Embedding client — Voyage AI veya OpenAI embedding API.
 * Anthropic'in kendi embedding'i olmadığı için harici servis kullanılır.
 *
 * Not: Voyage AI, Anthropic'in önerdiği embedding servisidir.
 */
class VoyageEmbeddingClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "voyage-code-3",
    private val dimensions: Int = 1536
) : EmbeddingClient {
    private val logger = LoggerFactory.getLogger(VoyageEmbeddingClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val apiUrl = "https://api.voyageai.com/v1/embeddings"

    override suspend fun embed(text: String): List<Float> {
        return embedBatch(listOf(text)).first()
    }

    override suspend fun embedBatch(texts: List<String>): List<List<Float>> {
        val requestBody = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                texts.forEach { add(it.take(8000)) } // Max input truncation
            }
        }

        return try {
            val response = httpClient.post(apiUrl) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), requestBody))
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]?.jsonArray ?: return texts.map { zeroVector() }

            data.map { item ->
                item.jsonObject["embedding"]?.jsonArray?.map {
                    it.jsonPrimitive.float
                } ?: zeroVector()
            }
        } catch (e: Exception) {
            logger.error("Embedding failed", e)
            texts.map { zeroVector() }
        }
    }

    private fun zeroVector(): List<Float> = List(dimensions) { 0f }
}

/**
 * Geliştirme/test için sahte embedding client.
 * Basit hash tabanlı deterministik vektörler üretir.
 */
class MockEmbeddingClient(private val dimensions: Int = 1536) : EmbeddingClient {
    override suspend fun embed(text: String): List<Float> {
        // Deterministik hash tabanlı sahte embedding
        val hash = text.hashCode()
        return List(dimensions) { i ->
            ((hash.toLong() * (i + 1) % 1000) / 1000f)
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<List<Float>> {
        return texts.map { embed(it) }
    }
}
