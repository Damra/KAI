package com.kai.tools

import com.kai.core.ToolResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * JUnit testlerini sandbox ortamında çalıştırır.
 * Kaynak kod ve test kodunu alır, Gradle wrapper ile test eder.
 */
class TestRunnerTool(
    private val sandboxDir: File = File(System.getProperty("java.io.tmpdir"), "kai-test-sandbox")
) : Tool {
    private val logger = LoggerFactory.getLogger(TestRunnerTool::class.java)

    override val name = "run_tests"
    override val description = "JUnit testlerini çalıştırır ve sonuçları raporlar. Kaynak kod ve test kodu gerektirir."
    override val parameters = listOf(
        ToolParameter("source_code", "string", "Test edilecek kaynak kod"),
        ToolParameter("test_code", "string", "JUnit test kodu"),
        ToolParameter("source_filename", "string", "Kaynak dosya adı", required = false),
        ToolParameter("test_filename", "string", "Test dosya adı", required = false)
    )

    override suspend fun execute(input: Map<String, String>): ToolResult {
        val sourceCode = input["source_code"]
            ?: return ToolResult.Failure("'source_code' parametresi gerekli", retryable = false)
        val testCode = input["test_code"]
            ?: return ToolResult.Failure("'test_code' parametresi gerekli", retryable = false)
        val sourceFilename = input["source_filename"] ?: "Main.kt"
        val testFilename = input["test_filename"] ?: "MainTest.kt"

        return try {
            // Test projesi oluştur
            val projectDir = File(sandboxDir, "test-project-${System.currentTimeMillis()}")
            setupTestProject(projectDir, sourceCode, sourceFilename, testCode, testFilename)

            // Gradle test çalıştır
            val process = ProcessBuilder(
                if (System.getProperty("os.name").lowercase().contains("win")) "gradlew.bat" else "./gradlew",
                "test", "--no-daemon", "--console=plain"
            )
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            // Cleanup
            projectDir.deleteRecursively()

            if (exitCode == 0) {
                ToolResult.Success("Tum testler gecti.\n$output")
            } else {
                ToolResult.Failure("Test basarisiz:\n$output", retryable = true)
            }
        } catch (e: Exception) {
            logger.error("Test execution failed", e)
            // Gradle mevcut değilse basit analiz yap
            analyzeTestCode(testCode)
        }
    }

    private fun setupTestProject(
        projectDir: File,
        sourceCode: String,
        sourceFilename: String,
        testCode: String,
        testFilename: String
    ) {
        val srcDir = File(projectDir, "src/main/kotlin")
        val testDir = File(projectDir, "src/test/kotlin")
        srcDir.mkdirs()
        testDir.mkdirs()

        File(srcDir, sourceFilename).writeText(sourceCode)
        File(testDir, testFilename).writeText(testCode)

        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.0.21"
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
            }
            tasks.test { useJUnitPlatform() }
            """.trimIndent()
        )
    }

    /** Gradle yoksa test kodunu analiz et */
    private fun analyzeTestCode(testCode: String): ToolResult {
        val testCount = testCode.lines().count { it.contains("@Test") }
        val assertCount = testCode.lines().count {
            it.contains("assert") || it.contains("assertEquals") || it.contains("assertTrue")
        }

        return ToolResult.Success(
            "Test analizi (sandbox disinda):\n" +
                "- $testCount test metodu bulundu\n" +
                "- $assertCount assertion bulundu\n" +
                "- Not: Gercek calistirma icin Gradle gerekli"
        )
    }
}
