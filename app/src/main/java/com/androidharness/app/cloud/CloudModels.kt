package com.androidharness.app.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A hosted backend that can run a coding agent for the user. The first (and
 * only) implementation is GitHub Codespaces; the interface keeps a second
 * provider from forcing a rewrite later.
 */
interface CloudProvider {
    val id: String
    val label: String

    /** Whether this environment is provisioned and reachable right now. */
    suspend fun readiness(): CloudReadiness
}

data class CloudReadiness(val ready: Boolean, val detail: String)

/**
 * A conversational cloud agent. Tool execution, file edits and model calls all
 * happen in the cloud; the app only authenticates, provisions and renders.
 */
interface CloudAgent {
    val provider: CloudProvider
    suspend fun start(selection: CloudModelSelection, permission: CloudPermissionMode): String
    suspend fun send(text: String)
    suspend fun interrupt()
    suspend fun stop()
}

@Serializable
enum class CloudPermissionMode {
    /** Ask before every tool that changes the workspace (Claude Code `default`). */
    ASK,

    /** Approve file edits automatically, still ask for shell and network tools. */
    ACCEPT_EDITS,

    /** Skip every prompt. Never the default; the UI asks for an explicit confirmation. */
    BYPASS,
    ;

    companion object {
        fun fromSetting(value: String): CloudPermissionMode =
            entries.firstOrNull { it.name == value } ?: ASK

        /** Claude Code's own `--permission-mode` value. */
        fun cliValue(mode: CloudPermissionMode): String = when (mode) {
            ASK -> "default"
            ACCEPT_EDITS -> "acceptEdits"
            BYPASS -> "bypassPermissions"
        }
    }
}

enum class CodespacePhase { NONE, CREATING, STARTING, READY, STOPPED, FAILED, DELETED }

/** State of the remote Claude Code process as reported by the relay. */
@Serializable
enum class CloudRunState {
    @SerialName("idle") IDLE,
    @SerialName("running") RUNNING,
    @SerialName("error") ERROR,
}

/**
 * Every message the cloud can send is one of these. The relay numbers them so a
 * reconnect can replay exactly what the phone missed.
 */
@Serializable
sealed interface CloudEvent {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : CloudEvent

    @Serializable
    @SerialName("thinking")
    data class Thinking(val text: String) : CloudEvent

    @Serializable
    @SerialName("tool")
    data class ToolCall(val id: String, val name: String, val input: String) : CloudEvent

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(val id: String, val content: String, val error: Boolean = false) : CloudEvent

    @Serializable
    @SerialName("file")
    data class FileChange(val path: String, val summary: String) : CloudEvent

    @Serializable
    @SerialName("usage")
    data class Usage(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val costUsd: Double? = null,
        val contextTokens: Long? = null,
    ) : CloudEvent

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : CloudEvent

    @Serializable
    @SerialName("done")
    data class Done(val ok: Boolean = true) : CloudEvent

    @Serializable
    @SerialName("state")
    data class State(val value: CloudRunState) : CloudEvent

    @Serializable
    @SerialName("session")
    data class Session(val id: String) : CloudEvent

    /** Claude Code is waiting for a decision on a tool it wants to run. */
    @Serializable
    @SerialName("permission")
    data class Permission(val requestId: String, val tool: String, val input: String) : CloudEvent

    /**
     * One raw `stream-json` line from Claude Code. The relay stays a dumb pipe
     * for model output; the phone parses it with [ClaudeStreamParser], so the
     * two sides cannot drift apart.
     */
    @Serializable
    @SerialName("raw")
    data class Raw(val json: String) : CloudEvent

    /** Local transcript entry for what the user typed; never sent by the relay. */
    @Serializable
    @SerialName("user")
    data class UserMessage(val text: String) : CloudEvent
}

/** One numbered protocol frame. */
@Serializable
data class CloudFrame(val seq: Long, val event: CloudEvent)

/** A model the user picked for the cloud agent, resolved from the app's providers. */
@Serializable
data class CloudModelSelection(
    val providerId: String,
    val providerName: String,
    val model: String,
    val alias: String,
    val contextTokens: Long? = null,
    val supportsTools: Boolean? = null,
) {
    val label: String get() = "$providerName · $model"
}

/** Progress of one provisioning step; the UI renders these as a stepper. */
enum class CloudSetupStep { REPOSITORY, FILES, CODESPACE, STARTING, RELAY, READY }

enum class CloudStepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class CloudSetupProgress(
    val current: CloudSetupStep = CloudSetupStep.REPOSITORY,
    val statuses: Map<CloudSetupStep, CloudStepStatus> =
        CloudSetupStep.entries.associateWith { CloudStepStatus.PENDING },
    val detail: String? = null,
    val error: String? = null,
) {
    fun with(step: CloudSetupStep, status: CloudStepStatus, detail: String? = null, error: String? = null) =
        copy(current = step, statuses = statuses + (step to status), detail = detail ?: this.detail, error = error)
}

/** A Codespace as the REST API describes it, trimmed to what the app uses. */
data class CodespaceInfo(
    val name: String,
    val state: String,
    val machine: String? = null,
    val repository: String? = null,
    val webUrl: String? = null,
    val idleTimeoutMinutes: Int? = null,
    val createdAt: String? = null,
    val lastUsedAt: String? = null,
) {
    val phase: CodespacePhase
        get() = when (state) {
            "Available" -> CodespacePhase.READY
            "Starting", "Provisioning", "Queued", "Created", "Awaiting", "Rebuilding", "Updating", "Exporting" ->
                CodespacePhase.STARTING
            "Shutdown", "ShuttingDown", "Archived" -> CodespacePhase.STOPPED
            "Failed", "Unavailable" -> CodespacePhase.FAILED
            "Deleted", "Moved" -> CodespacePhase.DELETED
            else -> CodespacePhase.NONE
        }

    /** Public relay origin; the port is suffixed to the Codespace name by GitHub. */
    fun relayOrigin(port: Int = RELAY_PORT): String {
        val base = name.lowercase()
        return "https://$base-$port.app.github.dev"
    }

    companion object {
        const val RELAY_PORT = 8765
    }
}

/** Everything the app knows about the cloud section on this device. */
data class CloudStatus(
    val signedIn: Boolean = false,
    val login: String? = null,
    val repository: String? = null,
    val codespace: CodespaceInfo? = null,
    val phase: CodespacePhase = CodespacePhase.NONE,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/** A tool the relay may accept from the phone. Nothing else is forwarded. */
object CloudCommands {
    const val HELLO = "hello"
    const val START = "start"
    const val MESSAGE = "message"
    const val INTERRUPT = "interrupt"
    const val PERMISSION = "permission"
    const val RESUME = "resume"
    const val STATUS = "status"
    const val STOP = "stop"

    val allowed = setOf(HELLO, START, MESSAGE, INTERRUPT, PERMISSION, RESUME, STATUS, STOP)
}
