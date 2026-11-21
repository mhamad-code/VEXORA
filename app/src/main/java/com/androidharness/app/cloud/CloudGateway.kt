package com.androidharness.app.cloud

import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType

/** One model the cloud section can drive, resolved from the app's providers. */
data class CloudModelOption(
    val providerId: String,
    val providerName: String,
    val model: String,
    val contextTokens: Long? = null,
    val supportsTools: Boolean? = null,
) {
    val key: String get() = "$providerId/$model"
    val label: String get() = "$providerName · $model"
}

/** The saved connection a gateway entry needs. */
data class CloudConnection(val baseUrl: String, val type: ProviderType, val apiKey: String)

/**
 * Generates the LiteLLM proxy that lets Claude Code speak Anthropic Messages to
 * the user's own providers. Claude Code cannot call the app's providers
 * directly, so it talks to `ANTHROPIC_BASE_URL` and the proxy translates.
 *
 * Anthropic-hosted models are never offered here: the docs require this section
 * to run on the user's own providers only.
 */
object CloudGateway {
    const val MAIN_ALIAS = "vexora-main"
    const val FAST_ALIAS = "vexora-fast"
    const val GATEWAY_PORT = 4000
    const val MAIN_KEY_ENV = "VEXORA_KEY_MAIN"
    const val FAST_KEY_ENV = "VEXORA_KEY_FAST"
    const val TOKEN_ENV = "VEXORA_GATEWAY_TOKEN"

    private val anthropicMarker = Regex("claude|anthropic", RegexOption.IGNORE_CASE)

    fun isAnthropicModel(model: String): Boolean = anthropicMarker.containsMatchIn(model)

    fun isAnthropicProvider(provider: ProviderConfig): Boolean =
        provider.type == ProviderType.ANTHROPIC || anthropicMarker.containsMatchIn(provider.name)

    /**
     * Every model a user may pick for the cloud section. On-device models and
     * Anthropic models are excluded; a provider with no catalog falls back to
     * the model it already uses in chat.
     */
    fun options(
        providers: List<ProviderConfig>,
        catalogs: Map<String, List<ModelEntry>>,
    ): List<CloudModelOption> = providers
        .filterNot { isAnthropicProvider(it) }
        .filterNot { it.baseUrl.startsWith("local://") }
        .flatMap { provider ->
            val models = catalogs[provider.id].orEmpty().ifEmpty {
                provider.model.takeIf { it.isNotBlank() }?.let { listOf(ModelEntry(it)) }.orEmpty()
            }
            models
                .filterNot { isAnthropicModel(it.id) }
                .map { CloudModelOption(provider.id, provider.name, it.id, it.contextTokens, it.tools) }
        }

    /** Resolve a saved selection back to a live option, so a deleted provider cannot be used. */
    fun resolve(option: CloudModelOption?, available: List<CloudModelOption>): CloudModelOption? =
        option?.let { saved -> available.firstOrNull { it.key == saved.key } } ?: available.firstOrNull()

    /**
     * Builds the proxy config. Keys never enter the YAML: they are referenced as
     * environment variables and delivered separately over the authenticated
     * relay socket.
     */
    fun build(
        main: CloudModelOption,
        fast: CloudModelOption,
        connection: (String) -> CloudConnection?,
    ): GatewayPlan {
        require(!isAnthropicModel(main.model) && !isAnthropicModel(fast.model)) {
            "Claude and Anthropic models are not available in the cloud section. Pick one of your own providers."
        }
        val mainConnection = connection(main.providerId)
        val fastConnection = connection(fast.providerId)
        require(mainConnection != null) { "${main.providerName} has no saved connection. Reopen its settings." }
        require(fastConnection != null) { "${fast.providerName} has no saved connection. Reopen its settings." }
        require(mainConnection.apiKey.isNotBlank()) { "${main.providerName} needs an API key before the cloud can use it." }
        require(fastConnection.apiKey.isNotBlank()) { "${fast.providerName} needs an API key before the cloud can use it." }

        val entries = mutableListOf<GatewayEntry>()
        entries += GatewayEntry(MAIN_ALIAS, main, mainConnection, MAIN_KEY_ENV)
        if (fast.key != main.key) entries += GatewayEntry(FAST_ALIAS, fast, fastConnection, FAST_KEY_ENV)
        else entries += GatewayEntry(FAST_ALIAS, fast, fastConnection, MAIN_KEY_ENV)

        val secrets = buildMap {
            entries.forEach { entry ->
                if (entry.keyEnv == MAIN_KEY_ENV) put(MAIN_KEY_ENV, mainConnection.apiKey)
                else put(FAST_KEY_ENV, fastConnection.apiKey)
            }
        }

        return GatewayPlan(
            mainAlias = MAIN_ALIAS,
            fastAlias = FAST_ALIAS,
            yaml = yaml(entries, secrets),
            env = mapOf(
                "ANTHROPIC_BASE_URL" to "http://127.0.0.1:$GATEWAY_PORT",
                "ANTHROPIC_MODEL" to MAIN_ALIAS,
                "ANTHROPIC_DEFAULT_HAIKU_MODEL" to FAST_ALIAS,
                "CLAUDE_CODE_SUBAGENT_MODEL" to MAIN_ALIAS,
            ),
            secrets = secrets,
            mainContextTokens = main.contextTokens,
            fastContextTokens = fast.contextTokens,
        )
    }

    fun yaml(entries: List<GatewayEntry>, secrets: Map<String, String>): String = buildString {
        appendLine("# Generated by the app for this cloud session. Keys are environment variables only.")
        appendLine("model_list:")
        entries.forEach { entry ->
            appendLine("  - model_name: ${quote(entry.alias)}")
            appendLine("    litellm_params:")
            appendLine("      model: ${quote(upstream(entry.connection.type, entry.model.model))}")
            appendLine("      api_base: ${quote(entry.connection.baseUrl.trimEnd('/'))}")
            appendLine("      api_key: os.environ/${entry.keyEnv}")
            appendLine("      supports_function_calling: ${entry.model.supportsTools != false}")
            entry.model.contextTokens?.takeIf { it > 0 }?.let { tokens ->
                appendLine("    model_info:")
                appendLine("      max_input_tokens: $tokens")
                appendLine("      max_tokens: ${minOf(tokens, 32_768L)}")
            }
        }
        appendLine("litellm_settings:")
        appendLine("  drop_params: true")
        // Providers here speak /chat/completions; without this LiteLLM would ask them for /responses.
        appendLine("  use_chat_completions_url_for_anthropic_messages: true")
        appendLine("  request_timeout: 600")
        appendLine("general_settings:")
        appendLine("  master_key: os.environ/$TOKEN_ENV")
    }

    fun upstream(type: ProviderType, model: String): String = when (type) {
        ProviderType.GEMINI -> "gemini/$model"
        ProviderType.ANTHROPIC -> error("Anthropic models are not available in the cloud section.")
        ProviderType.OPENAI_COMPAT, ProviderType.OPENAI_RESPONSES -> "openai/$model"
    }

    /** Quote a YAML scalar; model ids may contain `:` and `/`, so never leave them bare. */
    fun quote(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ") + "\""

    data class GatewayEntry(
        val alias: String,
        val model: CloudModelOption,
        val connection: CloudConnection,
        val keyEnv: String,
    )
}

/** Everything the relay needs to run Claude Code against the user's models. */
data class GatewayPlan(
    val mainAlias: String,
    val fastAlias: String,
    val yaml: String,
    /** Env for the Claude Code process. */
    val env: Map<String, String>,
    /** Sent only over the authenticated socket; never written to the repository. */
    val secrets: Map<String, String>,
    val mainContextTokens: Long?,
    val fastContextTokens: Long?,
)
