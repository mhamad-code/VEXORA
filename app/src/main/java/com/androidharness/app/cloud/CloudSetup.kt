package com.androidharness.app.cloud

/**
 * One-click provisioning, expressed as steps so the UI can show a stepper and a
 * failed run can resume from the step that failed instead of starting over
 * (a used-up Codespaces quota must not be paid twice).
 */
interface CloudSetupOps {
    suspend fun repository(fullName: String): RepositoryRef?
    suspend fun createRepository(name: String): RepositoryRef
    suspend fun writeFiles(fullName: String, files: Map<String, String>)
    suspend fun existingCodespace(fullName: String): CodespaceInfo?
    suspend fun createCodespace(repositoryId: Long, machine: String, idleTimeoutMinutes: Int): CodespaceInfo
    suspend fun startCodespace(name: String): CodespaceInfo?
    suspend fun awaitAvailable(name: String): CodespaceInfo
    suspend fun openRelayPort(name: String): String
    suspend fun awaitRelay(origin: String): Boolean
}

data class RepositoryRef(val fullName: String, val id: Long, val private: Boolean)

data class SetupRequest(
    val owner: String,
    val repositoryName: String,
    val files: Map<String, String>,
    val machine: String = "",
    val idleTimeoutMinutes: Int = 30,
)

data class SetupResult(val repository: String, val codespace: CodespaceInfo, val relayOrigin: String)

class CloudSetup(
    private val ops: CloudSetupOps,
    private val onProgress: (CloudSetupProgress) -> Unit = {},
) {
    /**
     * Runs (or resumes) provisioning. [from] is the first step to execute; the
     * statuses of earlier steps stay DONE so the stepper tells the truth.
     */
    suspend fun run(request: SetupRequest, from: CloudSetupStep = CloudSetupStep.REPOSITORY): SetupResult {
        require(request.repositoryName.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "Use a repository name with letters, numbers, dots, dashes or underscores."
        }
        var progress = resumeState(from)
        fun step(step: CloudSetupStep, detail: String? = null) {
            progress = progress.with(step, CloudStepStatus.RUNNING, detail)
            onProgress(progress)
        }
        fun done(step: CloudSetupStep, detail: String? = null) {
            progress = progress.with(step, CloudStepStatus.DONE, detail, error = null)
            onProgress(progress)
        }
        suspend fun <T> attempt(step: CloudSetupStep, block: suspend () -> T): T = try {
            step(step)
            block().also { done(step) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            progress = progress.with(step, CloudStepStatus.FAILED, error = e.message ?: "This step failed.")
            onProgress(progress)
            throw SetupFailure(step, e)
        }

        var repository: RepositoryRef? = null
        var codespace: CodespaceInfo? = null
        var origin = ""

        if (from <= CloudSetupStep.REPOSITORY) {
            repository = attempt(CloudSetupStep.REPOSITORY) {
                ops.repository("${request.owner}/${request.repositoryName}")
                    ?: ops.createRepository(request.repositoryName)
            }.also { ref ->
                require(ref.private) {
                    "The workspace repository already exists and is public. Cloud workspaces must be private; rename the repository or make it private."
                }
            }
        }

        if (repository == null) {
            // Resuming from a later step still needs the repository identity.
            repository = attempt(CloudSetupStep.REPOSITORY) {
                ops.repository("${request.owner}/${request.repositoryName}")
                    ?: error("The workspace repository is missing. Run setup from the first step.")
            }
        }

        val repo = repository!!
        if (from <= CloudSetupStep.FILES) {
            attempt(CloudSetupStep.FILES) { ops.writeFiles(repo.fullName, request.files) }
        }
        if (from <= CloudSetupStep.CODESPACE) {
            codespace = attempt(CloudSetupStep.CODESPACE) {
                ops.existingCodespace(repo.fullName) ?: ops.createCodespace(repo.id, request.machine,
                    request.idleTimeoutMinutes)
            }
        }
        if (codespace == null) {
            codespace = ops.existingCodespace(repo.fullName)
                ?: throw SetupFailure(CloudSetupStep.CODESPACE, IllegalStateException("No Codespace exists for this workspace. Run setup again."))
        }

        val running = codespace!!
        if (from <= CloudSetupStep.STARTING) {
            attempt(CloudSetupStep.STARTING) {
                if (running.phase == CodespacePhase.READY) running else {
                    if (running.phase == CodespacePhase.STOPPED) ops.startCodespace(running.name)
                    ops.awaitAvailable(running.name)
                }
            }
        }
        if (from <= CloudSetupStep.RELAY) {
            origin = attempt(CloudSetupStep.RELAY) {
                val relayOrigin = ops.openRelayPort(running.name)
                check(ops.awaitRelay(relayOrigin)) {
                    "The Codespace is running but its relay did not answer. Open the Codespace, run `gh codespace ports visibility 8765:public` or set port 8765 to Public in the Ports tab, then retry this step. The relay stays reachable only with this device's random token."
                }
                relayOrigin
            }
        } else {
            origin = ops.openRelayPort(running.name)
        }

        progress = progress.with(CloudSetupStep.RELAY, CloudStepStatus.DONE)
            .with(CloudSetupStep.READY, CloudStepStatus.DONE, detail = "Cloud ready")
        onProgress(progress)
        return SetupResult(repo.fullName, running, origin)
    }

    private fun resumeState(from: CloudSetupStep): CloudSetupProgress {
        val statuses = CloudSetupStep.entries.associateWith { step ->
            if (step.ordinal < from.ordinal) CloudStepStatus.DONE else CloudStepStatus.PENDING
        }
        return CloudSetupProgress(current = from, statuses = statuses)
    }
}

class SetupFailure(val step: CloudSetupStep, cause: Throwable) :
    Exception(cause.message ?: "Setup failed at ${step.name.lowercase()}.", cause)
