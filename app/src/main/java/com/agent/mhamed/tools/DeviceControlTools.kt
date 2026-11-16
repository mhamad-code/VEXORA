package com.agent.mhamed.tools

import com.agent.mhamed.data.AppSettings
import com.agent.mhamed.data.env.LinuxEnvironmentManager
import com.agent.mhamed.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Android device-control tools (Feature A). All execution goes through the
 * existing shell tier router (Shizuku / Wireless ADB / Root / app uid), so no
 * new dangerous permissions are added. The feature is disabled by default and
 * every tool re-checks the master switch.
 */
object DeviceControlSession {
    private val steps = AtomicInteger(0)
    private val startMs = AtomicLong(0L)
    fun reset() { steps.set(0); startMs.set(System.currentTimeMillis()) }
    fun tick(maxSteps: Int, maxDurationSec: Int): String? {
        if (startMs.get() == 0L) reset()
        val n = steps.incrementAndGet()
        val elapsed = (System.currentTimeMillis() - startMs.get()) / 1000
        if (n > maxSteps) return "Device-control step limit reached ($maxSteps). Stop the task or raise the limit in Settings."
        if (elapsed > maxDurationSec) return "Device-control duration limit reached ($maxDurationSec s). Stop the task or raise the limit in Settings."
        return null
    }
}

class DeviceControlTools(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
    private val settings: suspend () -> AppSettings,
    private val imageStore: com.agent.mhamed.data.ImageStore,
) {
    private suspend fun guard(): String? {
        val s = settings()
        if (!s.deviceControlEnabled) {
            return "Device control is disabled. Enable it in Settings -> Terminal & device -> Device control (and connect Shizuku/Wireless ADB/Root for stronger control)."
        }
        return DeviceControlSession.tick(s.deviceControlMaxSteps, s.deviceControlMaxDurationSeconds)
    }

    // We cannot reuse runGitWithRetry's git-specific handling well, but it runs the command. For non-git, use router directly.
    private suspend fun sh(cmd: String, ctx: ToolContext, timeoutMs: Int = 30_000, maxOut: Int = 2_000_000): com.agent.mhamed.data.env.ShellRunResult =
        withContext(Dispatchers.IO) { router.runWorkspace(cmd, ctx.workspace, timeoutMs = timeoutMs, maxOutput = maxOut) }

    class DeviceScreenshotTool(private val outer: DeviceControlTools) : Tool {
        override val name = "device_screenshot"
        override val description = "Capture a screenshot of the device screen and attach it for visual analysis. Requires device control enabled."
        override val parametersSchema = Schema.obj(emptyMap())
        override val isReadOnly = true
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            outer.guard()?.let { return ToolResult(false, it) }
            val res = outer.sh("screencap -p | base64", ctx)
            val b64 = res.rawOutput.replace("\n", "").replace("\r", "").trim()
            if (b64.isBlank()) return ToolResult(false, "screencap produced no output: ${res.rawStderr}")
            val bytes = try { Base64.getDecoder().decode(b64) } catch (e: Exception) {
                return ToolResult(false, "Failed to decode screenshot: ${e.message}")
            }
            val name = "${UUID.randomUUID()}.png"
            File(outer.imageStore.imagesDir, name).writeBytes(bytes)
            return ToolResult(true, "Screenshot captured (${bytes.size} bytes).", image = com.agent.mhamed.core.ImageRef(name, "image/png"))
        }
    }

    class DeviceUiTreeTool(private val outer: DeviceControlTools) : Tool {
        override val name = "device_ui_tree"
        override val description = "Dump the current UI as a text tree (class, text, resource-id, bounds, clickable). Use when vision is unavailable or to find tap targets."
        override val parametersSchema = Schema.obj(emptyMap())
        override val isReadOnly = true
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            outer.guard()?.let { return ToolResult(false, it) }
            val res = outer.sh("uiautomator dump /data/local/tmp/vexora_ui.xml >/dev/null 2>&1; cat /data/local/tmp/vexora_ui.xml", ctx, timeoutMs = 30_000, maxOut = 2_000_000)
            val xml = res.rawOutput
            if (xml.isBlank()) return ToolResult(false, "uiautomator produced no output: ${res.rawStderr}")
            // Extract node elements with text/resource-id/bounds
            val sb = StringBuilder()
            val nodeRx = Regex("""<node [^>]*>""")
            var count = 0
            for (m in nodeRx.findAll(xml)) {
                val tag = m.value
                fun attr(n: String): String = Regex("""$n="([^"]*)"""").find(tag)?.groupValues?.get(1).orEmpty()
                val text = attr("text")
                val rid = attr("resource-id")
                val desc = attr("content-desc")
                val bounds = attr("bounds")
                val clickable = attr("clickable")
                val cls = attr("class").substringAfterLast('.')
                if (text.isBlank() && desc.isBlank() && rid.isBlank()) continue
                sb.appendLine("- $cls ${if (clickable == "true") "[clickable]" else ""} text=\"${text.take(80)}\" desc=\"${desc.take(80)}\" id=\"$rid\" bounds=$bounds")
                count++
                if (count > 400) { sb.appendLine("... (truncated)"); break }
            }
            if (count == 0) sb.appendLine("(no labeled nodes)")
            return ToolResult(true, "UI tree ($count nodes):\n${sb.toString().trim()}")
        }
    }

    class DeviceTapTool(private val outer: DeviceControlTools) : Tool {
        override val name = "device_tap"
        override val description = "Tap at (x,y) on screen. Coordinates in pixels."
        override val parametersSchema = Schema.obj(mapOf(
            "x" to Schema.integer("X coordinate in pixels."),
            "y" to Schema.integer("Y coordinate in pixels."),
        ), required = listOf("x", "y"))
        override val isReadOnly = false
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            outer.guard()?.let { return ToolResult(false, it) }
            val x = (args["x"] as? JsonPrimitive)?.intOrNull ?: return ToolResult(false, "x required")
            val y = (args["y"] as? JsonPrimitive)?.intOrNull ?: return ToolResult(false, "y required")
            val res = outer.sh("input tap $x $y", ctx)
            return ToolResult(res.exitCode == 0, if (res.exitCode == 0) "Tapped ($x,$y)." else "tap failed: ${res.rawStderr}")
        }
    }

    class DeviceSwipeTool(private val outer: DeviceControlTools) : Tool {
        override val name = "device_swipe"
        override val description = "Swipe from (x1,y1) to (x2,y2) over duration ms."
        override val parametersSchema = Schema.obj(mapOf(
            "x1" to Schema.integer("Start X."), "y1" to Schema.integer("Start Y."),
            "x2" to Schema.integer("End X."), "y2" to Schema.integer("End Y."),
            "duration" to Schema.integer("Duration in ms (default 300)."),
        ), required = listOf("x1", "y1", "x2", "y2"))
        override val isReadOnly = false
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            outer.guard()?.let { return ToolResult(false, it) }
            val x1 = (args["x1"] as? JsonPrimitive)?.intOrNull ?: 0
            val y1 = (args["y1"] as? JsonPrimitive)?.intOrNull ?: 0
            val x2 = (args["x2"] as? JsonPrimitive)?.intOrNull ?: 0
            val y2 = (args["y2"] as? JsonPrimitive)?.intOrNull ?: 0
            val dur = (args["duration"] as? JsonPrimitive)?.intOrNull ?: 300
            val res = outer.sh("input swipe $x1 $y1 $x2 $y2 $dur", ctx)
            return ToolResult(res.exitCode == 0, if (res.exitCode == 0) "Swiped." else "swipe failed: ${res.rawStderr}")
        }
    }

    class DeviceTypeTextTool(private val outer: DeviceControlTools) : Tool {
        override val name = "device_type_text"
        override val description = "Type text into the focused field. Refuses passwords/OTP-like strings."
        override val parametersSchema = Schema.obj(mapOf(
            "text" to Schema.string("Text to type."),
        ), required = listOf("text"))
        override val isReadOnly = false
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            outer.guard()?.let { return ToolResult(false, it) }
            val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "text required")
            // safety: refuse obvious secrets/OTP
            if (text.matches(Regex("""\d{4,8}""")) ) return ToolResult(false, "Refusing to type a numeric code (possible OTP). Type it yourself.")
            if (Regex("""(?i)password|passwd|secret|token""").containsMatchIn(text)) return ToolResult(false, "Refusing to type a secret-like value.")
            // escape for `input text`
            val escaped = text.replace(" ", "%s").replace("&", "\\&").replace("<", "\\<").replace(">", "\\>").replace(";", "\\;")
            val res = outer.sh("input text '$escaped'", ctx)
            return ToolResult(res.exitCode == 0, if (res.exitCode == 0) "Typed text." else "type failed: ${res.rawStderr}")
        }
    }

    class DeviceKeyTool(private val outer: DeviceControlTools) : Tool {
        override val name = "device_key"
        override val description = "Press a key: back, home, recents, enter, volume_up, volume_down, power, tab."
        override val parametersSchema = Schema.obj(mapOf(
            "key" to Schema.string("Key name."),
        ), required = listOf("key"))
        override val isReadOnly = false
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            outer.guard()?.let { return ToolResult(false, it) }
            val key = args["key"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return ToolResult(false, "key required")
            val code = when (key) {
                "back" -> 4; "home" -> 3; "recents", "recent" -> 187; "enter" -> 66; "tab" -> 61
                "volume_up" -> 24; "volume_down" -> 25; "power" -> 26; "menu" -> 82
                else -> null
            } ?: return ToolResult(false, "Unknown key '$key'.")
            val res = outer.sh("input keyevent $code", ctx)
            return ToolResult(res.exitCode == 0, if (res.exitCode == 0) "Pressed $key." else "key failed: ${res.rawStderr}")
        }
    }

    class DeviceOpenAppTool(private val outer: DeviceControlTools) : Tool {
        override val name = "device_open_app"
        override val description = "Launch an app by package name. Blocked packages require separate explicit approval."
        override val parametersSchema = Schema.obj(mapOf(
            "package" to Schema.string("Android package name."),
            "override" to Schema.string("Pass \"true\" to launch a blocked (sensitive) app after explicit user approval."),
        ), required = listOf("package"))
        override val isReadOnly = false
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            outer.guard()?.let { return ToolResult(false, it) }
            val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "package required")
            val override = args["override"]?.jsonPrimitive?.booleanOrNull == true
            val blocked = outer.settings().deviceControlBlockedPackages
            if (pkg in blocked && !override) {
                return ToolResult(false, "Package '$pkg' is on the blocked list (sensitive). Re-run with override=true only after explicit user approval, or remove it from the blocked list in Settings.")
            }
            // also refuse uninstall/wipe-style packages? package name only; command is launch only.
            val res = outer.sh("monkey -p $pkg -c android.intent.category.LAUNCHER 1", ctx)
            return ToolResult(res.exitCode == 0, if (res.exitCode == 0) "Launched $pkg." else "launch failed: ${res.rawStderr}")
        }
    }

    class DeviceWaitTool(private val outer: DeviceControlTools) : Tool {
        override val name = "device_wait"
        override val description = "Wait for the given number of milliseconds (max 10000)."
        override val parametersSchema = Schema.obj(mapOf(
            "ms" to Schema.integer("Milliseconds to wait."),
        ), required = listOf("ms"))
        override val isReadOnly = true
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            outer.guard()?.let { return ToolResult(false, it) }
            val ms = ((args["ms"] as? JsonPrimitive)?.intOrNull ?: 1000).coerceIn(0, 10000)
            kotlinx.coroutines.delay(ms.toLong())
            return ToolResult(true, "Waited ${ms}ms.")
        }
    }

    fun tools(): List<Tool> = listOf(
        DeviceScreenshotTool(this),
        DeviceUiTreeTool(this),
        DeviceTapTool(this),
        DeviceSwipeTool(this),
        DeviceTypeTextTool(this),
        DeviceKeyTool(this),
        DeviceOpenAppTool(this),
        DeviceWaitTool(this),
    )
}
