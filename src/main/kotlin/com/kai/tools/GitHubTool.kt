package com.kai.tools

import com.kai.core.ToolResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * GitHub/Git tool that executes git and gh CLI commands.
 * Actions: create_branch, commit_files, create_pr, list_prs, merge_pr, pr_status
 */
class GitHubTool(
    private val defaultWorkDir: File = File(".")
) : Tool {
    private val logger = LoggerFactory.getLogger(GitHubTool::class.java)

    override val name = "github"
    override val description = "Git/GitHub operations: create branches, commit, create PRs, merge, check status"
    override val parameters = listOf(
        ToolParameter("action", "string", "Action: create_branch, commit_files, create_pr, list_prs, merge_pr, pr_status", required = true),
        ToolParameter("branch_name", "string", "Branch name (for create_branch)", required = false),
        ToolParameter("message", "string", "Commit message or PR title", required = false),
        ToolParameter("body", "string", "PR body or commit description", required = false),
        ToolParameter("base_branch", "string", "Base branch for PR (default: main)", required = false),
        ToolParameter("files", "string", "Comma-separated file paths to add (for commit_files)", required = false),
        ToolParameter("pr_number", "string", "PR number (for merge_pr, pr_status)", required = false),
        ToolParameter("work_dir", "string", "Working directory (repo path)", required = false)
    )

    override suspend fun execute(input: Map<String, String>): ToolResult {
        val action = input["action"] ?: return ToolResult.Failure("action is required", retryable = false)
        val workDir = input["work_dir"]?.let { File(it) } ?: defaultWorkDir

        if (!workDir.exists()) {
            return ToolResult.Failure("Working directory does not exist: ${workDir.absolutePath}", retryable = false)
        }

        return try {
            when (action) {
                "create_branch" -> createBranch(input, workDir)
                "commit_files" -> commitFiles(input, workDir)
                "create_pr" -> createPr(input, workDir)
                "list_prs" -> listPrs(workDir)
                "merge_pr" -> mergePr(input, workDir)
                "pr_status" -> prStatus(input, workDir)
                else -> ToolResult.Failure("Unknown action: $action. Valid: create_branch, commit_files, create_pr, list_prs, merge_pr, pr_status", retryable = false)
            }
        } catch (e: Exception) {
            logger.error("GitHub tool error: ${e.message}", e)
            ToolResult.Failure("GitHub tool error: ${e.message}", retryable = true)
        }
    }

    private fun createBranch(input: Map<String, String>, workDir: File): ToolResult {
        val branchName = input["branch_name"] ?: return ToolResult.Failure("branch_name is required", retryable = false)
        val result = runCommand(listOf("git", "checkout", "-b", branchName), workDir)
        if (result.exitCode != 0) return ToolResult.Failure("Failed to create branch: ${result.stderr}", retryable = true)

        val pushResult = runCommand(listOf("git", "push", "-u", "origin", branchName), workDir)
        return if (pushResult.exitCode == 0) {
            ToolResult.Success("Branch '$branchName' created and pushed", data = branchName)
        } else {
            // Branch created locally even if push fails
            ToolResult.Success("Branch '$branchName' created locally (push failed: ${pushResult.stderr})", data = branchName)
        }
    }

    private fun commitFiles(input: Map<String, String>, workDir: File): ToolResult {
        val message = input["message"] ?: return ToolResult.Failure("message is required", retryable = false)
        val files = input["files"]?.split(",")?.map { it.trim() } ?: listOf(".")

        for (file in files) {
            runCommand(listOf("git", "add", file), workDir)
        }

        val result = runCommand(listOf("git", "commit", "-m", message), workDir)
        return if (result.exitCode == 0) {
            val pushResult = runCommand(listOf("git", "push"), workDir)
            ToolResult.Success("Committed: $message${if (pushResult.exitCode == 0) " (pushed)" else " (push failed)"}")
        } else {
            ToolResult.Failure("Commit failed: ${result.stderr}", retryable = true)
        }
    }

    private fun createPr(input: Map<String, String>, workDir: File): ToolResult {
        val title = input["message"] ?: return ToolResult.Failure("message (PR title) is required", retryable = false)
        val body = input["body"] ?: ""
        val baseBranch = input["base_branch"] ?: "main"

        val cmd = mutableListOf("gh", "pr", "create", "--title", title, "--body", body, "--base", baseBranch)
        val result = runCommand(cmd, workDir)

        return if (result.exitCode == 0) {
            val prUrl = result.stdout.trim()
            ToolResult.Success("PR created: $prUrl", data = prUrl)
        } else {
            ToolResult.Failure("Failed to create PR: ${result.stderr}", retryable = true)
        }
    }

    private fun listPrs(workDir: File): ToolResult {
        val result = runCommand(listOf("gh", "pr", "list", "--json", "number,title,state,url"), workDir)
        return if (result.exitCode == 0) {
            ToolResult.Success(result.stdout)
        } else {
            ToolResult.Failure("Failed to list PRs: ${result.stderr}", retryable = true)
        }
    }

    private fun mergePr(input: Map<String, String>, workDir: File): ToolResult {
        val prNumber = input["pr_number"] ?: return ToolResult.Failure("pr_number is required", retryable = false)
        val result = runCommand(listOf("gh", "pr", "merge", prNumber, "--squash", "--delete-branch"), workDir)
        return if (result.exitCode == 0) {
            ToolResult.Success("PR #$prNumber merged")
        } else {
            ToolResult.Failure("Failed to merge PR: ${result.stderr}", retryable = true)
        }
    }

    private fun prStatus(input: Map<String, String>, workDir: File): ToolResult {
        val prNumber = input["pr_number"] ?: return ToolResult.Failure("pr_number is required", retryable = false)
        val result = runCommand(
            listOf("gh", "pr", "view", prNumber, "--json", "number,title,state,url,reviews,statusCheckRollup"),
            workDir
        )
        return if (result.exitCode == 0) {
            ToolResult.Success(result.stdout)
        } else {
            ToolResult.Failure("Failed to get PR status: ${result.stderr}", retryable = true)
        }
    }

    // ── Process execution helper ──

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runCommand(command: List<String>, workDir: File): CommandResult {
        logger.debug("Running: ${command.joinToString(" ")} in ${workDir.absolutePath}")
        return try {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                logger.warn("Command failed (exit=$exitCode): ${command.joinToString(" ")}\nstderr: $stderr")
            }

            CommandResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            logger.error("Failed to execute command: ${command.joinToString(" ")}", e)
            CommandResult(-1, "", e.message ?: "Unknown error")
        }
    }
}
