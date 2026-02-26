package com.kai.llm

import com.kai.core.AgentStep
import com.kai.tools.ToolDefinition

/**
 * LLM provider interface.
 * Anthropic, OpenAI veya başka bir LLM ile değiştirilebilir.
 */
interface LLMClient {
    /** Agent reasoning: trajectory'den sonraki adımı üret */
    suspend fun reason(
        system: String,
        trajectory: List<AgentStep>,
        availableTools: List<ToolDefinition>
    ): AgentStep

    /** Basit sohbet: system + user → text cevap */
    suspend fun chat(system: String, user: String): String
}

/**
 * Embedding client — metin vektörlerine dönüştürme.
 * Episodic memory'de kullanılır.
 */
interface EmbeddingClient {
    suspend fun embed(text: String): List<Float>
    suspend fun embedBatch(texts: List<String>): List<List<Float>>
}
