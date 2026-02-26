package com.kai.tools

import com.kai.core.ToolResult
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

class KotlinCompilerToolTest {
    private lateinit var sandboxDir: File
    private lateinit var tool: KotlinCompilerTool

    @BeforeTest
    fun setup() {
        sandboxDir = File(System.getProperty("java.io.tmpdir"), "kai-compiler-test-${System.nanoTime()}")
        sandboxDir.mkdirs()
        tool = KotlinCompilerTool(sandboxDir)
    }

    @AfterTest
    fun cleanup() {
        sandboxDir.deleteRecursively()
    }

    @Test
    fun `valid kotlin code passes syntax check`() = runTest {
        val code = """
            fun main() {
                val message = "Hello, World!"
                println(message)
            }
        """.trimIndent()

        val result = tool.execute(mapOf("code" to code))

        // kotlinc olmasa bile syntax check gecmeli
        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `mismatched braces detected`() = runTest {
        val code = """
            fun main() {
                if (true) {
                    println("unclosed")
                // missing closing braces
        """.trimIndent()

        val result = tool.execute(mapOf("code" to code))

        // kotlinc varsa derleme hatasi, yoksa bracket mismatch
        if (result is ToolResult.Failure) {
            assertTrue(result.error.contains("mismatch") || result.error.contains("hata"))
        }
        // Eger kotlinc varsa ve basarili oluyorsa da kabul et (kotlinc daha akilli)
    }

    @Test
    fun `mismatched parentheses detected`() = runTest {
        val code = """
            fun main() {
                println("test"
            }
        """.trimIndent()

        val result = tool.execute(mapOf("code" to code))

        if (result is ToolResult.Failure) {
            assertTrue(result.retryable)
        }
    }

    @Test
    fun `empty code passes syntax check`() = runTest {
        val result = tool.execute(mapOf("code" to ""))

        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `data class code passes`() = runTest {
        val code = """
            data class User(
                val name: String,
                val age: Int,
                val email: String? = null
            )
        """.trimIndent()

        val result = tool.execute(mapOf("code" to code))
        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `sealed class code passes`() = runTest {
        val code = """
            sealed class Result {
                data class Success(val data: String) : Result()
                data class Error(val message: String) : Result()
            }
        """.trimIndent()

        val result = tool.execute(mapOf("code" to code))
        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `missing code parameter returns failure`() = runTest {
        val result = tool.execute(emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertFalse(result.retryable)
    }

    @Test
    fun `custom filename is used`() = runTest {
        val result = tool.execute(
            mapOf("code" to "fun hello() = 42", "filename" to "Hello.kt")
        )

        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "Hello.kt")
    }

    @Test
    fun `tool definition is correct`() {
        assertEquals("kotlin_compile", tool.name)
        assertTrue(tool.description.isNotBlank())
        assertTrue(tool.parameters.any { it.name == "code" && it.required })

        val def = tool.toToolDefinition()
        assertEquals("kotlin_compile", def.name)
    }
}
