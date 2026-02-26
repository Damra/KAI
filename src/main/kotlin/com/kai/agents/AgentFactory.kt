package com.kai.agents

import com.kai.core.*
import com.kai.llm.LLMClient
import com.kai.memory.MemoryLayer
import com.kai.tools.*
import io.ktor.client.*
import java.io.File

/**
 * Agent'ları oluşturan factory.
 * Her role için uygun tool seti ve config ile ReActAgent üretir.
 */
class AgentFactory(
    private val llmClient: LLMClient,
    private val memory: MemoryLayer,
    private val httpClient: HttpClient,
    private val sandboxDir: File = File(System.getProperty("java.io.tmpdir"), "kai-sandbox"),
    private val webSearchApiKey: String = ""
) {
    fun createAll(): Map<AgentRole, ReActAgent> {
        return mapOf(
            AgentRole.PLANNER to createPlanner(),
            AgentRole.CODE_WRITER to createCodeWriter(),
            AgentRole.REVIEWER to createReviewer(),
            AgentRole.FIXER to createFixer(),
            AgentRole.TESTER to createTester(),
            AgentRole.RESEARCHER to createResearcher()
        )
    }

    fun createPlanner(): ReActAgent = ReActAgent(
        role = AgentRole.PLANNER,
        llmClient = llmClient,
        tools = mapOf(
            "file_system" to FileSystemTool(sandboxDir)
        ),
        memory = memory,
        config = ReActAgent.AgentConfig(
            maxIterations = 5,
            confidenceThreshold = 0.6
        )
    )

    fun createCodeWriter(): ReActAgent = ReActAgent(
        role = AgentRole.CODE_WRITER,
        llmClient = llmClient,
        tools = mapOf(
            "file_system" to FileSystemTool(sandboxDir),
            "kotlin_compile" to KotlinCompilerTool(sandboxDir),
            "web_search" to WebSearchTool(httpClient, webSearchApiKey)
        ),
        memory = memory,
        config = ReActAgent.AgentConfig(
            maxIterations = 12,
            confidenceThreshold = 0.7
        )
    )

    fun createReviewer(): ReActAgent = ReActAgent(
        role = AgentRole.REVIEWER,
        llmClient = llmClient,
        tools = mapOf(
            "file_system" to FileSystemTool(sandboxDir),
            "kotlin_compile" to KotlinCompilerTool(sandboxDir)
        ),
        memory = memory,
        config = ReActAgent.AgentConfig(
            maxIterations = 6,
            confidenceThreshold = 0.8
        )
    )

    fun createFixer(): ReActAgent = ReActAgent(
        role = AgentRole.FIXER,
        llmClient = llmClient,
        tools = mapOf(
            "file_system" to FileSystemTool(sandboxDir),
            "kotlin_compile" to KotlinCompilerTool(sandboxDir)
        ),
        memory = memory,
        config = ReActAgent.AgentConfig(
            maxIterations = 8,
            maxRetries = 5,
            confidenceThreshold = 0.7
        )
    )

    fun createTester(): ReActAgent = ReActAgent(
        role = AgentRole.TESTER,
        llmClient = llmClient,
        tools = mapOf(
            "file_system" to FileSystemTool(sandboxDir),
            "kotlin_compile" to KotlinCompilerTool(sandboxDir),
            "run_tests" to TestRunnerTool(sandboxDir)
        ),
        memory = memory,
        config = ReActAgent.AgentConfig(
            maxIterations = 8,
            confidenceThreshold = 0.7
        )
    )

    fun createResearcher(): ReActAgent = ReActAgent(
        role = AgentRole.RESEARCHER,
        llmClient = llmClient,
        tools = mapOf(
            "web_search" to WebSearchTool(httpClient, webSearchApiKey),
            "file_system" to FileSystemTool(sandboxDir)
        ),
        memory = memory,
        config = ReActAgent.AgentConfig(
            maxIterations = 8,
            confidenceThreshold = 0.6
        )
    )
}
