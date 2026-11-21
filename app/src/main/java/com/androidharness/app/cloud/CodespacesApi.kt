package com.androidharness.app.cloud

import com.androidharness.app.github.GitHubHttpError
import com.androidharness.app.github.redactGitHubSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Codespaces REST calls. Only the documented endpoints are used, except port
 * visibility: GitHub exposes that through the CLI and web UI, so the app tries
 * the endpoint the CLI itself calls and degrades to a clear instruction when
 * the account or the API refuses it.
 */
class CodespacesApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS).build(),
    private val apiBase: String = "https://api.github.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun viewer(token: String): String =
        get("/user", token).jsonObject()?.string("login")
            ?: error("GitHub could not verify this account.")

    suspend fun listCodespaces(token: String): List<CodespaceInfo> =
        get("/user/codespaces?per_page=100", token).jsonObject()
            ?.get("codespaces")?.let { it as? JsonArray }
            ?.mapNotNull { (it as? JsonObject)?.toCodespace() }
            .orEmpty()

    suspend fun codespace(token: String, name: String): CodespaceInfo? =
        get("/user/codespaces/$name", token).jsonObject()?.toCodespace()

    suspend fun machines(token: String, repository: String): List<String> =
        get("/repos/$repository/codespaces/machines", token).jsonObject()
            ?.get("machines")?.let { it as? JsonArray }
            ?.mapNotNull { (it as? JsonObject)?.string("name") }
            .orEmpty()

    suspend fun createCodespace(
        token: String,
        repositoryId: Long,
        machine: String? = null,
        devcontainerPath: String = ".devcontainer/devcontainer.json",
        idleTimeoutMinutes: Int = 30,
        geo: String? = null,
    ): CodespaceInfo {
        val body = buildJsonObject {
            put("repository_id", repositoryId)
            put("devcontainer_path", devcontainerPath)
            put("idle_timeout_minutes", idleTimeoutMinutes)
            machine?.takeIf { it.isNotBlank() }?.let { put("machine", it) }
            geo?.takeIf { it.isNotBlank() }?.let { put("geo", it) }
        }
        return post("/user/codespaces", token, body).jsonObject()?.toCodespace()
            ?: error("GitHub did not return the new Codespace.")
    }

    suspend fun start(token: String, name: String): CodespaceInfo? =
        post("/user/codespaces/$name/start", token, null).jsonObject()?.toCodespace()

    suspend fun stop(token: String, name: String): CodespaceInfo? =
        post("/user/codespaces/$name/stop", token, null).jsonObject()?.toCodespace()

    suspend fun delete(token: String, name: String) {
        request("DELETE", "/user/codespaces/$name", token, null)
    }

    suspend fun repository(token: String, fullName: String): JsonObject? =
        get("/repos/$fullName", token).jsonObject()

    suspend fun createPrivateRepository(token: String, name: String): JsonObject {
        val body = buildJsonObject {
            put("name", name)
            put("private", true)
            put("auto_init", true)
            put("description", "Cloud workspace for the app's Claude Code section")
        }
        return post("/user/repos", token, body).jsonObject()
            ?: error("GitHub did not return the new repository.")
    }

    /** Create or replace one repository file. Content is committed on the default branch. */
    suspend fun putFile(token: String, fullName: String, path: String, content: String, message: String) {
        val existing = runCatching { get("/repos/$fullName/contents/$path", token).jsonObject()?.string("sha") }
            .getOrNull()
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
            existing?.takeIf { it.isNotBlank() }?.let { put("sha", it) }
        }
        request("PUT", "/repos/$fullName/contents/$path", token, body)
    }

    /**
     * Best effort: the documented API has no port visibility endpoint. The CLI
     * uses this one, so try it and report a usable instruction on failure.
     */
    suspend fun setPortPublic(token: String, name: String, port: Int) {
        try {
            val body = buildJsonObject { put("visibility", "public") }
            request("PATCH", "/user/codespaces/$name/ports/$port", token, body)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error(
                "GitHub did not let the app open port $port automatically. In the Codespace, run " +
                    "`gh codespace ports visibility $port:public` or open the Ports tab and set it to Public, then retry. " +
                    "The relay stays reachable only with this device's random token.",
            )
        }
    }

    private suspend fun get(path: String, token: String): JsonElement =
        request("GET", path, token, null)

    private suspend fun post(path: String, token: String, body: JsonObject?): JsonElement =
        request("POST", path, token, body)

    private suspend fun request(method: String, path: String, token: String, body: JsonObject?): JsonElement =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url("$apiBase$path")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer $token")
            val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
                ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null)
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                "PATCH" -> builder.patch(requireNotNull(requestBody))
                "PUT" -> builder.put(requireNotNull(requestBody))
                else -> builder.post(requireNotNull(requestBody))
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                if (!response.isSuccessful) {
                    val message = (parsed as? JsonObject)?.string("message")
                        ?: "GitHub returned HTTP ${response.code}."
                    val details = ((parsed as? JsonObject)?.get("errors") as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.string("message") }
                    throw GitHubHttpError(response.code, details,
                        redactGitHubSecrets("$message ${details.joinToString(" ")}".trim()))
                }
                parsed ?: JsonPrimitive(true)
            }
        }

    private fun JsonElement.jsonObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.toCodespace(): CodespaceInfo? {
        val name = string("name") ?: return null
        return CodespaceInfo(
            name = name,
            state = string("state").orEmpty(),
            machine = (get("machine") as? JsonObject)?.string("name"),
            repository = (get("repository") as? JsonObject)?.string("full_name"),
            webUrl = string("web_url"),
            idleTimeoutMinutes = (get("idle_timeout_minutes") as? JsonPrimitive)
                ?.content?.toIntOrNull(),
            createdAt = string("created_at"),
            lastUsedAt = string("last_used_at"),
        )
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
