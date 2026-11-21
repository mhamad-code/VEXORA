package com.androidharness.app.cloud

import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudGatewayTest {
    private val openAi = ProviderConfig("p-openai", "My provider", ProviderType.OPENAI_COMPAT,
        "https://api.example.com/v1", "gpt-x")
    private val anthropic = ProviderConfig("p-anthropic", "Anthropic", ProviderType.ANTHROPIC,
        "https://api.anthropic.com", "claude-sonnet-4")
    private val local = ProviderConfig("p-local", "On device", ProviderType.OPENAI_COMPAT,
        "local://device", "qwen")

    @Test
    fun `anthropic providers and models never appear in the cloud list`() {
        val options = CloudGateway.options(
            listOf(openAi, anthropic, local),
            mapOf(
                openAi.id to listOf(ModelEntry("gpt-x"), ModelEntry("claude-knockoff")),
                anthropic.id to listOf(ModelEntry("claude-sonnet-4")),
            ),
        )
        assertEquals(listOf("gpt-x"), options.map { it.model })
    }

    @Test
    fun `missing catalog falls back to the model the provider already uses`() {
        val options = CloudGateway.options(listOf(openAi), emptyMap())
        assertEquals(listOf("gpt-x"), options.map { it.model })
    }

    @Test
    fun `gateway config references env variables and keeps keys out of the yaml`() {
        val options = CloudGateway.options(listOf(openAi), mapOf(openAi.id to listOf(ModelEntry("gpt-x"))))
        val plan = CloudGateway.build(options[0], options[0]) { providerId ->
            if (providerId == openAi.id) CloudConnection("https://api.example.com/v1", ProviderType.OPENAI_COMPAT, "sk-secret") else null
        }
        assertTrue(plan.yaml.contains("model_name: \"vexora-main\""))
        assertTrue(plan.yaml.contains("model: \"openai/gpt-x\""))
        assertTrue(plan.yaml.contains("api_key: os.environ/VEXORA_KEY_MAIN"))
        assertFalse(plan.yaml.contains("sk-secret"))
        assertEquals("sk-secret", plan.secrets["VEXORA_KEY_MAIN"])
        assertEquals("vexora-main", plan.env["ANTHROPIC_MODEL"])
        assertEquals("vexora-fast", plan.env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
    }

    @Test
    fun `a claude model is rejected even if it slips through`() {
        val claude = CloudModelOption(openAi.id, openAi.name, "claude-sonnet-4")
        val failure = runCatching { CloudGateway.build(claude, claude) { null } }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `a provider without a key cannot be used`() {
        val options = CloudGateway.options(listOf(openAi), emptyMap())
        val failure = runCatching { CloudGateway.build(options[0], options[0]) { null } }
        assertTrue(failure.exceptionOrNull()?.message?.contains("no saved connection") == true)
    }

    @Test
    fun `a different fast model gets its own key entry`() {
        val options = CloudGateway.options(listOf(openAi),
            mapOf(openAi.id to listOf(ModelEntry("gpt-x"), ModelEntry("gpt-mini"))))
        val plan = CloudGateway.build(options[0], options[1]) { CloudConnection("https://api.example.com/v1", ProviderType.OPENAI_COMPAT, "sk") }
        assertTrue(plan.yaml.contains("os.environ/VEXORA_KEY_FAST"))
        assertTrue(plan.secrets.containsKey("VEXORA_KEY_FAST"))
    }
}
