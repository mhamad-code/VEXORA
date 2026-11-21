package com.androidharness.app.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSetupTest {
    private class FakeOps : CloudSetupOps {
        val calls = mutableListOf<String>()
        var existingRepo: RepositoryRef? = null
        var createRepoFails = false
        var createCodespaceFails = false
        var repositoryPrivate = true
        var codespace: CodespaceInfo? = null
        var relayUp = true

        override suspend fun repository(fullName: String): RepositoryRef? {
            calls += "repository"
            return existingRepo ?: RepositoryRef(fullName, 1, repositoryPrivate)
        }

        override suspend fun createRepository(name: String): RepositoryRef {
            calls += "createRepository"
            if (createRepoFails) error("quota exhausted")
            return RepositoryRef("me/$name", 2, true)
        }

        override suspend fun writeFiles(fullName: String, files: Map<String, String>) {
            calls += "writeFiles:${files.size}"
        }

        override suspend fun existingCodespace(fullName: String): CodespaceInfo? {
            calls += "existingCodespace"
            return codespace
        }

        override suspend fun createCodespace(repositoryId: Long, machine: String, idleTimeoutMinutes: Int): CodespaceInfo {
            calls += "createCodespace"
            if (createCodespaceFails) error("no capacity in this region")
            return CodespaceInfo("sturdy-cloud", "Provisioning", repository = "me/cloud-workspace")
        }

        override suspend fun startCodespace(name: String): CodespaceInfo? {
            calls += "startCodespace"
            return CodespaceInfo(name, "Starting")
        }

        override suspend fun awaitAvailable(name: String): CodespaceInfo {
            calls += "awaitAvailable"
            return CodespaceInfo(name, "Available", repository = "me/cloud-workspace")
        }

        override suspend fun openRelayPort(name: String): String {
            calls += "openRelayPort"
            return "https://$name-8765.app.github.dev"
        }

        override suspend fun awaitRelay(origin: String): Boolean {
            calls += "awaitRelay"
            return relayUp
        }
    }

    private fun request(files: Map<String, String> = mapOf(".devcontainer/devcontainer.json" to "{}")) =
        SetupRequest(owner = "me", repositoryName = "cloud-workspace", files = files)

    @Test
    fun `a fresh setup walks every step in order`() = runBlocking {
        val ops = FakeOps()
        var last: CloudSetupProgress? = null
        val result = CloudSetup(ops) { last = it }.run(request())
        assertEquals(
            listOf("repository", "writeFiles:1", "existingCodespace", "createCodespace",
                "awaitAvailable", "openRelayPort", "awaitRelay"),
            ops.calls,
        )
        assertEquals("me/cloud-workspace", result.repository)
        assertEquals("https://sturdy-cloud-8765.app.github.dev", result.relayOrigin)
        assertEquals(CloudStepStatus.DONE, last?.statuses?.get(CloudSetupStep.READY))
    }

    @Test
    fun `a failure marks its step and can resume from it`() = runBlocking {
        val ops = FakeOps().apply { createCodespaceFails = true }
        val failure = runCatching { CloudSetup(ops).run(request()) }
        assertTrue(failure.exceptionOrNull() is SetupFailure)
        assertEquals(CloudSetupStep.CODESPACE, (failure.exceptionOrNull() as SetupFailure).step)
        assertFalse("relay" in ops.calls)

        ops.createCodespaceFails = false
        ops.calls.clear()
        val result = CloudSetup(ops).run(request(), from = CloudSetupStep.CODESPACE)
        assertTrue(result.relayOrigin.endsWith("/health").not())
        assertFalse("createRepository" in ops.calls)
        assertFalse("writeFiles:1" in ops.calls)
    }

    @Test
    fun `an existing public repository is refused`() = runBlocking {
        val ops = FakeOps().apply {
            existingRepo = RepositoryRef("me/cloud-workspace", 1, false)
            repositoryPrivate = false
        }
        val failure = runCatching { CloudSetup(ops).run(request()) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message?.contains("private") == true)
    }

    @Test
    fun `a dead relay fails the relay step instead of reporting ready`() = runBlocking {
        val ops = FakeOps().apply { relayUp = false }
        val failure = runCatching { CloudSetup(ops).run(request()) }
        assertEquals(CloudSetupStep.RELAY, (failure.exceptionOrNull() as SetupFailure).step)
    }
}
