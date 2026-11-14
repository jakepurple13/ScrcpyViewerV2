package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.update.UpdateDialog
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.ui.update.UpdatesContent
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenExternallyTest {
    private val url = "https://github.com/jakepurple13/ScrcpyViewerV2/releases/tag/v1.6.0?a=1&b=2"
    private val file = "/tmp/my photo #1.jpg"

    @Test
    fun `a url gets the per-os browser command as one argument`() {
        assertEquals(listOf("open", url), openCommand(url, OpenPlatform.Mac, url = true))
        assertEquals(listOf("rundll32", "url.dll,FileProtocolHandler", url), openCommand(url, OpenPlatform.Windows, url = true))
        assertEquals(listOf("xdg-open", url), openCommand(url, OpenPlatform.Linux, url = true))
    }

    @Test
    fun `reveal selects the file on mac and windows and opens the folder on linux, one argument each`() {
        assertEquals(listOf("open", "-R", file), revealCommand(file, OpenPlatform.Mac))
        assertEquals(listOf("explorer.exe", "/select,", file), revealCommand(file, OpenPlatform.Windows))
        assertEquals(listOf("xdg-open", "/tmp"), revealCommand(file, OpenPlatform.Linux))
        assertEquals(listOf("xdg-open", "/"), revealCommand("/shot.png", OpenPlatform.Linux))
        assertEquals(listOf("xdg-open", "."), revealCommand("shot.png", OpenPlatform.Linux))
    }

    @Test
    fun `a file path gets the per-os file command as one argument`() {
        assertEquals(listOf("open", file), openCommand(file, OpenPlatform.Mac))
        assertEquals(listOf("explorer.exe", file), openCommand(file, OpenPlatform.Windows))
        assertEquals(listOf("xdg-open", file), openCommand(file, OpenPlatform.Linux))
    }

    @Test
    fun `openWebUrl runs the platform command for http and https`() {
        val seen = mutableListOf<List<String>>()
        val process = startDetached(listOf(if (System.getProperty("os.name").startsWith("Windows")) "cmd" else "true"))
        assertNotNull(process)
        assertTrue(openWebUrl("http://example.com/a", OpenPlatform.Linux) { seen += it; process })
        assertTrue(openWebUrl(url, OpenPlatform.Windows) { seen += it; process })
        assertEquals(
            listOf(listOf("xdg-open", "http://example.com/a"), listOf("rundll32", "url.dll,FileProtocolHandler", url)),
            seen,
        )
        process.destroy()
    }

    @Test
    fun `openWebUrl refuses anything that is not http or https`() {
        val seen = mutableListOf<List<String>>()
        val bad = listOf(
            "file:///etc/passwd", "javascript:alert(1)", "ftp://example.com/x", "smb://host/share", "calc.exe",
            "/usr/bin/open", "", "https://", "https:example.com", "x-apple.systempreferences:", "http://exa mple.com",
        )
        for (b in bad) {
            assertFalse(openWebUrl(b, OpenPlatform.Mac) { seen += it; null }, "should refuse '$b'")
            assertFalse(isWebUrl(b), "should refuse '$b'")
        }
        assertTrue(seen.isEmpty(), "no opener may run for a refused url: $seen")
    }

    @Test
    fun `a failing opener returns false instead of throwing`() {
        assertFalse(openWebUrl(url, OpenPlatform.Linux) { null })
        assertFalse(openWebUrl(url, OpenPlatform.Linux) { error("no opener") })
        assertFalse(openExternally(file, OpenPlatform.Mac) { throw java.io.IOException("no opener") })
    }

    private val release = Release(version = "1.6.0", htmlUrl = url, body = "notes")

    /** The Tao window provides no handler, so any use of one is the crash. This one records the use. */
    private class TripwireUriHandler : UriHandler {
        var used = false
        override fun openUri(uri: String) {
            used = true
            error("LocalUriHandler used")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun clickEverywhere(content: @Composable (open: (String) -> Boolean) -> Unit): List<String> {
        val tripwire = TripwireUriHandler()
        val opened = mutableListOf<String>()
        val driver = SceneDriver(ImageComposeScene(800, 700, Density(1f)) {
            CompositionLocalProvider(LocalUriHandler provides tripwire) {
                content { opened += it; true }
            }
        })
        try {
            driver.frames(10)
            // Click only what can be clicked: nodes carrying an OnClick action, found through the semantics tree.
            val targets = mutableListOf<Offset>()
            fun walk(node: SemanticsNode) {
                if (SemanticsActions.OnClick in node.config) targets += node.boundsInRoot.center
                node.children.forEach(::walk)
            }
            driver.scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            assertTrue(targets.isNotEmpty(), "the update UI should expose clickable nodes")
            for (at in targets) {
                driver.scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
                driver.scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
                driver.frame()
                driver.scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
                driver.frame()
            }
        } finally {
            driver.scene.close()
        }
        assertFalse(tripwire.used, "the update UI must open links without LocalUriHandler")
        return opened
    }

    private fun ui(selfUpdate: Boolean) = UpdateUiState(
        state = UpdateState.Available(release), available = release, currentVersion = "1.5.0",
        canSelfUpdate = selfUpdate, releasePageUrl = url,
    )

    @Test
    fun `Settings updates section opens its links through the injected opener, not LocalUriHandler`() {
        val opened = clickEverywhere { open -> UpdatesContent(ui(true), {}, {}, {}, openUrl = open) }
        assertTrue(opened.isNotEmpty() && opened.all { it == url }, "release links should open $url, got $opened")
    }

    @Test
    fun `update dialog opens its links through the injected opener, not LocalUriHandler`() {
        for (selfUpdate in listOf(true, false)) {
            val opened = clickEverywhere { open -> UpdateDialog(ui(selfUpdate), {}, {}, {}, openUrl = open) }
            assertTrue(opened.isNotEmpty() && opened.all { it == url }, "selfUpdate=$selfUpdate: expected $url, got $opened")
        }
    }
}
