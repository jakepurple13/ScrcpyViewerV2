package com.programmersbox.scrcpyviewer.ui.common

import com.programmersbox.scrcpyviewer.Os
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The platform's own open and save dialogs, without `java.awt.FileDialog` or `JFileChooser`, so a picker never
 * initializes the AWT toolkit. Calls block until the dialog closes: call them through [onPickerThread], which keeps the
 * Tao event loop (the UI thread) free where the platform's dialog would not pump it.
 */
interface NativeFileDialogs {
    /**
     * Files (or, with [directories], folders) to open; empty when cancelled. [extensions] ("apk", "json") limits the
     * files shown, empty shows all; [initial] is the folder to start in.
     */
    fun open(title: String, extensions: List<String>, multiple: Boolean, directories: Boolean = false, initial: Path? = null): List<Path>

    /** Where to save, starting from [suggestedName] (given [extension] when it lacks it); null when cancelled. */
    fun save(title: String, suggestedName: String, extension: String): Path?
}

/**
 * The dialogs for this OS: NSOpenPanel / NSSavePanel on macOS, comdlg32 / shell32 on Windows, zenity or kdialog
 * elsewhere. [onUnavailable] gets a message when no picker can be shown (Linux with neither tool).
 */
fun nativeFileDialogs(onUnavailable: (String) -> Unit = { System.err.println(it) }): NativeFileDialogs = SafeFileDialogs(
    when {
        Os.isMac -> MacFileDialogs
        Os.isWindows -> WindowsFileDialogs
        else -> LinuxFileDialogs(onUnavailable)
    },
    onUnavailable,
)

/**
 * Runs [pick] (a blocking [NativeFileDialogs] call) where the platform's picker belongs. On macOS ([mac]) in place:
 * NSOpenPanel must run on the AppKit main thread, the UI thread under Tao, and its modal loop keeps the windows
 * drawing. Elsewhere on [background]: the Win32 dialog thread's join and zenity / kdialog's waitFor pump nothing, so
 * on the UI thread every window (live video included) would freeze until the dialog closed.
 */
suspend fun <T> onPickerThread(
    mac: Boolean = Os.isMac,
    background: CoroutineDispatcher = Dispatchers.IO,
    pick: () -> T,
): T = if (mac) pick() else withContext(background) { pick() }

/** [dialogs], with a failed native call reported to [onFailure] and treated as Cancel instead of thrown. */
internal class SafeFileDialogs(private val dialogs: NativeFileDialogs, private val onFailure: (String) -> Unit) : NativeFileDialogs {
    override fun open(title: String, extensions: List<String>, multiple: Boolean, directories: Boolean, initial: Path?): List<Path> =
        guard(emptyList()) { dialogs.open(title, extensions, multiple, directories, initial) }

    override fun save(title: String, suggestedName: String, extension: String): Path? =
        guard(null) { dialogs.save(title, suggestedName, extension) }

    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        System.err.println("File dialog failed: $t")
        onFailure("Could not open the file picker")
        fallback
    }
}

/** One dialog, as the platform pickers take it. */
sealed interface PickerRequest {
    val title: String

    data class Open(
        override val title: String,
        val extensions: List<String>,
        val multiple: Boolean,
        val directories: Boolean,
        val initial: Path?,
    ) : PickerRequest

    data class Save(override val title: String, val suggestedName: String, val extension: String) : PickerRequest
}

/** [extensions] as lower-case names without the dot, blanks and duplicates dropped: `[".APK", "apk"]` -> `["apk"]`. */
fun extensionsFilter(extensions: List<String>): List<String> =
    extensions.map { it.trim().removePrefix(".").lowercase() }.filter { it.isNotEmpty() }.distinct()

/** Shell globs for [extensions] (from [extensionsFilter]) in both cases, as zenity and kdialog match case-sensitively. */
fun globPatterns(extensions: List<String>): List<String> =
    extensions.flatMap { listOf("*.$it", "*.${it.uppercase()}") }.distinct()

/** [suggestedName] ending in `.extension` (any case): unchanged when it already does. */
fun suggestedFileName(suggestedName: String, extension: String): String {
    val ext = extension.trim().removePrefix(".")
    if (ext.isEmpty() || suggestedName.endsWith(".$ext", ignoreCase = true)) return suggestedName
    return "$suggestedName.$ext"
}

/** [path] ending in `.extension`, for pickers that do not add it themselves. */
fun withExtension(path: Path, extension: String): Path {
    val name = path.fileName?.toString() ?: return path
    val fixed = suggestedFileName(name, extension)
    return if (fixed == name) path else path.resolveSibling(fixed)
}

/** Newline-separated paths printed by zenity / kdialog. */
fun parsePickerOutput(output: String): List<Path> = output.lines().filter { it.isNotBlank() }.map { Path.of(it) }

/** A picker program and its arguments. */
data class PickerCommand(val tool: String, val args: List<String>)

/**
 * The zenity command for [request], else the kdialog one, else null; [available] tells whether a program is on PATH.
 */
fun linuxPickerCommand(request: PickerRequest, available: (String) -> Boolean): PickerCommand? = when {
    available("zenity") -> PickerCommand("zenity", zenityArgs(request))
    available("kdialog") -> PickerCommand("kdialog", kdialogArgs(request))
    else -> null
}

private fun zenityArgs(request: PickerRequest): List<String> = buildList {
    add("zenity")
    add("--file-selection")
    add("--title=${request.title}")
    when (request) {
        is PickerRequest.Open -> {
            if (request.multiple) {
                add("--multiple")
                add("--separator=\n")
            }
            if (request.directories) add("--directory")
            // A trailing slash makes zenity open the folder rather than preselect a file named like it.
            request.initial?.let { add("--filename=${it.toString().trimEnd('/')}/") }
            val globs = globPatterns(extensionsFilter(request.extensions))
            if (!request.directories && globs.isNotEmpty()) add("--file-filter=${globs.joinToString(" ")}")
        }
        is PickerRequest.Save -> {
            add("--save")
            add("--confirm-overwrite")
            add("--filename=${suggestedFileName(request.suggestedName, request.extension)}")
            val globs = globPatterns(extensionsFilter(listOf(request.extension)))
            if (globs.isNotEmpty()) add("--file-filter=${globs.joinToString(" ")}")
        }
    }
}

private fun kdialogArgs(request: PickerRequest): List<String> = buildList {
    add("kdialog")
    add("--title")
    add(request.title)
    when (request) {
        is PickerRequest.Open -> {
            val start = request.initial?.toString() ?: "."
            if (request.directories) {
                add("--getexistingdirectory")
                add(start)
            } else {
                add("--getopenfilename")
                add(start)
                val globs = globPatterns(extensionsFilter(request.extensions))
                if (globs.isNotEmpty()) add(globs.joinToString(" "))
                if (request.multiple) {
                    add("--multiple")
                    add("--separate-output")
                }
            }
        }
        is PickerRequest.Save -> {
            add("--getsavefilename")
            add(suggestedFileName(request.suggestedName, request.extension))
            val globs = globPatterns(extensionsFilter(listOf(request.extension)))
            if (globs.isNotEmpty()) add(globs.joinToString(" "))
        }
    }
}

/** True when [program] is an executable file in a `PATH` directory. */
internal fun onPath(program: String): Boolean =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
        .any { File(it, program).let { f -> f.isFile && f.canExecute() } }

/**
 * zenity, then kdialog, through [ProcessBuilder]. Neither on PATH: nothing is picked and [onUnavailable] says why.
 * The XDG desktop portal is not used (it needs D-Bus); see the Task 7 report.
 */
internal class LinuxFileDialogs(
    private val onUnavailable: (String) -> Unit,
    private val available: (String) -> Boolean = ::onPath,
) : NativeFileDialogs {
    override fun open(title: String, extensions: List<String>, multiple: Boolean, directories: Boolean, initial: Path?): List<Path> {
        val paths = run(PickerRequest.Open(title, extensions, multiple, directories, initial))
        return if (multiple) paths else paths.take(1)
    }

    override fun save(title: String, suggestedName: String, extension: String): Path? =
        run(PickerRequest.Save(title, suggestedName, extension)).firstOrNull()?.let { withExtension(it, extension) }

    private fun run(request: PickerRequest): List<Path> {
        val command = linuxPickerCommand(request, available)
        if (command == null) {
            onUnavailable("No file picker found: install zenity or kdialog")
            return emptyList()
        }
        return try {
            val process = ProcessBuilder(command.args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(1, TimeUnit.MINUTES)
            // Exit 1 is Cancel for both tools; anything else non-zero is an error with nothing picked.
            if (process.exitValue() == 0) parsePickerOutput(output) else emptyList()
        } catch (t: Throwable) {
            System.err.println("${command.tool} failed: $t")
            emptyList()
        }
    }
}
