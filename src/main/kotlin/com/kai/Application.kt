package com.kai

import com.kai.agents.AgentFactory
import com.kai.core.MetaController
import com.kai.core.VerificationGate
import com.kai.llm.*
import com.kai.memory.DualMemory
import com.kai.memory.Neo4jStore
import com.kai.memory.PgVectorStore
import com.kai.pipeline.PipelineOrchestrator
import com.kai.pipeline.ProjectAnalyzer
import com.kai.pipeline.TaskStore
import com.kai.routes.agentRoutes
import com.kai.routes.pipelineRoutes
import com.kai.routes.webSocketRoutes
import com.kai.tools.GitHubTool
import com.kai.tools.KotlinCompilerTool
import com.kai.tools.TestRunnerTool
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration

private val logger = LoggerFactory.getLogger("KAI")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"

    logger.info("Starting KAI Agent Server on $host:$port")

    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    // ── Plugins ──
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Bilinmeyen hata"))
            )
        }
    }

    install(Authentication) {
        bearer("api-key") {
            authenticate { tokenCredential ->
                val expectedKey = System.getenv("KAI_API_KEY") ?: "dev-key"
                if (tokenCredential.token == expectedKey) {
                    UserIdPrincipal("kai-user")
                } else {
                    null
                }
            }
        }
    }

    // ── Dependencies ──
    val config = AppConfig.fromEnv()
    val httpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }
    }

    // ── LLM Client: Ollama (lokal) veya Anthropic (cloud) ──
    val llmClient: LLMClient = when {
        config.llmProvider == "ollama" || (config.anthropicApiKey.isBlank() && isOllamaRunning(config.ollamaUrl)) -> {
            logger.info("Using Ollama (${config.ollamaModel}) at ${config.ollamaUrl}")
            OllamaClient(
                httpClient = httpClient,
                baseUrl = config.ollamaUrl,
                model = config.ollamaModel
            )
        }
        config.anthropicApiKey.isNotBlank() -> {
            logger.info("Using Anthropic (${config.llmModel})")
            AnthropicClient(
                httpClient = httpClient,
                apiKey = config.anthropicApiKey,
                model = config.llmModel
            )
        }
        else -> {
            logger.warn("No LLM configured! Set ANTHROPIC_API_KEY or install Ollama.")
            logger.warn("Install Ollama: https://ollama.com -> ollama pull qwen2.5-coder:7b -> ollama serve")
            AnthropicClient(httpClient = httpClient, apiKey = "", model = config.llmModel)
        }
    }

    // ── Embedding Client ──
    val embeddingClient: EmbeddingClient = when {
        config.llmProvider == "ollama" || (config.voyageApiKey.isBlank() && isOllamaRunning(config.ollamaUrl)) -> {
            logger.info("Using Ollama embeddings (nomic-embed-text)")
            OllamaEmbeddingClient(httpClient, config.ollamaUrl)
        }
        config.voyageApiKey.isNotBlank() -> {
            VoyageEmbeddingClient(httpClient, config.voyageApiKey)
        }
        else -> {
            logger.warn("No embedding provider, using MockEmbeddingClient")
            MockEmbeddingClient()
        }
    }

    // Database connections (graceful fallback)
    val memory = createMemoryLayer(config, embeddingClient)

    val sandboxDir = File(config.sandboxPath).also { it.mkdirs() }

    val agentFactory = AgentFactory(
        llmClient = llmClient,
        memory = memory,
        httpClient = httpClient,
        sandboxDir = sandboxDir,
        webSearchApiKey = config.tavilyApiKey
    )

    val agents = agentFactory.createAll()

    val verificationGate = VerificationGate(
        llmClient = llmClient,
        compilerTool = KotlinCompilerTool(sandboxDir),
        testRunnerTool = TestRunnerTool(sandboxDir)
    )

    val metaController = MetaController(
        agents = agents,
        llmClient = llmClient,
        memory = memory,
        verificationGate = verificationGate
    )

    // ── Pipeline (Phase 2) ──
    val gitHubTool = GitHubTool(sandboxDir)
    val pipelineComponents = initializePipeline(config, llmClient, metaController, gitHubTool)

    // ── Routes ──
    routing {
        // Public health check
        agentRoutes(metaController)

        // WebSocket — auth via query param (browsers can't send custom headers on WS)
        webSocketRoutes(metaController)

        // Pipeline REST API
        if (pipelineComponents != null) {
            pipelineRoutes(pipelineComponents.first, pipelineComponents.second)
        }

        // Authenticated REST endpoints
        authenticate("api-key") {
            // Future authenticated REST routes go here
        }
    }

    logger.info("KAI Agent Server ready. Agents: ${agents.keys}")
}

/**
 * Initialize the pipeline system (TaskStore, ProjectAnalyzer, PipelineOrchestrator).
 * Returns null if database is not configured.
 */
private fun initializePipeline(
    config: AppConfig,
    llmClient: LLMClient,
    metaController: MetaController,
    gitHubTool: GitHubTool
): Pair<TaskStore, PipelineOrchestrator>? {
    if (config.postgresUrl.isBlank()) {
        logger.info("Pipeline disabled: no database configured")
        return null
    }

    return try {
        val database = Database.connect(
            url = config.postgresUrl,
            driver = "org.postgresql.Driver",
            user = config.postgresUser,
            password = config.postgresPassword
        )

        val taskStore = TaskStore(database)
        kotlinx.coroutines.runBlocking { taskStore.initialize() }

        val projectAnalyzer = ProjectAnalyzer(llmClient, taskStore)
        val orchestrator = PipelineOrchestrator(taskStore, projectAnalyzer, gitHubTool, metaController)

        logger.info("Pipeline system initialized")
        Pair(taskStore, orchestrator)
    } catch (e: Exception) {
        logger.warn("Pipeline initialization failed (continuing without pipeline): ${e.message}")
        null
    }
}

private fun createMemoryLayer(
    config: AppConfig,
    embeddingClient: com.kai.llm.EmbeddingClient
): com.kai.memory.MemoryLayer {
    // API key yoksa veya DB ayarlanmamissa direkt in-memory kullan
    if (config.postgresUrl.isBlank()) {
        logger.info("No database configured, using in-memory memory layer")
        return InMemoryMemoryLayer()
    }

    return try {
        val database = Database.connect(
            url = config.postgresUrl,
            driver = "org.postgresql.Driver",
            user = config.postgresUser,
            password = config.postgresPassword
        )

        // Eager connection test — lazy olmadan hatayi burada yakala
        org.jetbrains.exposed.sql.transactions.transaction(database) {
            exec("SELECT 1")
        }

        val pgVectorStore = PgVectorStore(database)
        kotlinx.coroutines.runBlocking { pgVectorStore.initialize() }

        val neo4jDriver = GraphDatabase.driver(
            config.neo4jUrl,
            AuthTokens.basic(config.neo4jUser, config.neo4jPassword)
        )
        neo4jDriver.verifyConnectivity()
        val neo4jStore = Neo4jStore(neo4jDriver)

        logger.info("Connected to PostgreSQL and Neo4j")
        DualMemory(pgVectorStore, neo4jStore, embeddingClient)
    } catch (e: Exception) {
        logger.warn("Database connection failed, using in-memory fallback: ${e.message}")
        InMemoryMemoryLayer()
    }
}

/**
 * Veritabanı olmadan çalışmak için basit in-memory memory layer.
 */
private class InMemoryMemoryLayer : com.kai.memory.MemoryLayer {
    private val episodes = mutableListOf<com.kai.memory.Episode>()
    private val facts = mutableListOf<com.kai.memory.Fact>()
    private val sessions = mutableMapOf<String, String>()

    override suspend fun recallSimilar(query: String, limit: Int): List<com.kai.memory.Episode> {
        return episodes.takeLast(limit)
    }

    override suspend fun queryGraph(entities: List<String>): com.kai.memory.GraphContext {
        val related = facts.filter { it.subject in entities || it.objectEntity in entities }
        return com.kai.memory.GraphContext(related, entities)
    }

    override suspend fun storeEpisode(
        task: com.kai.core.AgentTask,
        trajectory: List<com.kai.core.AgentStep>,
        outcome: com.kai.core.AgentStep.Answer
    ) {
        episodes.add(
            com.kai.memory.Episode(
                taskDescription = task.description,
                trajectorySummary = trajectory.size.toString() + " steps",
                outcomeScore = 0.7,
                artifacts = outcome.artifacts
            )
        )
        // Max 100 episode tut
        while (episodes.size > 100) episodes.removeFirst()
    }

    override suspend fun updateGraph(newFacts: List<com.kai.memory.Fact>) {
        facts.addAll(newFacts)
    }

    override suspend fun getSessionContext(sessionId: String): String {
        return sessions.getOrDefault(sessionId, "")
    }

    override suspend fun updateSessionContext(sessionId: String, context: String) {
        sessions[sessionId] = context
    }
}

/**
 * Uygulama konfigürasyonu — environment variable'lardan okunur.
 */
data class AppConfig(
    val llmProvider: String,
    val anthropicApiKey: String,
    val llmModel: String,
    val ollamaUrl: String,
    val ollamaModel: String,
    val voyageApiKey: String,
    val tavilyApiKey: String,
    val postgresUrl: String,
    val postgresUser: String,
    val postgresPassword: String,
    val neo4jUrl: String,
    val neo4jUser: String,
    val neo4jPassword: String,
    val sandboxPath: String
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig(
            llmProvider = System.getenv("LLM_PROVIDER") ?: "auto",
            anthropicApiKey = System.getenv("ANTHROPIC_API_KEY") ?: "",
            llmModel = System.getenv("LLM_MODEL") ?: "claude-sonnet-4-5-20250929",
            ollamaUrl = System.getenv("OLLAMA_URL") ?: "http://localhost:11434",
            ollamaModel = System.getenv("OLLAMA_MODEL") ?: "qwen2.5-coder:7b",
            voyageApiKey = System.getenv("VOYAGE_API_KEY") ?: "",
            tavilyApiKey = System.getenv("TAVILY_API_KEY") ?: "",
            postgresUrl = System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/kai",
            postgresUser = System.getenv("POSTGRES_USER") ?: "kai",
            postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: "kai",
            neo4jUrl = System.getenv("NEO4J_URL") ?: "bolt://localhost:7687",
            neo4jUser = System.getenv("NEO4J_USER") ?: "neo4j",
            neo4jPassword = System.getenv("NEO4J_PASSWORD") ?: "kai",
            sandboxPath = System.getenv("SANDBOX_PATH")
                ?: System.getProperty("java.io.tmpdir") + "/kai-sandbox"
        )
    }
}

/** Ollama servisinin çalışıp çalışmadığını kontrol et */
private fun isOllamaRunning(baseUrl: String): Boolean {
    return try {
        val url = java.net.URL("$baseUrl/api/version")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.responseCode == 200
    } catch (_: Exception) {
        false
    }
}
