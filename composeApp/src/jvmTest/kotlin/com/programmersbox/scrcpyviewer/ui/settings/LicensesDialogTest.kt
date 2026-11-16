package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Help › Open-source licenses: copy, search, rows and motion. Motion is passed explicitly (CI runners report
 * reduce motion on); frames are compared by how much of the picture changes, never by text widths.
 */
class LicensesDialogTest {
    private val apache = LicenseText("Apache License 2.0", "Apache License\nVersion 2.0, January 2004\n" + "Terms.\n".repeat(200), "https://spdx.org/licenses/Apache-2.0.html")

    private fun entry(
        id: String, name: String, group: LicenseGroup = LicenseGroup.Libraries, label: String = "Apache 2.0",
        family: LicenseFamily = LicenseFamily.Apache, by: String = "", description: String? = "About $name.",
        links: List<LicenseLink> = emptyList(), texts: List<LicenseText> = listOf(apache),
    ) = LicenseEntry(id, group, name, "1.0", label, family, texts.map { it.name }, by, description, links, texts)

    private val entries = listOf(
        entry(
            "bundled:ffmpeg", "FFmpeg", LicenseGroup.Bundled, "LGPL 3.0+", LicenseFamily.Lgpl, "The FFmpeg developers",
            "Decodes the mirrored video.",
            links = listOf(LicenseLink("ffmpeg.org", "https://ffmpeg.org/"), LicenseLink("Source (javacpp-presets 1.5.14)", "https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg")),
            texts = listOf(LicenseText("GNU Lesser General Public License v3.0 or later", "GNU LESSER GENERAL PUBLIC LICENSE", null)),
        ),
        entry("bundled:adb", "adb (Android SDK Platform-Tools)", LicenseGroup.Bundled, by = "The Android Open Source Project"),
        entry("io.insert-koin:koin-core", "Koin", by = "Arnaud Giuliani"),
        entry("x:tray", "Compose Native Tray", label = "MIT", family = LicenseFamily.Mit, by = "NucleusFramework",
            texts = listOf(LicenseText("MIT License", null, "https://spdx.org/licenses/MIT.html"))),
    )

    private class Calls {
        val opened = mutableListOf<String>()
        var dismissed = 0
    }

    private fun withLicenses(
        motion: Boolean = true,
        query: String = "",
        openId: String? = null,
        block: SceneDriver.(Calls) -> Unit,
    ): Calls {
        val calls = Calls()
        var visible by mutableStateOf(true)
        val d = helpScene {
            LicensesDialog(
                visible, entries, onDismiss = { calls.dismissed++; visible = false }, motion = motion,
                onOpenUrl = { calls.opened += it }, initialQuery = query, initialOpen = openId,
            )
        }
        try {
            d.frames(40)
            d.block(calls)
        } finally {
            d.scene.close()
        }
        return calls
    }

    @Test
    fun `shows the copy, the count and the bundled tools before the libraries`() = onEdt {
        withLicenses {
            for (text in listOf(
                "Open-source licenses", "Scrcpy Vwr is built on these. Thank you to everyone who makes them.",
                "Search libraries or licences", "4 entries", "Close",
                "FFmpeg", "LGPL 3.0+", "The FFmpeg developers", "Koin", "MIT",
            )) assertNotNull(node(text), "no $text")
            val bundled = assertNotNull(node("BUNDLED TOOLS")).boundsInRoot.top
            val libraries = assertNotNull(node("LIBRARIES")).boundsInRoot.top
            assertTrue(bundled < node("FFmpeg")!!.boundsInRoot.top)
            assertTrue(node("adb (Android SDK Platform-Tools)")!!.boundsInRoot.top < libraries)
            assertTrue(libraries < node("Koin")!!.boundsInRoot.top)
        }
    }

    @Test
    fun `typing filters by name, licence and developer and counts what is shown`() = onEdt {
        withLicenses {
            type("mit")
            frames(30)
            assertNotNull(node("1 of 4"))
            assertNotNull(node("Compose Native Tray"))
            assertNull(node("Koin"))
            assertNull(node("BUNDLED TOOLS"))
        }
        withLicenses(query = "giuliani") { assertNotNull(node("Koin")); assertNotNull(node("1 of 4")) }
        withLicenses(query = "apache") { assertNotNull(node("2 of 4")) }
    }

    @Test
    fun `no match shows the empty text`() = onEdt {
        withLicenses(query = "zzz") {
            assertNotNull(node(LICENSES_EMPTY_TEXT))
            assertNotNull(node("0 of 4"))
        }
        withLicenses { assertNull(node(LICENSES_EMPTY_TEXT)) }
    }

    @Test
    fun `a row unfolds its description, links and licence text, one row at a time`() = onEdt {
        withLicenses { calls ->
            assertNull(node("Decodes the mirrored video."))
            click("FFmpeg")
            frames(40)
            assertNotNull(node("Decodes the mirrored video."))
            assertNotNull(node("GNU LESSER GENERAL PUBLIC LICENSE"))
            click("Source (javacpp-presets 1.5.14)")
            assertEquals(listOf("https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg"), calls.opened)
            click("Koin")
            frames(40)
            assertNull(node("Decodes the mirrored video."))
            assertNotNull(node("About Koin."))
            // Clicking the open row folds it.
            click("Koin")
            frames(40)
            assertNull(node("About Koin."))
        }
    }

    @Test
    fun `a licence without its text links to it`() = onEdt {
        val calls = withLicenses(openId = "x:tray") { click("MIT License (full text)") }
        assertEquals(listOf("https://spdx.org/licenses/MIT.html"), calls.opened)
    }

    @Test
    fun `a long licence scrolls inside its row`() = onEdt {
        withLicenses(openId = "io.insert-koin:koin-core") {
            frames(30)
            val text = assertNotNull(nodeContaining("Version 2.0, January 2004"))
            // The text is far taller than its 160 dp box, which keeps the list's next row close below.
            assertTrue(text.size.height > 1_000, "text height ${text.size.height}")
            val next = assertNotNull(node("Compose Native Tray")).boundsInRoot.top
            assertTrue(next - node("Koin")!!.boundsInRoot.bottom < 400, "next row is ${next - node("Koin")!!.boundsInRoot.bottom} px below")
        }
    }

    @Test
    fun `Close, Esc and the scrim close it, a click on the card does not`() = onEdt {
        assertEquals(1, withLicenses { click("Close") }.dismissed)
        assertEquals(1, withLicenses { key(Key.Escape) }.dismissed)
        assertEquals(1, withLicenses { click(Offset(4f, 4f)) }.dismissed)
        assertEquals(0, withLicenses { click("Open-source licenses") }.dismissed)
    }

    private fun openCloseSteps(motion: Boolean, opening: Boolean): List<Long> {
        var visible by mutableStateOf(!opening)
        val d = helpScene { LicensesDialog(visible, entries, {}, motion = motion, onOpenUrl = {}) }
        try {
            d.frames(60)
            return d.stepsAfter { visible = opening }
        } finally {
            d.scene.close()
        }
    }

    @Test
    fun `opens over 450 ms and closes over 200 ms`() = onEdt {
        val open = openCloseSteps(motion = true, opening = true)
        assertMoves(open, "open")
        assertTrue(open.drop(19).sum() > 0, "open ended early: $open")
        val close = openCloseSteps(motion = true, opening = false)
        assertMoves(close, "close")
        assertTrue(close.drop(16).sum() <= close.sum() / 100, "close too slow: $close")
    }

    @Test
    fun `reduced motion fades in and out in 150 ms`() = onEdt {
        assertLandsQuickly(openCloseSteps(motion = false, opening = true), "open")
        assertLandsQuickly(openCloseSteps(motion = false, opening = false), "close")
    }

    private fun rowSteps(motion: Boolean): List<Long> {
        var steps = emptyList<Long>()
        withLicenses(motion = motion) {
            frames(20)
            val koin = assertNotNull(node("Koin")).boundsInRoot
            val at = koin.center
            // Below the row's header, where it unfolds (the header's own press ripple is not the unfold).
            steps = stepsAfter(fromY = koin.bottom.toInt() + 32) {
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at, type = androidx.compose.ui.input.pointer.PointerType.Mouse)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at, type = androidx.compose.ui.input.pointer.PointerType.Mouse, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at, type = androidx.compose.ui.input.pointer.PointerType.Mouse, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            }
        }
        return steps
    }

    @Test
    fun `a row unfolds over 400 ms, with reduced motion in 150 ms`() = onEdt {
        val steps = rowSteps(motion = true)
        assertMoves(steps, "unfold")
        assertTrue(steps.drop(16).sum() > 0, "unfold ended early: $steps")
        assertLandsQuickly(rowSteps(motion = false), "unfold")
    }

    private fun searchSteps(motion: Boolean): List<Long> {
        var steps = emptyList<Long>()
        withLicenses(motion = motion) {
            frames(30)
            // Nothing has a "z": the rows fade out and the empty text fades in.
            steps = stepsAfter { typeChar('z') }
            assertNotNull(node(LICENSES_EMPTY_TEXT))
        }
        return steps
    }

    @Test
    fun `search results and the empty text fade in over 300 ms, with reduced motion in 150 ms`() = onEdt {
        val steps = searchSteps(motion = true)
        assertMoves(steps, "results")
        assertTrue(steps.drop(13).sum() > 0, "results landed within 150 ms: $steps")
        assertLandsQuickly(searchSteps(motion = false), "results")
    }
}
