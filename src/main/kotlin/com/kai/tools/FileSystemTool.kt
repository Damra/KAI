package com.kai.tools

import com.kai.core.ToolResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Proje dosyalarını oku, yaz, listele.
 * Path traversal koruması ile sandbox içinde çalışır.
 */
class FileSystemTool(
    private val sandboxDir: File
) : Tool {
    private val logger = LoggerFactory.getLogger(FileSystemTool::class.java)

    override val name = "file_system"
    override val description = "Proje dosyalarını oku, yaz veya listele. Sandbox dizini içinde çalışır."
    override val parameters = listOf(
        ToolParameter("action", "string", "Yapılacak işlem: read | write | list | delete"),
        ToolParameter("path", "string", "Dosya veya dizin yolu (sandbox'a göre relatif)"),
        ToolParameter("content", "string", "Yazılacak içerik (sadece write için)", required = false)
    )

    override suspend fun execute(input: Map<String, String>): ToolResult {
        val action = input["action"] ?: return ToolResult.Failure("'action' parametresi gerekli", retryable = false)
        val path = input["path"] ?: return ToolResult.Failure("'path' parametresi gerekli", retryable = false)

        val sanitized = sanitizePath(path)
            ?: return ToolResult.Failure("Geçersiz path: $path (sandbox dışına erişim engellendi)", retryable = false)

        val targetFile = File(sandboxDir, sanitized)

        return try {
            when (action.lowercase()) {
                "read" -> {
                    if (!targetFile.exists()) {
                        ToolResult.Failure("Dosya bulunamadi: $sanitized", retryable = false)
                    } else if (targetFile.length() > 1_000_000) {
                        ToolResult.Failure("Dosya çok büyük (>1MB): $sanitized", retryable = false)
                    } else {
                        ToolResult.Success(targetFile.readText())
                    }
                }

                "write" -> {
                    val content = input["content"]
                        ?: return ToolResult.Failure("'content' parametresi gerekli (write)", retryable = false)
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeText(content)
                    ToolResult.Success("Dosya yazildi: $sanitized (${content.length} karakter)")
                }

                "list" -> {
                    val dir = if (targetFile.isDirectory) targetFile else targetFile.parentFile ?: sandboxDir
                    if (!dir.exists()) {
                        ToolResult.Failure("Dizin bulunamadi: $sanitized", retryable = false)
                    } else {
                        val listing = dir.walkTopDown()
                            .filter { it.isFile }
                            .take(200)
                            .joinToString("\n") { it.relativeTo(sandboxDir).path }
                        ToolResult.Success(listing.ifEmpty { "(bos dizin)" })
                    }
                }

                "delete" -> {
                    if (!targetFile.exists()) {
                        ToolResult.Failure("Dosya bulunamadi: $sanitized", retryable = false)
                    } else {
                        val deleted = targetFile.deleteRecursively()
                        if (deleted) ToolResult.Success("Silindi: $sanitized")
                        else ToolResult.Failure("Silinemedi: $sanitized", retryable = true)
                    }
                }

                else -> ToolResult.Failure("Bilinmeyen action: $action (read|write|list|delete)", retryable = false)
            }
        } catch (e: Exception) {
            logger.error("FileSystem operation failed: $action $sanitized", e)
            ToolResult.Failure("I/O hatasi: ${e.message}", retryable = true)
        }
    }

    /** Path traversal saldırılarını engeller */
    private fun sanitizePath(path: String): String? {
        val normalized = path
            .replace("\\", "/")
            .replace("../", "")
            .replace("..\\", "")
            .removePrefix("/")

        val resolved = File(sandboxDir, normalized).canonicalFile
        return if (resolved.startsWith(sandboxDir.canonicalFile)) {
            normalized
        } else {
            logger.warn("Path traversal attempt blocked: $path -> $resolved")
            null
        }
    }
}
