package com.androidharness.app.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The phone/relay wire format. Every server frame carries a monotonic [seq];
 * the phone acknowledges the highest one it rendered by reconnecting with it,
 * and the relay replays everything newer. The relay only understands the
 * command types in [CloudCommands].
 */
object RelayProtocol {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Frames are tagged with "kind" so they stay readable in the relay log.
        classDiscriminator = "kind"
    }

    fun client(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): String {
        require(type in CloudCommands.allowed) { "Unsupported cloud command." }
        return buildJsonObject {
            put("type", type)
            build()
        }.toString()
    }

    fun hello(token: String, since: Long): String = client(CloudCommands.HELLO) {
        put("token", token)
        put("since", since)
    }

    fun start(prompt: String, plan: GatewayPlan, permission: CloudPermissionMode, sessionId: String? = null): String =
        client(CloudCommands.START) {
            put("prompt", prompt)
            put("model", plan.mainAlias)
            put("fastModel", plan.fastAlias)
            put("permissionMode", CloudPermissionMode.cliValue(permission))
            sessionId?.let { put("sessionId", it) }
            gateway(plan)
        }

    fun message(text: String, plan: GatewayPlan, permission: CloudPermissionMode): String =
        client(CloudCommands.MESSAGE) {
            put("text", text)
            put("model", plan.mainAlias)
            put("fastModel", plan.fastAlias)
            put("permissionMode", CloudPermissionMode.cliValue(permission))
            gateway(plan)
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.gateway(plan: GatewayPlan) {
        put("gateway", buildJsonObject {
            put("yaml", plan.yaml)
            put("env", buildJsonObject { plan.env.forEach { (k, v) -> put(k, v) } })
            put("secrets", buildJsonObject { plan.secrets.forEach { (k, v) -> put(k, v) } })
        })
    }

    fun permission(requestId: String, allow: Boolean): String = client(CloudCommands.PERMISSION) {
        put("requestId", requestId)
        put("allow", allow)
    }

    fun resume(sessionId: String): String = client(CloudCommands.RESUME) { put("sessionId", sessionId) }

    fun simple(type: String): String = client(type)

    /** `{"seq": 12, "kind": "event", "event": {...}}` and bare `{"seq":..,"event":..}` both work. */
    fun decode(text: String): CloudFrame? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        val seq = (root["seq"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: return null
        val event = root["event"] as? JsonObject ?: return null
        CloudFrame(seq, json.decodeFromJsonElement(CloudEvent.serializer(), event))
    }.getOrNull()

    /** `{"seq":12,"kind":"ready","lastSeq":12}` acknowledges a connection or a status request. */
    fun readySeq(text: String): Long? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        if ((root["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "ready") return null
        (root["lastSeq"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
    }.getOrNull()

    /** Read an untyped `error` frame the relay sends before the protocol is established. */
    fun errorMessage(text: String): String? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        (root["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * Deduplicates and orders replayed frames. A reconnect asks for everything
 * newer than [lastSeq], so the same frame can arrive twice when the socket
 * drops between the relay's send and the phone's render.
 */
class RelayReplay(initialSeq: Long = 0) {
    private val startSeq = initialSeq
    private var last = initialSeq
    private val seen = linkedSetOf<Long>()

    val lastSeq: Long get() = last

    fun accept(frame: CloudFrame): Boolean {
        if (frame.seq <= startSeq || !seen.add(frame.seq)) return false
        if (frame.seq > last) last = frame.seq
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        return true
    }

    /** Frames may skip numbers when the relay restarts a session; the phone still continues. */
    fun gap(expected: Long): Boolean = expected < last

    private companion object {
        const val MAX_SEEN = 4096
    }
}
