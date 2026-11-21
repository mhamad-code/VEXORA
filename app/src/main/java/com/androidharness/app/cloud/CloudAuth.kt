package com.androidharness.app.cloud

import com.androidharness.app.github.GitHubApi
import com.androidharness.app.github.GitHubConnectionState
import com.androidharness.app.github.GitHubCredential
import com.androidharness.app.github.GitHubDeviceCode
import com.androidharness.app.github.oauthError
import com.androidharness.app.github.redactGitHubSecrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * GitHub sign-in for the cloud section. It is deliberately separate from the
 * repository connection: this one asks for `codespace` (plus `repo` only
 * because a private workspace repository has to be created), and its token is
 * stored under its own key so signing out of the cloud never disconnects the
 * user's existing GitHub tools.
 */
class CloudGitHubAuth(
    private val clientId: () -> String,
    private val read: () -> GitHubCredential?,
    private val write: (GitHubCredential?) -> Unit,
    val api: GitHubApi = GitHubApi(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var signIn: Job? = null
    private val _state = MutableStateFlow(
        read()?.let { GitHubConnectionState(it.login, it.oauth) } ?: GitHubConnectionState(),
    )
    val state: StateFlow<GitHubConnectionState> = _state

    fun cancelSignIn() {
        generation.incrementAndGet()
        signIn?.cancel()
        signIn = null
        _state.value = _state.value.copy(busy = false, code = null)
    }

    fun startSignIn() {
        cancelSignIn()
        val attempt = generation.get()
        signIn = scope.launch {
            _state.value = _state.value.copy(busy = true, message = "Requesting a GitHub sign-in code…")
            try {
                val id = clientId().trim()
                check(id.isNotEmpty()) {
                    "This build has no GitHub Client ID for the cloud section. Add one in the cloud settings or use a personal access token."
                }
                val body = api.form("/login/device/code", mapOf("client_id" to id, "scope" to SCOPE))
                check(body.string("error") == null) {
                    oauthError(body.string("error"), body.string("error_description"))
                }
                val code = GitHubDeviceCode(
                    deviceCode = requireNotNull(body.string("device_code")),
                    userCode = requireNotNull(body.string("user_code")),
                    url = requireNotNull(body.string("verification_uri")),
                    expiresIn = body.seconds("expires_in") ?: 900,
                    interval = (body.seconds("interval") ?: 5).coerceAtLeast(5),
                )
                check(code.url == "https://github.com/login/device") {
                    "GitHub returned an unexpected sign-in address."
                }
                _state.value = _state.value.copy(code = code, message = "Enter this code on GitHub, then approve access.")
                val deadline = now() + code.expiresIn * 1000
                var interval = code.interval
                while (now() < deadline) {
                    pause(interval * 1000)
                    if (now() >= deadline) break
                    val token = api.form(
                        "/login/oauth/access_token",
                        mapOf(
                            "client_id" to id,
                            "device_code" to code.deviceCode,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        ),
                    )
                    when (token.string("error")) {
                        "authorization_pending" -> Unit
                        "slow_down" -> interval = maxOf(interval + 5, token.seconds("interval") ?: 0)
                        null -> {
                            val access = requireNotNull(token.string("access_token"))
                            val login = api.get("/user", access).login()
                            generation.get().let { if (it != attempt) return@launch }
                            mutex.withLock {
                                currentCheck(attempt)
                                save(credential(token, login, id))
                            }
                            return@launch
                        }
                        else -> error(oauthError(token.string("error"), token.string("error_description")))
                    }
                }
                error("The sign-in code expired. Request a new code.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation.get() == attempt) {
                    _state.value = _state.value.copy(busy = false, code = null, message = safeMessage(e))
                }
            } finally {
                if (generation.get() == attempt) _state.value = _state.value.copy(busy = false, code = null)
            }
        }
    }

    internal suspend fun awaitSignIn() {
        signIn?.join()
    }

    suspend fun connectPat(token: String) {
        cancelSignIn()
        val clean = token.trim()
        require(clean.isNotEmpty() && clean.none { it.isWhitespace() }) { "Paste the complete personal access token." }
        mutex.withLock {
            val login = api.get("/user", clean).login()
            save(GitHubCredential(clean, login, scopes = SCOPE))
        }
    }

    suspend fun disconnect() {
        cancelSignIn()
        mutex.withLock {
            write(null)
            _state.value = GitHubConnectionState(message = "Signed out of the cloud section on this device.")
        }
    }

    suspend fun token(): String? = mutex.withLock { tokenLocked() }

    private suspend fun tokenLocked(): String? {
        var saved = read() ?: return null
        if (saved.oauth && saved.expiresAt?.let { it <= now() + 60_000 } == true) {
            val refresh = saved.refreshToken ?: error("GitHub sign-in expired. Sign in again or use a personal access token.")
            check(saved.refreshExpiresAt?.let { it > now() } != false) { "GitHub sign-in expired. Sign in again." }
            val body = api.form(
                "/login/oauth/access_token",
                mapOf(
                    "client_id" to saved.clientId.ifBlank { clientId() },
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                ),
            )
            check(body.string("error") == null) { oauthError(body.string("error"), body.string("error_description")) }
            val access = requireNotNull(body.string("access_token"))
            val login = api.get("/user", access).login()
            check(login == saved.login) { "GitHub account changed. Sign in again." }
            saved = credential(body, login, saved.clientId.ifBlank { clientId() })
            write(saved)
        }
        return saved.token
    }

    private fun currentCheck(attempt: Long) {
        check(generation.get() == attempt) { "Sign-in was cancelled." }
    }

    private fun credential(body: JsonObject, login: String, clientId: String): GitHubCredential {
        val token = body.string("access_token")?.takeIf { it.isNotBlank() }
            ?: error("GitHub did not return an access token. Sign in again.")
        return GitHubCredential(
            token = token,
            login = login,
            oauth = true,
            refreshToken = body.string("refresh_token"),
            expiresAt = body.seconds("expires_in")?.let { now() + it * 1000 },
            refreshExpiresAt = body.seconds("refresh_token_expires_in")?.let { now() + it * 1000 },
            scopes = body.string("scope").orEmpty(),
            clientId = clientId,
        )
    }

    private fun save(credential: GitHubCredential) {
        write(credential)
        _state.value = GitHubConnectionState(credential.login, credential.oauth, message = "Connected as ${credential.login}.")
    }

    fun safeMessage(e: Throwable): String {
        var message = e.message ?: "Could not reach GitHub. Check your connection and try again."
        read()?.let { credential ->
            message = message.replace(credential.token, "[hidden]")
            credential.refreshToken?.let { message = message.replace(it, "[hidden]") }
        }
        return redactGitHubSecrets(message)
    }

    private fun kotlinx.serialization.json.JsonElement.login(): String =
        (this as? JsonObject)?.string("login")?.takeIf { it.isNotBlank() }
            ?: error("GitHub could not verify this account.")

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }

    private fun JsonObject.seconds(key: String): Long? =
        (get(key) as? JsonPrimitive)?.let { runCatching { it.content.toLong() }.getOrNull() }

    companion object {
        /** `codespace` is required; `repo` is only used to create the private workspace repository. */
        const val SCOPE = "codespace repo offline_access"
    }
}
