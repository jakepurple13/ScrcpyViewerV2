package com.programmersbox.scrcpyviewer.update

import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import dev.nucleusframework.taskbarprogress.tao.hideTaskbarProgress
import dev.nucleusframework.taskbarprogress.tao.requestTaskbarAttention
import dev.nucleusframework.taskbarprogress.tao.showTaskbarError
import dev.nucleusframework.taskbarprogress.tao.showTaskbarIndeterminate
import dev.nucleusframework.taskbarprogress.tao.showTaskbarProgress
import dev.nucleusframework.taskbarprogress.tao.stopTaskbarAttention

/**
 * Draws on [window]'s taskbar button, or on the Dock icon on macOS (the Dock bar is app-wide). A native failure is
 * logged and dropped: the indicator is a nicety and must never take the UI down.
 */
class NucleusTaskbarSink(private val window: NucleusWindow) : TaskbarSink {
    override fun attention() = call("attention") {
        window.requestTaskbarAttention(TaskbarProgress.AttentionType.INFORMATIONAL)
    }

    override fun progress(fraction: Double) = call("progress") { window.showTaskbarProgress(fraction) }

    override fun indeterminate() = call("indeterminate") { window.showTaskbarIndeterminate() }

    override fun error(fraction: Double) = call("error") { window.showTaskbarError(fraction) }

    override fun hide() = call("hide") { window.hideTaskbarProgress() }

    override fun stopAttention() = call("stopAttention") { window.stopTaskbarAttention() }

    private inline fun call(what: String, block: () -> Boolean) {
        runCatching(block).onFailure { log.log(System.Logger.Level.WARNING, "Taskbar $what failed", it) }
    }

    private companion object {
        val log: System.Logger = System.getLogger("scrcpyviewer.Update")
    }
}
