package com.androidharness.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeStreamTest {
    private val parser = ClaudeStreamParser()

    @Test
    fun `init reports the session and running state`() {
        val events = parser.parseLine("""{"type":"system","subtype":"init","session_id":"abc-123","tools":[]}""")
        assertEquals(listOf(CloudEvent.Session("abc-123"), CloudEvent.State(CloudRunState.RUNNING)), events)
        assertEquals("abc-123", parser.sessionId)
    }

    @Test
    fun `partial text and thinking deltas become cards`() {
        val text = parser.parseLine(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}}""",
        )
        val thinking = parser.parseLine(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hmm"}}}""",
        )
        assertEquals(listOf(CloudEvent.Text("Hello")), text)
        assertEquals(listOf(CloudEvent.Thinking("hmm")), thinking)
    }

    @Test
    fun `tool use from a finished assistant message also reports the edited file`() {
        val events = parser.parseLine(
            """{"type":"assistant","message":{"role":"assistant","content":[
                {"type":"text","text":"done"},
                {"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"src/Main.kt","old_string":"a","new_string":"b"}}]}}""",
        )
        assertEquals(2, events.size)
        assertTrue(events[0] is CloudEvent.ToolCall)
        val file = events[1] as CloudEvent.FileChange
        assertEquals("src/Main.kt", file.path)
    }

    @Test
    fun `tool results carry their error flag`() {
        val events = parser.parseLine(
            """{"type":"user","message":{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"t1","content":"boom","is_error":true}]}}""",
        )
        assertEquals(listOf(CloudEvent.ToolResult("t1", "boom", true)), events)
    }

    @Test
    fun `result reports usage and ends the turn`() {
        val events = parser.parseLine(
            """{"type":"result","subtype":"success","total_cost_usd":0.25,
               "usage":{"input_tokens":100,"output_tokens":20}}""",
        )
        assertEquals(
            listOf(CloudEvent.Usage(100L, 20L, 0.25), CloudEvent.Done(true)),
            events,
        )
    }

    @Test
    fun `failed result is an error and a failed done`() {
        val events = parser.parseLine("""{"type":"result","is_error":true,"result":"rate limited"}""")
        assertTrue(events.any { it is CloudEvent.Error })
        assertTrue(events.any { it == CloudEvent.Done(false) })
    }

    @Test
    fun `permission control requests become approval cards`() {
        val events = parser.parseLine(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"}}}""",
        )
        val permission = events.single() as CloudEvent.Permission
        assertEquals("r1", permission.requestId)
        assertEquals("Bash", permission.tool)
    }

    @Test
    fun `garbage lines are ignored instead of failing the stream`() {
        assertEquals(emptyList<CloudEvent>(), parser.parseLine("not json at all"))
        assertEquals(emptyList<CloudEvent>(), parser.parseLine("""{"type":"unknown_future_event"}"""))
        assertEquals(emptyList<CloudEvent>(), parser.parseLine(""))
    }
}
