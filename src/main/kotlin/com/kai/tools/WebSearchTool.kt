package com.kai.tools

import com.kai.core.ToolResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.slf4j.LoggerFactory

/**
 * Web'de dokümantasyon araması yapar.
 * Tavily API veya benzeri bir search API kullanır.
 */
class WebSearchTool(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.tavily.com"
) : Tool {
    private val logger = LoggerFactory.getLogger(WebSearchTool::class.java)

    override val name = "web_search"
    override val description = "Kotlin/KTOR dokümantasyonu ve API referansları arar. Teknik sorular için kullanılır."
    override val parameters = listOf(
        ToolParameter("query", "string", "Arama sorgusu"),
        ToolParameter("max_results", "string", "Maksimum sonuç sayısı (varsayılan: 5)", required = false)
    )

    override suspend fun execute(input: Map<String, String>): ToolResult {
        val query = input["query"] ?: return ToolResult.Failure("'query' parametresi gerekli", retryable = false)
        val maxResults = input["max_results"]?.toIntOrNull() ?: 5

        return try {
            val response = httpClient.get("$baseUrl/search") {
                parameter("api_key", apiKey)
                parameter("query", "$query Kotlin")
                parameter("max_results", maxResults)
                parameter("search_depth", "advanced")
                parameter("include_answer", true)
            }

            val body = response.bodyAsText()

            if (response.status.value in 200..299) {
                ToolResult.Success(body)
            } else {
                ToolResult.Failure("Search API hatasi (${response.status}): $body", retryable = true)
            }
        } catch (e: Exception) {
            logger.error("Web search failed for query: $query", e)
            ToolResult.Failure("Arama hatasi: ${e.message}", retryable = true)
        }
    }
}
