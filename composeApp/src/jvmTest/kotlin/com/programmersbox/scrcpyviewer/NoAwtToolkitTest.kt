package com.programmersbox.scrcpyviewer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app runs on Nucleus' Tao backend: no code path may start the AWT toolkit, its windows or its event loop. This
 * scans the app's sources (comments stripped) for AWT, Swing and Compose-AWT references. Only data classes that never
 * start the toolkit are allowed.
 */
class NoAwtToolkitTest {
    private val sources = File("src/jvmMain/kotlin")

    @Test
    fun `app sources use no AWT toolkit, window or event loop`() {
        assertTrue(sources.isDirectory, "run from the composeApp module: ${sources.absolutePath} missing")
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val code = stripComments(file.readText())
                code.lineSequence().withIndex().flatMap { (i, line) ->
                    bannedIn(line).map { "${file.relativeTo(sources)}:${i + 1}: $it" }
                }
            }
            .toList()
        if (offenders.isNotEmpty()) fail("AWT still in use (${offenders.size}):\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `the scan catches imports, qualified uses and AWT-only Compose APIs`() {
        assertTrue(bannedIn("import java.awt.Toolkit").isNotEmpty())
        assertTrue(bannedIn("java.awt.EventQueue.invokeLater { }").isNotEmpty())
        assertTrue(bannedIn("import javax.swing.SwingUtilities").isNotEmpty())
        assertTrue(bannedIn("import androidx.compose.ui.awt.ComposeWindow").isNotEmpty())
        assertTrue(bannedIn("val w = nucleusWindow.unsafe.awtWindow").isNotEmpty())
        assertTrue(bannedIn("import androidx.compose.ui.window.FrameWindowScope").isNotEmpty())
        assertTrue(bannedIn("import kotlinx.coroutines.swing.Swing").isNotEmpty())
        assertTrue(bannedIn("import java.awt.event.KeyEvent as AwtKeyEvent").isEmpty())
        assertTrue(bannedIn("val r = java.awt.Rectangle(0, 0, 1, 1)").isEmpty())
        assertTrue(bannedIn("import java.awt.dnd.DnDConstants").isEmpty())
        assertTrue(bannedIn("import androidx.compose.ui.window.Dialog").isEmpty())
    }

    private fun bannedIn(line: String): List<String> {
        val hits = mutableListOf<String>()
        for (m in QUALIFIED.findAll(line)) {
            val name = m.value
            if (ALLOWED.none { name == it }) hits += name
        }
        for (m in BANNED_COMPOSE.findAll(line)) hits += m.value
        for (word in BANNED_WORDS) if (Regex("\\b$word\\b").containsMatchIn(line)) hits += word
        return hits
    }

    private fun stripComments(text: String): String {
        // Block comments keep their newlines so line numbers stay right; then // to end of line (not inside "...").
        val noBlocks = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text) { m -> m.value.filter { it == '\n' } }
        return noBlocks.lineSequence().joinToString("\n") { line ->
            var inString = false
            var i = 0
            var cut = line.length
            while (i < line.length - 1) {
                val c = line[i]
                if (c == '"' && (i == 0 || line[i - 1] != '\\')) inString = !inString
                if (!inString && c == '/' && line[i + 1] == '/') {
                    cut = i
                    break
                }
                i++
            }
            line.substring(0, cut)
        }
    }

    private companion object {
        /** A class in an AWT / Swing / AWT-bridge package: lower-case package segments, then the class name. */
        val QUALIFIED = Regex(
            "\\b(?:java\\.awt|javax\\.swing|javax\\.imageio|sun\\.awt|sun\\.lwawt|androidx\\.compose\\.ui\\.awt|kotlinx\\.coroutines\\.swing)" +
                "(?:\\.[a-z]\\w*)*\\.[A-Za-z]\\w*",
        )

        /** Data classes that never start the toolkit. */
        val ALLOWED = setOf(
            "java.awt.event.KeyEvent",
            "java.awt.Rectangle",
            "java.awt.Cursor",
            "java.awt.datatransfer.StringSelection",
            "java.awt.datatransfer.DataFlavor",
            // The file list of a drag out of the window (Tao reads it as plain data).
            "java.awt.datatransfer.Transferable",
            "java.awt.datatransfer.UnsupportedFlavorException",
            // Tao's synthetic drop events extend these; they do not start the toolkit.
            "java.awt.dnd.DnDConstants",
            "java.awt.dnd.DropTargetDragEvent",
            "java.awt.dnd.DropTargetDropEvent",
        )

        /** Compose Desktop's AWT windows and application. */
        val BANNED_COMPOSE = Regex(
            "\\bandroidx\\.compose\\.ui\\.window\\.(?:Window|DialogWindow|FrameWindowScope|DialogWindowScope|WindowScope|" +
                "application|awaitApplication|singleWindowApplication|Tray|rememberTrayState|LocalWindow)\\b",
        )

        val BANNED_WORDS = listOf(
            "ComposeWindow", "ComposeDialog", "FrameWindowScope", "LocalAwtWindow", "awtWindow", "awtDialog",
            "awtEventOrNull", "SkiaLayer",
        )
    }
}
