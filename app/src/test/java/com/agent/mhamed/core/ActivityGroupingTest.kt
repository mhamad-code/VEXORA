package com.agent.mhamed.core

import org.junit.Assert.*
import org.junit.Test

class ActivityGroupingTest {
    private fun tool(id: String, name: String, result: ChatMessage? = ChatMessage(Role.TOOL, "ok", toolCallId = id)) =
        ChatActivityPresentation.Entry.Tool("tool-$id", ToolCallData(id, name, "{}"), result)
    private fun text(key: String, body: String) = ChatActivityPresentation.Entry.Commentary(key, body)
    private fun thought(key: String) = ChatActivityPresentation.Entry.Thought(key, "thinking", 0)

    @Test fun consecutiveActivitiesBetweenTextsBecomeOneGroup() {
        val blocks = ActivityGrouping.blocks(listOf(
            thought("t1"), tool("a", "read_file"), tool("b", "shell"), tool("c", "search_files"),
            text("m1", "Now edit"), tool("d", "edit_file"), tool("e", "git_status"), text("m2", "Done"),
        ))
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is ActivityGrouping.Block.Activity)
        val first = (blocks[0] as ActivityGrouping.Block.Activity).group
        assertEquals(listOf(ActivityGrouping.Kind.THINKING, ActivityGrouping.Kind.READ_FILES,
            ActivityGrouping.Kind.COMMAND, ActivityGrouping.Kind.READ_FILES), first.kinds)
        assertEquals(4, first.entries.size)
        assertTrue(blocks[1] is ActivityGrouping.Block.Text)
        val second = (blocks[2] as ActivityGrouping.Block.Activity).group
        assertEquals(listOf(ActivityGrouping.Kind.EDIT_FILES, ActivityGrouping.Kind.GIT), second.kinds)
        assertTrue(blocks[3] is ActivityGrouping.Block.Text)
    }

    @Test fun summaryKeepsFirstAppearanceOrderAndCountsDuplicates() {
        val kinds = listOf(ActivityGrouping.Kind.READ_FILES, ActivityGrouping.Kind.COMMAND,
            ActivityGrouping.Kind.READ_FILES, ActivityGrouping.Kind.COMMAND, ActivityGrouping.Kind.BROWSER)
        assertEquals(listOf(
            ActivityGrouping.Kind.READ_FILES to 2,
            ActivityGrouping.Kind.COMMAND to 2,
            ActivityGrouping.Kind.BROWSER to 1,
        ), ActivityGrouping.summaryParts(kinds))
    }

    @Test fun failedToolMarksTheWholeGroup() {
        val blocks = ActivityGrouping.blocks(listOf(
            tool("a", "read_file"),
            tool("b", "shell", ChatMessage(Role.TOOL, "boom", toolCallId = "b", isError = true)),
        ))
        val group = (blocks.single() as ActivityGrouping.Block.Activity).group
        assertTrue(group.hasFailure)
        assertFalse(group.running)
        assertFalse(group.awaitingApproval)
    }

    @Test fun unfinishedToolIsRunningAndApprovalGroupIsFlagged() {
        val blocks = ActivityGrouping.blocks(listOf(
            tool("a", "shell", null),
            tool("b", "edit_file", null),
        ), pendingApprovalCallId = "b")
        val group = (blocks.single() as ActivityGrouping.Block.Activity).group
        assertTrue(group.running)
        assertTrue(group.awaitingApproval)
        assertFalse(group.hasFailure)
    }

    @Test fun toolMappingCoversEveryFamily() {
        assertEquals(ActivityGrouping.Kind.BROWSER, ActivityGrouping.kindOfTool("browser_read"))
        assertEquals(ActivityGrouping.Kind.SEARCH, ActivityGrouping.kindOfTool("web_search"))
        assertEquals(ActivityGrouping.Kind.MCP, ActivityGrouping.kindOfTool("mcp_call"))
        assertEquals(ActivityGrouping.Kind.SUBAGENT, ActivityGrouping.kindOfTool("task"))
        assertEquals(ActivityGrouping.Kind.COMMAND, ActivityGrouping.kindOfTool("device_tap"))
        assertEquals(ActivityGrouping.Kind.GIT, ActivityGrouping.kindOfTool("git_commit"))
        assertEquals(ActivityGrouping.Kind.OTHER, ActivityGrouping.kindOfTool("remember"))
    }

    @Test fun textOnlyEntryListKeepsOrder() {
        val blocks = ActivityGrouping.blocks(listOf(text("a", "one"), text("b", "two")))
        assertEquals(listOf("one", "two"), blocks.map { (it as ActivityGrouping.Block.Text).text })
    }
}
