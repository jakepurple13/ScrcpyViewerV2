package com.programmersbox.scrcpyviewer.actions

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The reason [file] can't be applied to a store holding [existing], or null when it can. `<n>` in a reason is the 1-based position in the file. */
fun validateInbox(file: InboxFile, existing: List<CustomAction>): String? {
    file.actions.forEachIndexed { i, action ->
        val n = i + 1
        if (action.name.isBlank()) return "Action $n: name is required"
        when (action) {
            is ToggleAction -> {
                if (action.on.isBlank()) return "Action $n: on is required"
                if (action.off.isBlank()) return "Action $n: off is required"
                val onWhen = action.onWhen
                if (onWhen?.mode == MatchMode.Regex && runCatching { Regex(onWhen.value) }.isFailure) {
                    return "Action $n: onWhen regex is invalid"
                }
            }

            is RunOnceAction -> if (action.command.isBlank()) return "Action $n: command is required"
        }
        if (existing.any { it.id == action.id } &&
            existing.any { it.id != action.id && it.name.equals(action.name, ignoreCase = true) }
        ) {
            return "Action $n: another action is already named ${action.name}"
        }
    }
    return null
}

/**
 * Watches [dir] for `*.json` files that AI tools drop in, applies valid ones to [store] and announces each change on
 * [messages]. Invalid files go to `rejected/` with a `.error.txt` explaining why.
 */
class ActionInbox(
    private val dir: Path,
    private val store: ActionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val rejectedDir get() = dir.resolve("rejected")

    /** Starts polling. Never throws: a folder that can't be created is reported once and retried on each poll. */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var reportedFolderError = false
            while (isActive) {
                try {
                    Files.createDirectories(rejectedDir)
                    reportedFolderError = false
                } catch (e: Exception) {
                    if (!reportedFolderError) System.err.println("Actions inbox folder can't be created: $e")
                    reportedFolderError = true
                    delay(POLL_MS)
                    continue
                }
                try {
                    processOnce()
                } catch (e: Exception) {
                    System.err.println("Actions inbox scan failed: $e")
                }
                delay(POLL_MS)
            }
        }
    }

    /** One scan of the inbox, oldest file first. */
    fun processOnce() {
        val files = try {
            Files.list(dir).use { s ->
                s.filter { it.isRegularFile() && it.extension == "json" }
                    .map { it to it.getLastModifiedTime().toMillis() }
                    .toList()
            }
        } catch (e: IOException) {
            return
        }
        files.sortedBy { it.second }.forEach { (file, modified) ->
            try {
                processFile(file, modified)
            } catch (e: Exception) {
                System.err.println("Actions inbox couldn't process ${file.name}: $e")
            }
        }
    }

    private fun processFile(file: Path, modified: Long) {
        val decoded = try {
            ActionCodec.decodeInbox(file.readText())
        } catch (e: ActionFileException) {
            // A writer may still be mid-write; give it a moment before calling the file bad.
            if (clock() - modified < RETRY_WINDOW_MS) return
            reject(file, e.message ?: "Not a Scrcpy Vwr actions file")
            return
        }
        // Like the editor, names are saved trimmed.
        val parsed = decoded.copy(actions = decoded.actions.map { it.withName(it.name.trim()) })
        val reason = validateInbox(parsed, store.actions.value)
        if (reason != null) {
            reject(file, reason)
            return
        }
        val author = parsed.author?.takeIf { it.isNotBlank() } ?: "AI"
        val changes = store.applyInbox(parsed.actions)
        Files.deleteIfExists(file)
        changes.forEach {
            _messages.tryEmit(
                when (it) {
                    is InboxChange.Added -> "✦ $author added ${it.name}"
                    is InboxChange.Updated -> "✦ $author updated ${it.name}"
                },
            )
        }
    }

    private fun reject(file: Path, reason: String) {
        Files.createDirectories(rejectedDir)
        Files.move(file, rejectedDir.resolve(file.name), StandardCopyOption.REPLACE_EXISTING)
        rejectedDir.resolve("${file.name}.error.txt").writeText(reason + "\n")
        _messages.tryEmit("Couldn't add actions from ${file.name}: $reason")
    }

    private companion object {
        const val POLL_MS = 1000L
        const val RETRY_WINDOW_MS = 2000L
    }
}
