package com.androidharness.app.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {
    @Test
    fun `frames carry their sequence number`() {
        val frame = RelayProtocol.decode("""{"seq":7,"kind":"event","event":{"kind":"text","text":"hi"}}""")
        assertEquals(7L, frame?.seq)
        assertEquals(CloudEvent.Text("hi"), frame?.event)
    }

    @Test
    fun `a ready frame is not an event frame`() {
        assertNull(RelayProtocol.decode("""{"seq":3,"kind":"ready","lastSeq":3}"""))
        assertEquals(3L, RelayProtocol.readySeq("""{"seq":3,"kind":"ready","lastSeq":3}"""))
    }

    @Test
    fun `resume replays only frames newer than the last one rendered`() {
        val replay = RelayReplay(10)
        assertFalse(replay.accept(CloudFrame(10, CloudEvent.Text("old"))))
        assertTrue(replay.accept(CloudFrame(11, CloudEvent.Text("new"))))
        assertFalse(replay.accept(CloudFrame(11, CloudEvent.Text("duplicate"))))
        assertEquals(11L, replay.lastSeq)
    }

    @Test
    fun `start carries the gateway but never a bare provider key`() {
        val plan = GatewayPlan(
            mainAlias = "vexora-main",
            fastAlias = "vexora-fast",
            yaml = "model_list: []",
            env = mapOf("ANTHROPIC_MODEL" to "vexora-main"),
            secrets = mapOf("VEXORA_KEY_MAIN" to "sk-test"),
            mainContextTokens = null,
            fastContextTokens = null,
        )
        val text = RelayProtocol.start("do work", plan, CloudPermissionMode.ACCEPT_EDITS)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("start", root["type"]?.jsonPrimitive?.content)
        assertEquals("acceptEdits", root["permissionMode"]?.jsonPrimitive?.content)
        val gateway = root["gateway"] as JsonObject
        assertEquals("vexora-main", (gateway["env"] as JsonObject)["ANTHROPIC_MODEL"]?.jsonPrimitive?.content)
        assertEquals("sk-test", (gateway["secrets"] as JsonObject)["VEXORA_KEY_MAIN"]?.jsonPrimitive?.content)
        // The key is only inside the authenticated gateway block, never top-level.
        assertFalse(root.containsKey("apiKey"))
    }

    @Test
    fun `the relay command surface stays closed`() {
        assertTrue("message" in CloudCommands.allowed)
        assertFalse("shell" in CloudCommands.allowed)
        assertFalse("exec" in CloudCommands.allowed)
        val failed = runCatching { RelayProtocol.simple("exec") }
        assertTrue(failed.isFailure)
    }
}
