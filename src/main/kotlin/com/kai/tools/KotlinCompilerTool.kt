package com.kai.tools

import com.kai.core.ToolResult
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kotlin kodunu derleyip hata kontrolü yapar.
 * Sandbox ortamında (temp directory) çalışır.
 */
class KotlinCompilerTool(
    private val sandboxDir: File = File(System.getProperty("java.io.tmpdir"), "kai-sandbox")
) : Tool {
    private val logger = LoggerFactory.getLogger(KotlinCompilerTool::class.java)

    override val name = "kotlin_compile"
    override val description = "Kotlin kodunu derler ve hataları döndürür. Derleme başarılıysa onay, değilse hata mesajı verir."
    override val parameters = listOf(
        ToolParameter("code", "string", "Derlenecek Kotlin kodu"),
        ToolParameter("filename", "string", "Dosya adı (ör: Main.kt)", required = false)
    )

    override suspend fun execute(input: Map<String, String>): ToolResult {
        val code = input["code"] ?: return ToolResult.Failure("'code' parametresi gerekli", retryable = false)
        val filename = input["filename"] ?: "Main.kt"

        return try {
            sandboxDir.mkdirs()
            val sourceFile = File(sandboxDir, filename)
            sourceFile.writeText(code)

            val process = ProcessBuilder(
                "kotlinc", sourceFile.absolutePath, "-d", File(sandboxDir, "out").absolutePath
            )
                .directory(sandboxDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ToolResult.Success("Derleme basarili: $filename")
            } else {
                ToolResult.Failure("Derleme hatasi:\n$output", retryable = true)
            }
        } catch (e: Exception) {
            logger.error("Kotlin compile failed", e)
            // kotlinc yoksa syntax check moduna düş
            syntaxCheck(code, filename)
        } finally {
            // Cleanup
            File(sandboxDir, "out").deleteRecursively()
        }
    }

    /** kotlinc mevcut değilse basit syntax kontrolü */
    private fun syntaxCheck(code: String, filename: String): ToolResult {
        val issues = mutableListOf<String>()

        // Basit bracket matching
        val openBraces = code.count { it == '{' }
        val closeBraces = code.count { it == '}' }
        if (openBraces != closeBraces) {
            issues.add("Bracket mismatch: { = $openBraces, } = $closeBraces")
        }

        val openParens = code.count { it == '(' }
        val closeParens = code.count { it == ')' }
        if (openParens != closeParens) {
            issues.add("Parenthesis mismatch: ( = $openParens, ) = $closeParens")
        }

        return if (issues.isEmpty()) {
            ToolResult.Success("Syntax check basarili (tam derleme yapılamadi, kotlinc bulunamadi): $filename")
        } else {
            ToolResult.Failure("Syntax hatalari:\n${issues.joinToString("\n")}", retryable = true)
        }
    }
}
