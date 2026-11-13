package com.programmersbox.scrcpyviewer.apps

import com.programmersbox.scrcpyviewer.adb.AdbClient
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** An app on the device: its label and system flag from scrcpy's app list, its version from `dumpsys package`. */
data class DeviceApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    /** Null when `dumpsys package` did not report it. */
    val versionName: String?,
    val versionCode: Long?,
)

/** Which apps the Apps tab shows. */
enum class AppFilter { All, User, System }

/** The apps whose label or package name contains [query] (trimmed, any case) and that [filter] keeps, in order. */
fun List<DeviceApp>.matching(query: String, filter: AppFilter): List<DeviceApp> {
    val q = query.trim()
    return filter { app ->
        val kind = when (filter) {
            AppFilter.All -> true
            AppFilter.User -> !app.system
            AppFilter.System -> app.system
        }
        kind && (q.isEmpty() || app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true))
    }
}

/** One line of scrcpy's `list_apps` output. */
data class ListedApp(val packageName: String, val label: String, val system: Boolean)

data class AppVersion(val name: String?, val code: Long?)

object AppListParser {
    // " * Camera      com.gabb.camera": `*` marks a system app, `-` a user one; the label is padded with spaces.
    private val APP_LINE = Regex("""^ ([*-]) (.+?)\s+(\S+)\s*$""")
    private val PACKAGE_LINE = Regex("""^\s*Package \[([^\]]+)]""")
    private val VERSION_CODE = Regex("""\bversionCode=(\d+)""")
    private val VERSION_NAME = Regex("""^\s*versionName=(.*)$""")

    /** The apps in the output of the scrcpy server run with `list_apps=true`. */
    fun listApps(output: String): List<ListedApp> = output.lineSequence().mapNotNull { line ->
        APP_LINE.find(line.trimEnd('\r'))?.destructured?.let { (mark, label, pkg) -> ListedApp(pkg, label.trim(), mark == "*") }
    }.toList()

    /**
     * Each package's version from `dumpsys package packages` lines. A package listed twice (an updated system app also
     * appears under "Hidden system packages") keeps its first, active entry.
     */
    fun versions(output: String): Map<String, AppVersion> {
        val result = LinkedHashMap<String, AppVersion>()
        var current: String? = null
        var name: String? = null
        var code: Long? = null
        fun flush() {
            current?.let { if (it !in result) result[it] = AppVersion(name, code) }
        }
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            val pkg = PACKAGE_LINE.find(line)?.groupValues?.get(1)
            if (pkg != null) {
                flush()
                current = pkg
                name = null
                code = null
                return@forEach
            }
            VERSION_CODE.find(line)?.let { code = it.groupValues[1].toLongOrNull() }
            VERSION_NAME.find(line)?.let { name = it.groupValues[1].trim().ifEmpty { null } }
        }
        flush()
        return result
    }
}

/**
 * The Apps tab's device side for [serial]: the launchable apps (labels come from the scrcpy server's `list_apps`, run
 * from its own copy of the jar so it never touches a mirror's), launching, force stopping and uninstalling.
 */
open class DeviceApps(
    private val adb: AdbClient,
    private val serial: String,
    /** The bundled scrcpy server jar; null when it is missing. */
    private val serverJar: () -> Path?,
    private val serverVersion: String,
    private val listTimeoutMs: Long = LIST_TIMEOUT_MS,
) {
    /** The device's launchable apps with their versions, sorted by label. */
    open suspend fun list(): List<DeviceApp> {
        val jar = serverJar() ?: throw IOException("The scrcpy server is missing")
        val push = adb.exec("-s", serial, "push", jar.toString(), JAR_PATH)
        if (push.exitCode != 0) throw IOException("adb push failed: ${(push.stderr + push.stdout).trim()}")
        val out = StringBuilder()
        try {
            // The server deletes its jar when it exits, so every listing pushes it again.
            withTimeout(listTimeoutMs) {
                adb.shellStream(serial, "CLASSPATH=$JAR_PATH app_process / $SERVER_CLASS $serverVersion list_apps=true 2>&1")
                    .collect { out.append(it) }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Listing the apps timed out")
        }
        val listed = AppListParser.listApps(out.toString())
        if (listed.isEmpty()) {
            throw IOException(out.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: "The device listed no apps")
        }
        val versions = runCatching { AppListParser.versions(adb.shell(serial, VERSIONS_COMMAND)) }.getOrDefault(emptyMap())
        return listed.map { app ->
            val v = versions[app.packageName]
            DeviceApp(app.packageName, app.label, app.system, v?.name, v?.code)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: DeviceApp -> it.label }.thenBy { it.packageName })
    }

    /** Starts [packageName]'s launcher activity. */
    open suspend fun launch(packageName: String) {
        val out = runChecked("monkey -p ${quote(packageName)} -c android.intent.category.LAUNCHER 1")
        if ("No activities found" in out || "monkey aborted" in out) throw IOException("No activities found to run")
    }

    open suspend fun forceStop(packageName: String) {
        runChecked("am force-stop ${quote(packageName)}")
    }

    /** Uninstalls [packageName]; a [system] app is removed for the current user only (it stays on the system image). */
    open suspend fun uninstall(packageName: String, system: Boolean) {
        val user = if (system) "--user current " else ""
        val out = runChecked("pm uninstall $user${quote(packageName)}")
        if (!out.lineSequence().any { it.trim() == "Success" }) {
            throw IOException(out.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: "Uninstall failed")
        }
    }

    /** Runs [cmd] and returns its output; the legacy shell service drops the exit code, so it is echoed after it. */
    private suspend fun runChecked(cmd: String): String {
        val out = adb.shell(serial, "$cmd; echo $EXIT_MARKER$?")
        val text = out.substringBeforeLast(EXIT_MARKER).trim()
        val code = out.substringAfterLast(EXIT_MARKER, "").trim().toIntOrNull()
        if (code != 0) throw IOException(text.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: "exit code ${code ?: "unknown"}")
        return text
    }

    companion object {
        /** Not the mirror's jar path: a listing must never delete the jar a starting mirror is about to run. */
        const val JAR_PATH = "/data/local/tmp/scrcpy-viewer-apps.jar"
        private const val SERVER_CLASS = "com.genymobile.scrcpy.Server"
        private const val LIST_TIMEOUT_MS = 60_000L
        private const val EXIT_MARKER = "__scrcpyviewer_exit="
        private const val VERSIONS_COMMAND =
            "dumpsys package packages | grep -E '^  Package \\[|versionName=|versionCode='"

        /** [value] in single quotes for the device shell. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
