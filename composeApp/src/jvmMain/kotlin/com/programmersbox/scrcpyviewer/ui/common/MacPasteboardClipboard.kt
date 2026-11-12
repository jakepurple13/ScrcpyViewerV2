package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.sun.jna.Pointer
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * The macOS clipboard (text) through NSPasteboard. Compose Desktop's own clipboard goes through the AWT toolkit, which
 * the app never starts on the Tao backend. Entries are plain text: the text of what is set, a [StringSelection] when
 * read. Safe from any thread.
 */
@OptIn(ExperimentalComposeUiApi::class)
object MacPasteboardClipboard : Clipboard {
    private const val TEXT_TYPE = "public.utf8-plain-text" // NSPasteboardTypeString

    override suspend fun getClipEntry(): ClipEntry? = text()?.let { ClipEntry(StringSelection(it)) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.plainText()
        pooled {
            val board = generalPasteboard()
            ObjC.msgLong(board, "clearContents")
            if (text != null) ObjC.msgBool(board, "setString:forType:", ObjC.nsString(text), ObjC.nsString(TEXT_TYPE))
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any get() = this

    /** The pasteboard's text, or null when it holds none. */
    fun text(): String? = pooled {
        val value = ObjC.msgPtr(generalPasteboard(), "stringForType:", ObjC.nsString(TEXT_TYPE)) ?: return@pooled null
        ObjC.msgPtr(value, "UTF8String")?.getString(0, "UTF-8")
    }

    private fun generalPasteboard(): Pointer = ObjC.msgPtr(ObjC.cls("NSPasteboard"), "generalPasteboard")!!

    /** Off the main thread nothing drains autoreleased objects: each call gets its own pool. */
    private fun <T> pooled(block: () -> T): T {
        val pool = ObjC.msgPtr(ObjC.msgPtr(ObjC.cls("NSAutoreleasePool"), "alloc")!!, "init")!!
        try {
            return block()
        } finally {
            ObjC.msgVoid(pool, "drain")
        }
    }
}

/** The text of this entry when it holds text, else null. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun ClipEntry.plainText(): String? = runCatching {
    val t = asAwtTransferable ?: return null
    if (!t.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    t.getTransferData(DataFlavor.stringFlavor) as? String
}.getOrNull()

/**
 * Gives [content] a clipboard that works without the AWT toolkit: NSPasteboard on macOS. Elsewhere the window's own
 * (GTK on Linux under Tao; Windows still goes through AWT's, see the PR's gap list).
 */
@Composable
fun ProvideAppClipboard(content: @Composable () -> Unit) {
    if (Os.isMac) CompositionLocalProvider(LocalClipboard provides MacPasteboardClipboard, content = content) else content()
}
