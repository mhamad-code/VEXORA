package com.agent.mhamed.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.agent.mhamed.github.GitHubAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Self-development tools: let the in-app agent drive VEXORA's own CI/CD.
 * Reuse GitHub auth (token with repo+workflow scopes) and the system package
 * installer (REQUEST_INSTALL_PACKAGES). All network calls go to api.github.com.
 */
private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()

class GitHubWorkflowDispatchTool(
    private val github: () -> GitHubAuth,
    private val defaultOwner: String = "mhamad-code",
    private val defaultRepo: String = "VEXORA",
) : Tool {
    override val name = "github_workflow_dispatch"
    override val description =
        "Trigger a GitHub Actions workflow (workflow_dispatch) on a repository. " +
            "Defaults to mhamad-code/VEXORA build.yml on main. Requires a GitHub token with 'workflow' scope."
    override val parametersSchema = Schema.obj(
        mapOf(
            "owner" to Schema.string("Repository owner (default mhamad-code)."),
            "repo" to Schema.string("Repository name (default VEXORA)."),
            "workflow" to Schema.string("Workflow file name or id (default build.yml)."),
            "ref" to Schema.string("Git ref to build (default main)."),
            "inputs" to Schema.obj(emptyMap(), emptyList()),
        ),
        required = emptyList(),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val owner = args.string("owner") ?: defaultOwner
        val repo = args.string("repo") ?: defaultRepo
        val workflow = args.string("workflow") ?: "build.yml"
        val ref = args.string("ref") ?: "main"
        val token = github().accessToken() ?: throw ToolFailure("Connect GitHub in Settings first.")
        val body = buildJsonObject {
            put("ref", ref)
            val inputs = args["inputs"] as? JsonObject
            if (inputs != null) put("inputs", inputs)
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflow/dispatches")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = resp.body?.string().orEmpty()
                throw ToolFailure("GitHub returned HTTP ${resp.code}: $msg")
            }
        }
        ToolResult(true, "Dispatched workflow '$workflow' on $owner/$repo@$ref. Use github_workflow_runs to monitor.")
    }
}

class GitHubWorkflowRunsTool(
    private val github: () -> GitHubAuth,
    private val defaultOwner: String = "mhamad-code",
    private val defaultRepo: String = "VEXORA",
) : Tool {
    override val name = "github_workflow_runs"
    override val description =
        "List recent GitHub Actions runs for a repository/workflow with status and conclusion. " +
            "Use after github_workflow_dispatch to monitor the build."
    override val parametersSchema = Schema.obj(
        mapOf(
            "owner" to Schema.string("Repository owner (default mhamad-code)."),
            "repo" to Schema.string("Repository name (default VEXORA)."),
            "workflow" to Schema.string("Workflow file name (optional, filters runs)."),
            "per_page" to Schema.integer("Number of runs to return (default 5)."),
        ),
        required = emptyList(),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val owner = args.string("owner") ?: defaultOwner
        val repo = args.string("repo") ?: defaultRepo
        val workflow = args.string("workflow")
        val perPage = (args["per_page"] as? JsonPrimitive)?.intOrNull ?: 5
        val token = github().accessToken()
        val path = if (workflow.isNullOrBlank()) {
            "/repos/$owner/$repo/actions/runs?per_page=$perPage"
        } else {
            "/repos/$owner/$repo/actions/workflows/$workflow/runs?per_page=$perPage"
        }
        val request = Request.Builder()
            .url("https://api.github.com$path")
            .header("Accept", "application/vnd.github+json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val body = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw ToolFailure("GitHub returned HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
            resp.body?.string().orEmpty()
        }
        val json = Json.parseToJsonElement(body).jsonObject
        val runs = json["workflow_runs"]?.jsonArray.orEmpty()
        val sb = StringBuilder()
        sb.appendLine("Runs for $owner/$repo${workflow?.let { " ($it)" } ?: ""}:")
        for (r in runs) {
            val o = r.jsonObject
            sb.appendLine("- id=${o["id"]?.jsonPrimitive?.content} status=${o["status"]?.jsonPrimitive?.content} " +
                "conclusion=${o["conclusion"]?.jsonPrimitive?.contentOrNull ?: "-"} " +
                "branch=${o["head_branch"]?.jsonPrimitive?.content} sha=${o["head_sha"]?.jsonPrimitive?.content?.take(7)} " +
                "created=${o["created_at"]?.jsonPrimitive?.content} url=${o["html_url"]?.jsonPrimitive?.content}")
        }
        if (runs.isEmpty()) sb.appendLine("(no runs)")
        ToolResult(true, sb.toString().trim())
    }
}

class GitHubArtifactDownloadTool(
    private val github: () -> GitHubAuth,
    private val context: Context,
    private val defaultOwner: String = "mhamad-code",
    private val defaultRepo: String = "VEXORA",
) : Tool {
    override val name = "github_artifact_download"
    override val description =
        "Download a GitHub Actions run's artifact (zip), extract VEXORA.apk, and return its local path. " +
            "Pass run_id from github_workflow_runs."
    override val parametersSchema = Schema.obj(
        mapOf(
            "owner" to Schema.string("Repository owner (default mhamad-code)."),
            "repo" to Schema.string("Repository name (default VEXORA)."),
            "run_id" to Schema.integer("Workflow run id (from github_workflow_runs)."),
            "artifact_name" to Schema.string("Artifact name to download (default VEXORA)."),
        ),
        required = listOf("run_id"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val owner = args.string("owner") ?: defaultOwner
        val repo = args.string("repo") ?: defaultRepo
        val runId = (args["run_id"] as? JsonPrimitive)?.longOrNull ?: throw ToolFailure("run_id is required.")
        val artifactName = args.string("artifact_name") ?: "VEXORA"
        val token = github().accessToken()
        // 1. list artifacts for the run
        val listReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts")
            .header("Accept", "application/vnd.github+json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val listBody = http.newCall(listReq).execute().use { resp ->
            if (!resp.isSuccessful) throw ToolFailure("GitHub returned HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
            resp.body?.string().orEmpty()
        }
        val artifacts = Json.parseToJsonElement(listBody).jsonObject["artifacts"]?.jsonArray.orEmpty()
        val artifact = artifacts.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == artifactName }
            ?: artifacts.firstOrNull()
            ?: throw ToolFailure("No artifacts found for run $runId.")
        val downloadUrl = artifact.jsonObject["archive_download_url"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Artifact has no download url.")
        // 2. download zip
        val dlReq = Request.Builder()
            .url(downloadUrl)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        val zipBytes = http.newCall(dlReq).execute().use { resp ->
            if (!resp.isSuccessful) throw ToolFailure("Download failed HTTP ${resp.code}")
            resp.body?.bytes() ?: throw ToolFailure("Empty download.")
        }
        // 3. extract VEXORA.apk (or first .apk) into the staged-update folder that
        // the app's FileProvider exposes (so the system installer can read it).
        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "update").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        var apkFile: File? = null
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    val target = File(outDir, File(entry.name).name)
                    FileOutputStream(target).use { fos -> zis.copyTo(fos) }
                    apkFile = target
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val apk = apkFile ?: throw ToolFailure("No .apk found inside artifact zip.")
        ToolResult(true, "Downloaded and extracted ${apk.name} (${apk.length()} bytes) to ${apk.absolutePath}. Use install_apk to install it.")
    }
}

class InstallApkTool(private val context: Context) : Tool {
    override val name = "install_apk"
    override val description =
        "Install an APK file via the system package installer. Pass the local path returned by github_artifact_download. " +
            "Requires REQUEST_INSTALL_PACKAGES permission and user confirmation."
    override val parametersSchema = Schema.obj(
        mapOf("apk_path" to Schema.string("Absolute path to the APK file to install.")),
        required = listOf("apk_path"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.Main) {
        val path = args.string("apk_path") ?: throw ToolFailure("apk_path is required.")
        val file = File(path)
        check(file.exists()) { "APK not found: $path" }
        val archive = context.packageManager.getPackageArchiveInfo(
            file.absolutePath, android.content.pm.PackageManager.GET_ACTIVITIES,
        ) ?: throw ToolFailure("That file is not a valid APK.")
        check(archive.packageName == context.packageName) {
            "That APK is for ${archive.packageName}, not ${context.packageName}."
        }
        val authority = "${context.packageName}.update"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ToolResult(true, "Launched system installer for ${file.name}. Confirm the install on screen.")
    }
}

