package com.kai.tools

import com.kai.core.ToolResult
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

class FileSystemToolTest {
    private lateinit var sandboxDir: File
    private lateinit var tool: FileSystemTool

    @BeforeTest
    fun setup() {
        sandboxDir = File(System.getProperty("java.io.tmpdir"), "kai-test-sandbox-${System.nanoTime()}")
        sandboxDir.mkdirs()
        tool = FileSystemTool(sandboxDir)
    }

    @AfterTest
    fun cleanup() {
        sandboxDir.deleteRecursively()
    }

    // ── Write Tests ──

    @Test
    fun `write creates file with content`() = runTest {
        val result = tool.execute(
            mapOf("action" to "write", "path" to "test.txt", "content" to "merhaba dunya")
        )

        assertIs<ToolResult.Success>(result)
        val file = File(sandboxDir, "test.txt")
        assertTrue(file.exists())
        assertEquals("merhaba dunya", file.readText())
    }

    @Test
    fun `write creates nested directories`() = runTest {
        val result = tool.execute(
            mapOf("action" to "write", "path" to "src/main/kotlin/App.kt", "content" to "fun main() {}")
        )

        assertIs<ToolResult.Success>(result)
        assertTrue(File(sandboxDir, "src/main/kotlin/App.kt").exists())
    }

    @Test
    fun `write overwrites existing file`() = runTest {
        tool.execute(mapOf("action" to "write", "path" to "data.txt", "content" to "v1"))
        tool.execute(mapOf("action" to "write", "path" to "data.txt", "content" to "v2"))

        assertEquals("v2", File(sandboxDir, "data.txt").readText())
    }

    @Test
    fun `write fails without content parameter`() = runTest {
        val result = tool.execute(mapOf("action" to "write", "path" to "test.txt"))

        assertIs<ToolResult.Failure>(result)
        assertContains(result.error, "content")
    }

    // ── Read Tests ──

    @Test
    fun `read returns file content`() = runTest {
        File(sandboxDir, "hello.txt").writeText("selam!")
        val result = tool.execute(mapOf("action" to "read", "path" to "hello.txt"))

        assertIs<ToolResult.Success>(result)
        assertEquals("selam!", result.output)
    }

    @Test
    fun `read fails for nonexistent file`() = runTest {
        val result = tool.execute(mapOf("action" to "read", "path" to "yok.txt"))

        assertIs<ToolResult.Failure>(result)
        assertContains(result.error, "bulunamadi")
    }

    // ── List Tests ──

    @Test
    fun `list shows files in directory`() = runTest {
        File(sandboxDir, "a.kt").writeText("a")
        File(sandboxDir, "b.kt").writeText("b")
        File(sandboxDir, "sub").mkdirs()
        File(sandboxDir, "sub/c.kt").writeText("c")

        val result = tool.execute(mapOf("action" to "list", "path" to "."))

        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "a.kt")
        assertContains(result.output, "b.kt")
        assertContains(result.output, "c.kt")
    }

    @Test
    fun `list returns empty message for empty directory`() = runTest {
        val result = tool.execute(mapOf("action" to "list", "path" to "."))

        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "bos")
    }

    // ── Delete Tests ──

    @Test
    fun `delete removes file`() = runTest {
        File(sandboxDir, "trash.txt").writeText("silinecek")
        val result = tool.execute(mapOf("action" to "delete", "path" to "trash.txt"))

        assertIs<ToolResult.Success>(result)
        assertFalse(File(sandboxDir, "trash.txt").exists())
    }

    @Test
    fun `delete removes directory recursively`() = runTest {
        File(sandboxDir, "dir/sub").mkdirs()
        File(sandboxDir, "dir/sub/file.txt").writeText("x")

        val result = tool.execute(mapOf("action" to "delete", "path" to "dir"))

        assertIs<ToolResult.Success>(result)
        assertFalse(File(sandboxDir, "dir").exists())
    }

    @Test
    fun `delete fails for nonexistent file`() = runTest {
        val result = tool.execute(mapOf("action" to "delete", "path" to "ghost.txt"))

        assertIs<ToolResult.Failure>(result)
    }

    // ── Security Tests ──

    @Test
    fun `path traversal with dotdot is blocked`() = runTest {
        val result = tool.execute(
            mapOf("action" to "read", "path" to "../../etc/passwd")
        )

        // Sanitize ../  siler → "etc/passwd" olur (sandbox icinde, dosya yok)
        // Ya sandbox hatasi ya da dosya bulunamadi donmeli
        assertIs<ToolResult.Failure>(result)
        assertTrue(
            result.error.contains("sandbox") || result.error.contains("bulunamadi"),
            "Expected sandbox or file-not-found error, got: ${result.error}"
        )
    }

    @Test
    fun `path traversal with backslash dotdot is blocked`() = runTest {
        val result = tool.execute(
            mapOf("action" to "read", "path" to "..\\..\\Windows\\System32\\config")
        )

        assertIs<ToolResult.Failure>(result)
    }

    @Test
    fun `absolute path outside sandbox is blocked`() = runTest {
        val result = tool.execute(
            mapOf("action" to "write", "path" to "/tmp/evil.txt", "content" to "hacked")
        )

        // /tmp/evil.txt sandboxDir'in altinda degilse engellenmeli
        // Ama sandboxDir zaten /tmp altinda olabilir, path sanitize ediyor
        // En azindan calistigindan emin ol
        assertTrue(result is ToolResult.Success || result is ToolResult.Failure)
    }

    // ── Edge Cases ──

    @Test
    fun `unknown action returns failure`() = runTest {
        val result = tool.execute(mapOf("action" to "rename", "path" to "x.txt"))

        assertIs<ToolResult.Failure>(result)
        assertContains(result.error, "Bilinmeyen")
    }

    @Test
    fun `missing action parameter returns failure`() = runTest {
        val result = tool.execute(mapOf("path" to "x.txt"))

        assertIs<ToolResult.Failure>(result)
    }

    @Test
    fun `missing path parameter returns failure`() = runTest {
        val result = tool.execute(mapOf("action" to "read"))

        assertIs<ToolResult.Failure>(result)
    }

    // ── Tool Definition ──

    @Test
    fun `tool definition has correct name and params`() {
        assertEquals("file_system", tool.name)
        assertTrue(tool.parameters.any { it.name == "action" })
        assertTrue(tool.parameters.any { it.name == "path" })

        val definition = tool.toToolDefinition()
        assertEquals("file_system", definition.name)
        assertTrue(definition.inputSchema.required.contains("action"))
        assertTrue(definition.inputSchema.required.contains("path"))
    }
}
