package com.agent.mhamed.core

/**
 * Pure presentation grouping for a chat turn's activity. It only reshapes the
 * entries produced by [ChatActivityPresentation] into blocks; it never changes
 * how tools run, stream or persist. Consecutive activity entries between two
 * commentary texts become one group so the UI can draw a single compact line.
 */
internal object ActivityGrouping {

    enum class Kind { THINKING, READ_FILES, EDIT_FILES, COMMAND, BROWSER, GIT, SEARCH, MCP, SUBAGENT, OTHER }

    data class Group(
        val key: String,
        val kinds: List<Kind>,
        val entries: List<ChatActivityPresentation.Entry>,
        val hasFailure: Boolean,
        val running: Boolean,
        val awaitingApproval: Boolean,
    )

    sealed interface Block {
        val key: String
        data class Text(override val key: String, val text: String) : Block
        data class Activity(override val key: String, val group: Group) : Block
    }

    private val readTools = setOf(
        "read_file", "file_info", "list_files", "search_files", "grep", "glob", "read_tool_output",
        "read_artifact", "read_chat_text", "view_chat_image", "zoom_chat_image", "recall",
        "search_agent_knowledge", "visual_guide",
    )
    private val editTools = setOf(
        "write_file", "edit_file", "multi_edit", "apply_patch", "delete_file", "move_file", "create_dir",
    )
    private val commandTools = setOf(
        "shell", "shell_background", "bg_list", "bg_log", "bg_kill", "pkg_install", "android_sdk_install",
        "install_apk",
    )
    private val browserTools = setOf("web_fetch", "browser_read")
    private val searchTools = setOf("web_search", "search_chats", "recent_chats", "read_chat_snippet")

    fun kindOfTool(name: String): Kind = when {
        name in readTools -> Kind.READ_FILES
        name in editTools -> Kind.EDIT_FILES
        name in commandTools || name.startsWith("device_") -> Kind.COMMAND
        name in browserTools || name.startsWith("browser_") -> Kind.BROWSER
        name.startsWith("git_") -> Kind.GIT
        name in searchTools -> Kind.SEARCH
        name.startsWith("mcp_") || name == "use_tool" -> Kind.MCP
        name == "task" || name.startsWith("subagent_") -> Kind.SUBAGENT
        else -> Kind.OTHER
    }

    private fun kindOf(entry: ChatActivityPresentation.Entry): Kind = when (entry) {
        is ChatActivityPresentation.Entry.Thought -> Kind.THINKING
        is ChatActivityPresentation.Entry.Tool -> kindOfTool(entry.call.name)
        is ChatActivityPresentation.Entry.Commentary -> Kind.OTHER
    }

    /** First-appearance order, with a count per kind. */
    fun summaryParts(kinds: List<Kind>): List<Pair<Kind, Int>> {
        val order = linkedMapOf<Kind, Int>()
        for (kind in kinds) order[kind] = (order[kind] ?: 0) + 1
        return order.map { it.key to it.value }
    }

    fun blocks(
        entries: List<ChatActivityPresentation.Entry>,
        pendingApprovalCallId: String? = null,
    ): List<Block> {
        val out = mutableListOf<Block>()
        val pending = mutableListOf<ChatActivityPresentation.Entry>()
        fun flush() {
            if (pending.isEmpty()) return
            val snapshot = pending.toList()
            pending.clear()
            val kinds = snapshot.map(::kindOf)
            val hasFailure = snapshot.any { it is ChatActivityPresentation.Entry.Tool && it.result?.isError == true }
            val running = snapshot.any { it is ChatActivityPresentation.Entry.Tool && it.result == null }
            val awaiting = snapshot.any {
                it is ChatActivityPresentation.Entry.Tool && pendingApprovalCallId != null && it.call.id == pendingApprovalCallId
            }
            out += Block.Activity(
                key = "group-${snapshot.first().key}",
                group = Group(
                    key = "group-${snapshot.first().key}",
                    kinds = kinds,
                    entries = snapshot,
                    hasFailure = hasFailure,
                    running = running,
                    awaitingApproval = awaiting,
                ),
            )
        }
        for (entry in entries) {
            if (entry is ChatActivityPresentation.Entry.Commentary) {
                flush()
                out += Block.Text(entry.key, entry.text)
            } else {
                pending += entry
            }
        }
        flush()
        return out
    }
}
