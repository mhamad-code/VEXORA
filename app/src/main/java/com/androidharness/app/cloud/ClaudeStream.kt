package com.androidharness.app.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Turns Claude Code's newline-delimited `stream-json` output into the app's own
 * events. The CLI adds fields over time, so every lookup is optional and an
 * unknown event type is dropped rather than failing the whole stream.
 *
 * Text arrives through `stream_event` deltas because the relay runs with
 * `--include-partial-messages`; the completed `assistant` message is only used
 * for its tool-use blocks, which keeps the text from being rendered twice.
 */
class ClaudeStreamParser {
    var sessionId: String? = null
        private set

    fun reset() {
        sessionId = null
    }

    private val fileTools = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")

    fun parseLine(line: String): List<CloudEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()
        val root = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return emptyList()
        return runCatching { parse(root) }.getOrDefault(emptyList())
    }

    private fun parse(root: JsonObject): List<CloudEvent> = when (root.string("type")) {
        "system" -> parseSystem(root)
        "stream_event" -> parseStreamEvent(root.obj("event"))
        "assistant" -> parseAssistant(root.obj("message"))
        "user" -> parseUser(root.obj("message"))
        "result" -> parseResult(root)
        "control_request" -> parseControlRequest(root)
        "error", "stream_error" -> listOf(CloudEvent.Error(root.string("message") ?: "Claude Code reported an error."))
        else -> emptyList()
    }

    private fun parseSystem(root: JsonObject): List<CloudEvent> {
        if (root.string("subtype") != "init") return emptyList()
        val id = root.string("session_id")?.takeIf { it.isNotBlank() } ?: return emptyList()
        sessionId = id
        return listOf(CloudEvent.Session(id), CloudEvent.State(CloudRunState.RUNNING))
    }

    private fun parseStreamEvent(event: JsonObject?): List<CloudEvent> {
        if (event == null || event.string("type") != "content_block_delta") return emptyList()
        val delta = event.obj("delta") ?: return emptyList()
        val text = delta.string("text")
        return when (delta.string("type")) {
            "text_delta" -> text?.takeIf { it.isNotEmpty() }?.let { listOf(CloudEvent.Text(it)) }.orEmpty()
            "thinking_delta" -> delta.string("thinking")?.takeIf { it.isNotEmpty() }
                ?.let { listOf(CloudEvent.Thinking(it)) }.orEmpty()
            else -> emptyList()
        }
    }

    private fun parseAssistant(message: JsonObject?): List<CloudEvent> {
        val blocks = message?.get("content") as? JsonArray ?: return emptyList()
        val events = mutableListOf<CloudEvent>()
        for (block in blocks) {
            val obj = block as? JsonObject ?: continue
            if (obj.string("type") != "tool_use") continue
            val id = obj.string("id").orEmpty()
            val name = obj.string("name").orEmpty()
            if (name.isBlank()) continue
            val input = obj["input"]?.toString().orEmpty()
            events += CloudEvent.ToolCall(id, name, cap(input))
            if (name in fileTools) {
                val path = filePath(obj.obj("input"))
                if (path != null) events += CloudEvent.FileChange(path, summarize(name, obj.obj("input")))
            }
        }
        return events
    }

    private fun parseUser(message: JsonObject?): List<CloudEvent> {
        val blocks = message?.get("content") as? JsonArray ?: return emptyList()
        return blocks.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.string("type") != "tool_result") return@mapNotNull null
            CloudEvent.ToolResult(
                id = obj.string("tool_use_id").orEmpty(),
                content = cap(flatten(obj["content"])),
                error = obj["is_error"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true,
            )
        }
    }

    private fun parseResult(root: JsonObject): List<CloudEvent> {
        val usage = root.obj("usage")
        val events = mutableListOf<CloudEvent>()
        if (usage != null) {
            events += CloudEvent.Usage(
                inputTokens = usage.long("input_tokens"),
                outputTokens = usage.long("output_tokens"),
                costUsd = root.double("total_cost_usd") ?: root.double("cost_usd"),
            )
        }
        val failed = root.string("subtype")?.contains("error", ignoreCase = true) == true ||
            root.string("is_error")?.toBoolean() == true
        if (failed) events += CloudEvent.Error(root.string("result") ?: "The cloud task failed.")
        events += CloudEvent.Done(ok = !failed)
        return events
    }

    private fun parseControlRequest(root: JsonObject): List<CloudEvent> {
        val request = root.obj("request") ?: return emptyList()
        if (request.string("subtype") != "can_use_tool") return emptyList()
        val requestId = root.string("request_id") ?: return emptyList()
        return listOf(
            CloudEvent.Permission(
                requestId = requestId,
                tool = request.string("tool_name").orEmpty().ifBlank { "tool" },
                input = cap(request["input"]?.toString().orEmpty()),
            ),
        )
    }

    private fun summarize(tool: String, input: JsonObject?): String = when (tool) {
        "Write" -> "writes ${input?.string("file_path").orEmpty()}"
        "NotebookEdit" -> "edits notebook ${input?.string("notebook_path").orEmpty()}"
        else -> "edits ${input?.string("file_path").orEmpty()}"
    }.trim()

    private fun filePath(input: JsonObject?): String? =
        (input?.string("file_path") ?: input?.string("notebook_path"))?.takeIf { it.isNotBlank() }

    private fun flatten(element: kotlinx.serialization.json.JsonElement?): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString("\n") { flatten(it) }
        is JsonObject -> element["text"]?.let { flatten(it) } ?: element.toString()
        null -> ""
    }

    private fun cap(text: String, limit: Int = 8_000): String =
        if (text.length <= limit) text else text.take(limit) + "\n…"

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull
}
