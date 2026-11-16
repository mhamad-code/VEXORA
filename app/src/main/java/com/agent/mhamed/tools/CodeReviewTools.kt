package com.agent.mhamed.tools

import com.agent.mhamed.data.AppSettings
import com.agent.mhamed.data.env.LinuxEnvironmentManager
import com.agent.mhamed.data.env.ShellTierRouter
import com.agent.mhamed.data.CodeReviewLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Deterministic secret scanner + review card for code changes before commit/push.
 * Values are never returned: only the secret type and its location, with the
 * value redacted. The AI review itself is delegated to a read-only subagent
 * (task tool) using the redacted diff this tool emits, so it reuses the
 * existing subagent provider/model and cost accounting.
 */
object SecretScanner {
    data class Finding(
        val kind: String,
        val severity: String, // Blocker / Warning / Note
        val file: String,
        val line: Int,
        val hint: String,
    )

    private val patterns: List<Triple<String, Regex, String>> = listOf(
        Triple("GitHub token", Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""), "Blocker"),
        Triple("GitHub PAT (classic)", Regex("""ghp_[A-Za-z0-9]{36,}"""), "Blocker"),
        Triple("AWS access key", Regex("""AKIA[0-9A-Z]{16}"""), "Blocker"),
        Triple("AWS secret key", Regex("""(?i)aws_secret_access_key\s*[:=]\s*['\"]?[A-Za-z0-9/+=]{30,}"""), "Blocker"),
        Triple("Google API key", Regex("""AIza[0-9A-Za-z\-_]{35}"""), "Blocker"),
        Triple("Slack token", Regex("""xox[baprs]-[0-9A-Za-z-]{10,}"""), "Blocker"),
        Triple("Stripe key", Regex("""sk_(live|test)_[0-9a-zA-Z]{20,}"""), "Blocker"),
        Triple("Private key block", Regex("""-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"""), "Blocker"),
        Triple("JWT", Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"""), "Blocker"),
        Triple("Password assignment", Regex("""(?i)(password|passwd|pwd)\s*[:=]\s*['\"][^'\"]{6,}['\"]"""), "Blocker"),
        Triple("Generic secret assignment", Regex("""(?i)(secret|api[_-]?key|access[_-]?token|auth[_-]?token)\s*[:=]\s*['\"][^'\"]{8,}['\"]"""), "Warning"),
        Triple("High-entropy string", Regex("""['\"][A-Za-z0-9+/=_-]{32,}['\"]"""), "Warning"),
    )

    private val secretFileNames = setOf(".env", ".env.local", ".env.production", ".env.development",
        "credentials.txt", "keystore.properties", "local.properties")

    private val secretExtensions = setOf("jks", "keystore", "p12", "pfx")

    fun scan(diffText: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        var currentFile = ""
        var lineNo = 0
        for (raw in diffText.lineSequence()) {
            if (raw.startsWith("diff --git") || raw.startsWith("---") || raw.startsWith("+++")) {
                // track file from +++ line
                if (raw.startsWith("+++")) {
                    currentFile = raw.removePrefix("+++ ").removePrefix("b/").trim()
                    if (currentFile == "/dev/null") currentFile = ""
                }
                continue
            }
            if (raw.startsWith("@@")) {
                val m = Regex("""\+(\d+)""").find(raw)
                lineNo = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                continue
            }
            if (raw.startsWith("+") && !raw.startsWith("+++")) {
                val content = raw.removePrefix("+")
                // secret file names in added path? check file name
                val name = currentFile.substringAfterLast('/')
                if (name in secretFileNames) {
                    findings += Finding("Secret file added", "Blocker", currentFile, lineNo, "A secret/env file was added to the diff.")
                }
                val ext = currentFile.substringAfterLast('.', "")
                if (ext in secretExtensions) {
                    findings += Finding("Keystore/binary secret file", "Blocker", currentFile, lineNo, "A keystore/certificate file was added.")
                }
                for ((kind, rx, sev) in patterns) {
                    if (rx.containsMatchIn(content)) {
                        findings += Finding(kind, sev, currentFile.ifBlank { "?" }, lineNo, "Redacted value at this line.")
                    }
                }
                lineNo++
            } else if (raw.startsWith("-")) {
                // deletions don't add secrets
            } else {
                if (!raw.startsWith("\\")) lineNo++
            }
        }
        // dedupe identical findings
        return findings.distinct()
    }

    fun redact(diffText: String): String {
        var out = diffText
        for ((_, rx, _) in patterns) {
            out = rx.replace(out) { m ->
                val v = m.value
                if (v.length <= 8) "«REDACTED»" else v.take(4) + "…«REDACTED»"
            }
        }
        return out
    }
}

class CodeReviewTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
    private val settings: suspend () -> AppSettings,
) : Tool {
    override val name = "code_review"
    override val description =
        "Review the current git changes before committing or pushing. Runs a deterministic secret scan " +
            "(API keys, tokens, passwords, keystores, .env, workflow secrets) and, in FULL mode, emits a " +
            "redacted diff plus a suggested read-only subagent prompt for deeper review. Blockers (e.g. a " +
            "leaked secret) make this tool fail until fixed or explicitly overridden with override=true."
    override val parametersSchema = Schema.obj(
        mapOf(
            "staged" to Schema.string("Pass \"true\" to review staged changes instead of unstaged."),
            "override" to Schema.string("Pass \"true\" to acknowledge blockers and proceed anyway (user must have approved)."),
        ),
        required = emptyList(),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val settingsNow = settings()
        val level = settingsNow.codeReviewBeforeCommit
        if (level == CodeReviewLevel.OFF) {
            return@withContext ToolResult(true, "Code review is disabled (Off). Skipping.")
        }
        val staged = args["staged"]?.jsonPrimitive?.booleanOrNull == true
        val override = args["override"]?.jsonPrimitive?.booleanOrNull == true
        // get diff
        val diffCmd = gitDiffCmd(null, staged)
        val res = withContext(Dispatchers.IO) { router.runWorkspace(diffCmd, ctx.workspace, timeoutMs = 60_000, maxOutput = 2_000_000) }
        val diffText = res.rawOutput
        if (diffText.isBlank() || diffText.contains("No commits yet")) {
            return@withContext ToolResult(true, "No changes to review.")
        }
        val addedLines = diffText.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
        val findings = SecretScanner.scan(diffText)
        val blockers = findings.filter { it.severity == "Blocker" }
        val warnings = findings.filter { it.severity == "Warning" }
        val notes = findings.filter { it.severity == "Note" }
        val sb = StringBuilder()
        sb.appendLine("Code review (${level.name}):")
        sb.appendLine("Changed lines: $addedLines (min for AI review: ${settingsNow.codeReviewMinDiffLines})")
        sb.appendLine()
        if (findings.isEmpty()) {
            sb.appendLine("Secret scan: clean (no secrets detected).")
        } else {
            sb.appendLine("Secret scan findings:")
            for (f in findings) {
                sb.appendLine("- [${f.severity}] ${f.kind} — ${f.file}:${f.line} — ${f.hint}")
            }
        }
        if (blockers.isNotEmpty() && !override) {
            sb.appendLine()
            sb.appendLine("BLOCKED: fix the blockers above or re-run with override=true after explicit user approval.")
            return@withContext ToolResult(false, sb.toString().trim())
        }
        if (override && blockers.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Note: blockers overridden by explicit approval.")
        }
        if (level == CodeReviewLevel.FULL && addedLines >= settingsNow.codeReviewMinDiffLines) {
            val redacted = SecretScanner.redact(diffText)
            sb.appendLine()
            sb.appendLine("=== Redacted diff (for AI review) ===")
            sb.appendLine(redacted.take(12000))
            sb.appendLine()
            sb.appendLine("=== Suggested read-only subagent prompt ===")
            sb.appendLine("Review the redacted diff above for: obvious logic errors, accidentally deleted functions, leftover debug code, changes touching signing/package name/workflow/secrets, missing versionCode bump in self-development, large binary additions. Report each finding as Blocker/Warning/Note with file:line and a short explanation. Do not modify files.")
        }
        sb.appendLine()
        sb.appendLine("Summary: blockers=${blockers.size} warnings=${warnings.size + (if (level==CodeReviewLevel.FULL) 0 else 0)} notes=${notes.size}")
        ToolResult(true, sb.toString().trim())
    }
}
