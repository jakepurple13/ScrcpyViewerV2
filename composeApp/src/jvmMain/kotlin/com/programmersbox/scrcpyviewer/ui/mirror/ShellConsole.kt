package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The last commands typed in any console; app-lifetime and shared across devices. Oldest drop past [max]. */
class ShellHistory(private val max: Int = 100) {
    private val list = ArrayList<String>()

    /** Oldest first, newest last. */
    val items: List<String> get() = list.toList()

    /** Adds [cmd] as the newest entry; the same command twice in a row is kept once. */
    fun add(cmd: String) {
        if (cmd.isEmpty() || list.lastOrNull() == cmd) return
        list += cmd
        while (list.size > max) list.removeAt(0)
    }
}

sealed interface ConsoleLine {
    val text: String

    /** Starts a run: `$ cmd` for a typed command ([typed]), `▶ name` for a saved action. */
    data class Header(override val text: String, val typed: Boolean) : ConsoleLine

    data class Out(override val text: String) : ConsoleLine

    /** Ends a run. [code] is null when the command was stopped or its exit code is unknown. */
    data class Exit(val code: Int?, val millis: Long) : ConsoleLine {
        override val text: String get() = if (code != null) "exit $code · $millis ms" else "stopped"
    }
}

/**
 * One mirror window's shell console: the scrollback, the running command and the command field. One command runs at
 * a time; [notify] gets the refusal message when another starts. [runner] is null while the device is unreachable.
 *
 * Snapshot state is written on the UI thread: [scope] is the window's coroutine scope (Main), and the stream does its
 * own IO on another dispatcher. Every run has a generation; events from a run that was stopped are ignored.
 */
@Stable
class ShellConsole(
    private val runner: ShellRunner?,
    private val history: ShellHistory,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val maxLines: Int = 5_000,
) {
    var lines: List<ConsoleLine> by mutableStateOf(emptyList())
        private set
    var running: Boolean by mutableStateOf(false)
        private set
    var filter: String by mutableStateOf("")
    var paused: Boolean by mutableStateOf(false)
    private var inputState by mutableStateOf("")

    /** The command field. Typing resets the history position; [historyUp] and [historyDown] do not. */
    var input: String
        get() = inputState
        set(value) {
            navIndex = -1
            inputState = value
        }

    val lineCount: Int get() = lines.size

    /** [lines] with the filter applied: matching output, plus the header and exit of every run that has one. */
    val visible: List<ConsoleLine> by derivedStateOf {
        val all = lines
        val needle = filter
        if (needle.isEmpty()) return@derivedStateOf all
        val result = ArrayList<ConsoleLine>()
        var run = ArrayList<ConsoleLine>()
        var hit = false
        fun flush() {
            if (hit) result += run
            run = ArrayList()
            hit = false
        }
        for (line in all) {
            if (line is ConsoleLine.Header) flush()
            if (line !is ConsoleLine.Out) {
                run += line
            } else if (line.text.contains(needle, ignoreCase = true)) {
                run += line
                hit = true
            }
        }
        flush()
        result
    }

    /** Output lines that contain the filter; 0 while the filter is empty. */
    val matchCount: Int by derivedStateOf {
        val needle = filter
        if (needle.isEmpty()) 0 else lines.count { it is ConsoleLine.Out && it.text.contains(needle, ignoreCase = true) }
    }

    private var job: Job? = null
    private var partial = StringBuilder()
    private var generation = 0
    private var navIndex = -1
    private var draft = ""

    /** Runs [input] as a typed command. False when one is already running or there is nothing to run. */
    fun submit(): Boolean {
        val cmd = input.trim()
        if (cmd.isEmpty()) return false
        if (!start(ConsoleLine.Header("$ $cmd", typed = true), cmd)) return false
        history.add(cmd)
        input = ""
        return true
    }

    /** Runs a saved action's [command]; the console shows only [name]. */
    fun runAction(name: String, command: String): Boolean = start(ConsoleLine.Header("▶ $name", typed = false), command)

    private fun start(header: ConsoleLine.Header, cmd: String): Boolean {
        if (running) {
            notify(BUSY_MESSAGE)
            return false
        }
        val runner = runner ?: run {
            notify("Not connected")
            return false
        }
        partial = StringBuilder()
        val mine = ++generation
        append(listOf(header))
        running = true
        paused = false
        job = scope.launch {
            try {
                runner.stream(cmd).collect { event ->
                    when (event) {
                        is ShellEvent.Output -> if (mine == generation) appendOutput(event.text)
                        is ShellEvent.Exit -> if (mine == generation) finish(ConsoleLine.Exit(event.code, event.millis))
                    }
                }
                // A stream that ends without Exit must not leave the console busy.
                if (mine == generation) finish(ConsoleLine.Exit(null, 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) {
                    appendOutput("error: ${e.message ?: e::class.simpleName}\n")
                    finish(ConsoleLine.Exit(null, 0))
                }
            }
        }
        return true
    }

    /** Stops the running command; the console ends the run with a "stopped" line. */
    fun stop() {
        if (!running) return
        generation++
        job?.cancel()
        job = null
        finish(ConsoleLine.Exit(null, 0))
    }

    /** Empties the scrollback and the unfinished line. A running command keeps running; its later output appends. */
    fun clear() {
        partial = StringBuilder()
        lines = emptyList()
    }

    /** Up: the previous command, starting from the newest; the typed draft is kept for [historyDown]. */
    fun historyUp() {
        val items = history.items
        if (items.isEmpty()) return
        if (navIndex == -1) {
            draft = input
            navIndex = items.size - 1
        } else if (navIndex > 0) {
            navIndex--
        }
        inputState = items[navIndex]
    }

    /** Down: the next command; past the newest it restores the draft. */
    fun historyDown() {
        if (navIndex == -1) return
        val items = history.items
        if (navIndex < items.size - 1) {
            navIndex++
            inputState = items[navIndex]
        } else {
            navIndex = -1
            inputState = draft
        }
    }

    private fun appendOutput(text: String) {
        partial.append(text)
        val complete = ArrayList<ConsoleLine>()
        while (true) {
            val nl = partial.indexOf("\n")
            if (nl < 0) break
            complete += ConsoleLine.Out(partial.substring(0, nl).trimEnd('\r'))
            partial.delete(0, nl + 1)
        }
        if (complete.isNotEmpty()) append(complete)
    }

    private fun finish(exit: ConsoleLine.Exit) {
        if (!running) return
        running = false
        val tail = ArrayList<ConsoleLine>()
        if (partial.isNotEmpty()) tail += ConsoleLine.Out(partial.toString().trimEnd('\r'))
        partial = StringBuilder()
        tail += exit
        append(tail)
    }

    private fun append(added: List<ConsoleLine>) {
        val all = lines + added
        lines = if (all.size > maxLines) all.subList(all.size - maxLines, all.size).toList() else all
    }

    companion object {
        const val BUSY_MESSAGE = "Stop the running command first"
    }
}
