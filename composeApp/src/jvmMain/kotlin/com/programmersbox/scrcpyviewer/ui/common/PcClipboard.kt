package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * The computer's clipboard for code outside composition (device clipboard sync, mirror paste), through Compose's
 * [Clipboard] instead of the AWT toolkit. The main window calls [bind] with `LocalClipboard.current`.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PcClipboard {
    private var clipboard: Clipboard? = null
    private var scope: CoroutineScope? = null
    private var pending: String? = null

    /** Starts using [clipboard]; text set before this is written now (the latest only). */
    @Synchronized
    fun bind(clipboard: Clipboard, scope: CoroutineScope) {
        this.clipboard = clipboard
        this.scope = scope
        pending?.let { write(clipboard, scope, it) }
        pending = null
    }

    /** Puts [text] on the clipboard; kept for [bind] when there is none yet. */
    @Synchronized
    fun setText(text: String) {
        val c = clipboard
        val s = scope
        if (c == null || s == null) pending = text else write(c, s, text)
    }

    /** The clipboard's text, or null when it holds none (empty, not text, or no window yet). */
    suspend fun text(): String? {
        val c = synchronized(this) { clipboard } ?: return null
        return runCatching {
            val entry = c.getClipEntry()?.nativeClipEntry as? Transferable ?: return null
            if (!entry.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
            entry.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }

    private fun write(clipboard: Clipboard, scope: CoroutineScope, text: String) {
        scope.launch { runCatching { clipboard.setClipEntry(ClipEntry(StringSelection(text))) } }
    }
}
