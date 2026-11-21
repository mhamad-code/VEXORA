package com.androidharness.app.cloud

import android.content.Context
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.data.ProviderRepository
import com.androidharness.app.github.GitHubCredential
import com.androidharness.app.llm.ProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the cloud section: GitHub sign-in, one-click provisioning, the relay
 * session and the transcript the UI renders. The app's own agent and tools are
 * never involved once a cloud session starts.
 */
class CloudRepository(
    private val context: Context,
    private val keys: KeyStoreManager,
    private val providers: ProviderRepository,
    val settings: CloudSettings,
    private val buildClientId: String,
    private val api: CodespacesApi = CodespacesApi(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var currentClientId: String = buildClientId

    val auth = CloudGitHubAuth(
        clientId = { currentClientId },
        read = { keys.cloudGitHub()?.let { runCatching { json.decodeFromString<GitHubCredential>(it) }.getOrNull() } },
        write = { credential -> keys.putCloudGitHub(credential?.let { json.encodeToString(GitHubCredential.serializer(), it) }) },
        api = com.androidharness.app.github.GitHubApi(),
    )

    private val _status = MutableStateFlow(CloudStatus())
    val status: StateFlow<CloudStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow<CloudSetupProgress?>(null)
    val progress: StateFlow<CloudSetupProgress?> = _progress.asStateFlow()

    /** Everything Claude Code produced in this conversation, in order. */
    private val _events = MutableStateFlow<List<CloudEvent>>(emptyList())
    val events: StateFlow<List<CloudEvent>> = _events.asStateFlow()

    private val _relayStatus = MutableStateFlow(RelayConnection.CLOSED)
    val relayStatus: StateFlow<RelayConnection> = _relayStatus.asStateFlow()

    private val _runState = MutableStateFlow(CloudRunState.IDLE)
    val runState: StateFlow<CloudRunState> = _runState.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val prefs = settings.prefs

    private val parser = ClaudeStreamParser()
    private var relayClient: CloudRelayClient? = null
    private var relayJob: Job? = null
    private var lastPlan: GatewayPlan? = null
    private var viewer: String? = null

    /** Provider models the user may pick here; Anthropic models never appear. */
    val modelOptions: StateFlow<List<CloudModelOption>> = combine(
        providers.providers,
        providers.catalogs,
    ) { list, catalogs -> CloudGateway.options(list, catalogs) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            auth.state.collect { state ->
                viewer = state.login
                _status.value = _status.value.copy(signedIn = state.login != null, login = state.login,
                    message = state.message ?: _status.value.message)
                if (state.login == null) return@collect
                runCatching { refreshStatus() }
            }
        }
        scope.launch {
            settings.prefs.collect { saved ->
                currentClientId = saved.clientId.ifBlank { buildClientId }
            }
        }
    }

    // ---------------------------------------------------------------- sign-in

    fun signIn() = auth.startSignIn()
    fun cancelSignIn() = auth.cancelSignIn()

    suspend fun connectPat(token: String) {
        _status.value = _status.value.copy(busy = true, error = null)
        try {
            auth.connectPat(token)
            refreshStatus()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = _status.value.copy(error = auth.safeMessage(e))
        } finally {
            _status.value = _status.value.copy(busy = false)
        }
    }

    suspend fun signOut() {
        disconnectRelay()
        auth.disconnect()
        keys.putCloudRelaySecret(null)
        settings.update { it.copy(codespaceName = "") }
        _events.value = emptyList()
        _status.value = CloudStatus(message = "Signed out of the cloud section.")
    }

    suspend fun refreshStatus() {
        val token = auth.token() ?: run {
            _status.value = CloudStatus(signedIn = false)
            return
        }
        _status.value = _status.value.copy(busy = true, error = null)
        try {
            val login = viewer ?: api.viewer(token).also { viewer = it }
            val saved = settings.current()
            val codespaces = api.listCodespaces(token)
            val chosen = codespaces.firstOrNull { it.name == saved.codespaceName }
                ?: codespaces.firstOrNull { it.repository == "$login/${saved.repositoryName}" }
            _status.value = CloudStatus(
                signedIn = true,
                login = login,
                repository = chosen?.repository ?: _status.value.repository,
                codespace = chosen,
                phase = chosen?.phase ?: CodespacePhase.NONE,
                busy = false,
                message = when {
                    chosen == null -> "Not set up yet."
                    chosen.phase == CodespacePhase.READY -> "Running."
                    chosen.phase == CodespacePhase.STOPPED -> "Stopped."
                    else -> chosen.state
                },
            )
            if (chosen != null && chosen.name != saved.codespaceName) {
                settings.update { it.copy(codespaceName = chosen.name) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = _status.value.copy(busy = false, error = auth.safeMessage(e))
        }
    }

    // ------------------------------------------------------------ provisioning

    suspend fun provision(from: CloudSetupStep = CloudSetupStep.REPOSITORY): Boolean {
        val token = auth.token() ?: run {
            _status.value = _status.value.copy(error = "Sign in to GitHub first.")
            return false
        }
        val prefsNow = settings.current()
        _status.value = _status.value.copy(busy = true, error = null, message = "Preparing the cloud…")
        _progress.value = null
        val owner = viewer ?: api.viewer(token).also { viewer = it }
        val setup = CloudSetup(ops = object : CloudSetupOps {
            override suspend fun repository(fullName: String): RepositoryRef? =
                api.repository(token, fullName)?.let { repo ->
                    RepositoryRef(fullName, repo.long("id"), repo.bool("private"))
                }

            override suspend fun createRepository(name: String): RepositoryRef {
                val repo = api.createPrivateRepository(token, name)
                val fullName = repo.string("full_name") ?: "$owner/$name"
                return RepositoryRef(fullName, repo.long("id"), true)
            }

            override suspend fun writeFiles(fullName: String, files: Map<String, String>) {
                for ((path, content) in files) {
                    api.putFile(token, fullName, path, content, "Set up the cloud coding agent")
                }
            }

            override suspend fun existingCodespace(fullName: String): CodespaceInfo? =
                api.listCodespaces(token).firstOrNull { it.repository == fullName && it.phase != CodespacePhase.DELETED }

            override suspend fun createCodespace(repositoryId: Long, machine: String, idleTimeoutMinutes: Int) =
                api.createCodespace(token, repositoryId, machine.takeIf { it.isNotBlank() },
                    idleTimeoutMinutes = idleTimeoutMinutes)

            override suspend fun startCodespace(name: String): CodespaceInfo? = api.start(token, name)

            override suspend fun awaitAvailable(name: String): CodespaceInfo {
                val deadline = System.currentTimeMillis() + 8 * 60_000
                var last: CodespaceInfo? = null
                while (System.currentTimeMillis() < deadline) {
                    last = api.codespace(token, name) ?: last
                    when (last?.phase) {
                        CodespacePhase.READY -> return last
                        CodespacePhase.FAILED -> error("The Codespace failed to start (${last.state}). Open it on GitHub for details, then retry.")
                        else -> delay(5_000)
                    }
                }
                error("The Codespace did not become available in time. Retry from this step.")
            }

            override suspend fun openRelayPort(name: String): String {
                api.setPortPublic(token, name, CodespaceInfo.RELAY_PORT)
                return "https://${name.lowercase()}-${CodespaceInfo.RELAY_PORT}.app.github.dev"
            }

            override suspend fun awaitRelay(origin: String): Boolean {
                repeat(40) {
                    if (relayHealthy(origin)) return true
                    delay(5_000)
                }
                return false
            }
        }) { state -> _progress.value = state }

        val files = try {
            cloudFiles()
        } catch (e: Exception) {
            _status.value = _status.value.copy(busy = false, error = e.message)
            return false
        }

        return try {
            val result = setup.run(
                SetupRequest(owner = owner, repositoryName = prefsNow.repositoryName,
                    files = files, machine = prefsNow.machine, idleTimeoutMinutes = prefsNow.idleTimeoutMinutes),
                from = from,
            )
            settings.update { it.copy(codespaceName = result.codespace.name) }
            _status.value = _status.value.copy(busy = false, repository = result.repository,
                codespace = result.codespace, phase = CodespacePhase.READY, message = "Cloud ready.")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = _status.value.copy(busy = false, error = e.message)
            false
        }
    }

    /** Files committed to the private workspace repository; never contains a secret. */
    suspend fun cloudFiles(): Map<String, String> = withContext(Dispatchers.IO) {
        val secret = ensureRelaySecret()
        val hash = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
            .joinToString("") { "%02x".format(it) }
        mapOf(
            ".devcontainer/devcontainer.json" to asset("devcontainer.json"),
            ".cloud-claude/setup.sh" to asset("setup.sh"),
            ".cloud-claude/start-relay.sh" to asset("start-relay.sh"),
            ".cloud-claude/relay.mjs" to asset("relay.mjs"),
            ".cloud-claude/README.md" to asset("README.md"),
            ".cloud-claude/relay-token.sha256" to "$hash\n",
        )
    }

    private fun asset(name: String): String =
        context.assets.open("cloud-claude/$name").bufferedReader().use { it.readText() }

    private fun ensureRelaySecret(): String {
        keys.cloudRelaySecret()?.takeIf { it.length >= 32 }?.let { return it }
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        keys.putCloudRelaySecret(secret)
        return secret
    }

    // ------------------------------------------------------------- cloud state

    suspend fun startCloud() = cloudAction("Starting the cloud…") { token, name ->
        api.start(token, name)
        null
    }

    suspend fun stopCloud() = cloudAction("Stopping the cloud…") { token, name ->
        api.stop(token, name)
        "Cloud stopped."
    }

    suspend fun deleteCloud() = cloudAction("Deleting the cloud…") { token, name ->
        api.delete(token, name)
        settings.update { it.copy(codespaceName = "") }
        _events.value = emptyList()
        "Codespace deleted. The private workspace repository was kept."
    }

    private suspend fun cloudAction(
        message: String,
        block: suspend (String, String) -> String?,
    ): Boolean {
        val token = auth.token() ?: return false
        val name = _status.value.codespace?.name ?: settings.current().codespaceName.takeIf { it.isNotBlank() }
            ?: run {
                _status.value = _status.value.copy(error = "Set up the cloud first.")
                return false
            }
        _status.value = _status.value.copy(busy = true, error = null, message = message)
        return try {
            val result = block(token, name)
            disconnectRelay()
            refreshStatus()
            result?.let { _status.value = _status.value.copy(message = it) }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = _status.value.copy(busy = false, error = auth.safeMessage(e))
            false
        } finally {
            _status.value = _status.value.copy(busy = false)
        }
    }

    // ------------------------------------------------------------ conversation

    suspend fun send(prompt: String): Boolean {
        val text = prompt.trim()
        if (text.isEmpty()) return false
        val plan = buildPlan() ?: return false
        val permission = CloudPermissionMode.fromSetting(settings.current().permission.name)
        val client = ensureRelay() ?: return false
        val deadline = System.currentTimeMillis() + 20_000
        while (_relayStatus.value != RelayConnection.OPEN && System.currentTimeMillis() < deadline) {
            delay(500)
        }
        if (_relayStatus.value != RelayConnection.OPEN) {
            _status.value = _status.value.copy(error = "The cloud relay is not connected yet. Retry.")
            return false
        }
        if (_runState.value == CloudRunState.RUNNING) {
            return client.send(RelayProtocol.message(text, plan, permission))
        }
        _events.value = emptyList()
        parser.reset()
        _sessionId.value = null
        _runState.value = CloudRunState.RUNNING
        val sent = client.send(RelayProtocol.start(text, plan, permission))
        if (!sent) _status.value = _status.value.copy(error = "Could not reach the cloud relay.")
        return sent
    }

    fun interrupt() {
        relayClient?.send(RelayProtocol.simple(CloudCommands.INTERRUPT))
        _runState.value = CloudRunState.IDLE
    }

    fun answerPermission(requestId: String, allow: Boolean) {
        relayClient?.send(RelayProtocol.permission(requestId, allow))
        _events.value = _events.value.filterNot { it is CloudEvent.Permission && it.requestId == requestId }
    }

    fun resumeSession(id: String) {
        _sessionId.value = id
        relayClient?.send(RelayProtocol.resume(id))
    }

    fun clearTranscript() {
        _events.value = emptyList()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private suspend fun buildPlan(): GatewayPlan? {
        val options = modelOptions.value
        if (options.isEmpty()) {
            _status.value = _status.value.copy(error = "Add an OpenAI-compatible provider with an API key before using the cloud section.")
            return null
        }
        val prefsNow = settings.current()
        val main = options.firstOrNull { it.key == prefsNow.mainModel } ?: options.first()
        val fast = options.firstOrNull { it.key == prefsNow.fastModel } ?: main
        val configs = providers.providers.first()
        val connections = configs.associate { config ->
            config.id to connection(config)
        }
        return try {
            val plan = CloudGateway.build(main, fast) { providerId -> connections[providerId] }
            lastPlan = plan
            plan
        } catch (e: Exception) {
            _status.value = _status.value.copy(error = e.message)
            null
        }
    }

    private fun connection(config: ProviderConfig): CloudConnection? {
        val key = providers.apiKey(config.id)?.takeIf { it.isNotBlank() } ?: return null
        if (key == com.androidharness.app.llm.HarnessProvider.KEYLESS) return null
        return CloudConnection(config.baseUrl, config.type, key)
    }

    suspend fun connectRelay() {
        val client = ensureRelay() ?: return
        val deadline = System.currentTimeMillis() + 20_000
        while (client.status.value != RelayConnection.OPEN && System.currentTimeMillis() < deadline) {
            delay(500)
        }
        if (client.status.value != RelayConnection.OPEN) {
            _status.value = _status.value.copy(error = "The cloud relay is not connected yet. Retry.")
        }
    }

    private suspend fun ensureRelay(): CloudRelayClient? {
        relayClient?.takeIf { it.status.value == RelayConnection.OPEN }?.let { return it }
        val token = auth.token() ?: run {
            _status.value = _status.value.copy(error = "Sign in to GitHub first.")
            return null
        }
        val name = _status.value.codespace?.name ?: settings.current().codespaceName.takeIf { it.isNotBlank() }
            ?: run {
                _status.value = _status.value.copy(error = "Set up the cloud first.")
                return null
            }
        // Port visibility reverts after a Codespace restart; make it public again.
        runCatching { api.setPortPublic(token, name, CodespaceInfo.RELAY_PORT) }
        disconnectRelay()
        val origin = "https://${name.lowercase()}-${CodespaceInfo.RELAY_PORT}.app.github.dev"
        val relayToken = keys.cloudRelaySecret() ?: run {
            _status.value = _status.value.copy(error = "This device has no relay secret. Run Set up cloud again.")
            return null
        }
        val client = CloudRelayClient(origin, relayToken, scope).also { relayClient = it }
        relayJob = scope.launch {
            client.frames.collect { frame -> handleFrame(frame) }
        }
        scope.launch {
            client.status.collect { _relayStatus.value = it }
            if (client.status.value == RelayConnection.ERROR) {
                _notice.value = client.applicationError.value
            }
        }
        client.start()
        return client
    }

    private fun handleFrame(frame: CloudFrame) {
        when (val event = frame.event) {
            is CloudEvent.Raw -> parser.parseLine(event.json).forEach { append(it) }
            else -> append(event)
        }
    }

    private fun append(event: CloudEvent) {
        when (event) {
            is CloudEvent.Session -> {
                _sessionId.value = event.id
                return
            }
            is CloudEvent.State -> {
                _runState.value = event.value
                if (event.value == CloudRunState.IDLE) maybeAutoStop()
                return
            }
            is CloudEvent.Done -> _runState.value = CloudRunState.IDLE
            is CloudEvent.Error -> _notice.value = event.message
            else -> Unit
        }
        val list = _events.value.toMutableList()
        list += event
        if (list.size > MAX_EVENTS) list.subList(0, list.size - MAX_EVENTS).clear()
        _events.value = list
    }

    private fun maybeAutoStop() {
        if (_status.value.codespace?.name == null) return
        scope.launch {
            if (!settings.current().autoStop) return@launch
            runCatching { stopCloud() }
        }
    }

    private fun disconnectRelay() {
        relayJob?.cancel()
        relayJob = null
        relayClient?.stop()
        relayClient = null
        _relayStatus.value = RelayConnection.CLOSED
    }

    private suspend fun relayHealthy(origin: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${origin.trimEnd('/')}/health").build()
            OkHttpClient().newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Sends one tiny request through the gateway and checks the model can call a tool. */
    suspend fun probeModel(): String {
        val plan = lastPlan ?: buildPlan() ?: return "Choose a model first."
        val name = _status.value.codespace?.name ?: return "Set up the cloud first."
        val token = keys.cloudRelaySecret() ?: return "Run Set up cloud again."
        val origin = "https://${name.lowercase()}-${CodespaceInfo.RELAY_PORT}.app.github.dev"
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = """
                    {"model":"${plan.mainAlias}","max_tokens":64,"tools":[{"name":"ping","description":"Reply with pong","input_schema":{"type":"object","properties":{}}}],
                    "messages":[{"role":"user","content":"Call the ping tool."}]}
                """.trimIndent()
                val request = Request.Builder().url("$origin/probe")
                    .header("x-relay-token", token)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                OkHttpClient().newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        !response.isSuccessful -> "The gateway rejected the test: ${text.take(300)}"
                        text.contains("\"toolUse\":true") -> "Works: the model answered and called a tool."
                        text.contains("\"toolUse\":false") -> "The model answered but did not call a tool. Claude Code needs a strong tool-calling model."
                        else -> "Unexpected answer: ${text.take(300)}"
                    }
                }
            }.getOrElse { "The test could not run: ${it.message}" }
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content
    private fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
    private fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.content?.toBoolean() ?: false

    private companion object {
        const val MAX_EVENTS = 2000
    }
}
