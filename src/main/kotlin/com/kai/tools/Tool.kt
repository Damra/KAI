package com.kai.tools

import com.kai.core.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Tüm araçların implement etmesi gereken interface.
 * Her tool kendi parametrelerini ve açıklamasını tanımlar.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: List<ToolParameter>

    suspend fun execute(input: Map<String, String>): ToolResult

    /** Anthropic API tool_use formatına dönüştürür */
    fun toToolDefinition(): ToolDefinition {
        return ToolDefinition(
            name = name,
            description = description,
            inputSchema = InputSchema(
                type = "object",
                properties = parameters.associate { param ->
                    param.name to PropertyDef(
                        type = param.type,
                        description = param.description
                    )
                },
                required = parameters.filter { it.required }.map { it.name }
            )
        )
    }
}

data class ToolParameter(
    val name: String,
    val type: String = "string",
    val description: String,
    val required: Boolean = true
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: InputSchema
)

@Serializable
data class InputSchema(
    val type: String = "object",
    val properties: Map<String, PropertyDef>,
    val required: List<String> = emptyList()
)

@Serializable
data class PropertyDef(
    val type: String,
    val description: String
)
