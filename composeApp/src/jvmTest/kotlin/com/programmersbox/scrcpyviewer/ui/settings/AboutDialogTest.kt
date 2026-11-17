package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Constraints
import com.programmersbox.scrcpyviewer.APP_VERSION
import com.programmersbox.scrcpyviewer.BuildInfo
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.COPY_CONFIRM_MILLIS
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Help › About: copy, actions and motion. Motion is passed explicitly (CI runners report reduce motion on);
 * frames are compared by how much of the picture changes, never by text widths.
 */
class AboutDialogTest {
    private val info = AboutInfo(
        appVersion = "1.22.0", scrcpyServerVersion = "5.0", adbVersion = "36.0.0", ffmpegVersion = "8.1.2",
        javacppVersion = "1.5.14", javaVersion = "21.0.5", composeVersion = "1.12.2", os = "Mac OS X 15.1 (aarch64)",
    )

    private class Calls {
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var licenses = 0
        var dismissed = 0
    }

    /** About, open, with [motion]; [block] drives it. */
    private fun withAbout(motion: Boolean = true, block: SceneDriver.(Calls) -> Unit): Calls {
        val calls = Calls()
        var visible by mutableStateOf(true)
        val d = helpScene {
            AboutDialog(
                visible, info,
                onOpenLicenses = { calls.licenses++ },
                onDismiss = { calls.dismissed++; visible = false },
                motion = motion,
                onCopy = { calls.copied += it },
                onOpenUrl = { calls.opened += it },
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
    fun `shows the app, the versions it bundles and runs on, and its actions`() = onEdt {
        withAbout {
            for (text in listOf(
                "Scrcpy Vwr", "Version 1.22.0",
                "Mirroring", "scrcpy-server 5.0", "Android tools", "adb 36.0.0", "Video", "FFmpeg 8.1.2",
                "Runtime", "Java 21.0.5 · Compose 1.12.2",
                "Copy details", "GitHub", "Open-source licenses", "Close",
            )) assertNotNull(node(text), "no $text")
        }
    }

    @Test
    fun `an unknown adb version reads as an ellipsis`() {
        assertEquals("Android tools" to "adb …", aboutRows(info.copy(adbVersion = null))[1])
    }

    @Test
    fun `Copy details copies every version and the OS, then reads Copied for a second and a half`() = onEdt {
        val calls = withAbout {
            click("Copy details")
            frames(20)
            assertNotNull(node("Copied"))
            assertNull(node("Copy details"))
            // Still "Copied" just before 1.5 s (frame time), then back.
            frames((COPY_CONFIRM_MILLIS / 16 - 30).toInt())
            assertNotNull(node("Copied"))
            frames(40)
            assertNotNull(node("Copy details"))
            assertNull(node("Copied"))
        }
        assertEquals(
            listOf(
                "Scrcpy Vwr 1.22.0\nscrcpy-server 5.0\nadb 36.0.0\nFFmpeg 8.1.2 (JavaCPP 1.5.14)\n" +
                    "Java 21.0.5 · Compose 1.12.2\nOS Mac OS X 15.1 (aarch64)",
            ),
            calls.copied,
        )
    }

    @Test
    fun `GitHub opens the repository, Open-source licenses and Close call back`() = onEdt {
        assertEquals(listOf(SCRCPY_VWR_GITHUB_URL), withAbout { click("GitHub") }.opened)
        assertEquals(1, withAbout { click("Open-source licenses") }.licenses)
        assertEquals(1, withAbout { click("Close") }.dismissed)
    }

    @Test
    fun `Esc and the scrim close it, a click on the card does not`() = onEdt {
        assertEquals(1, withAbout { key(Key.Escape) }.dismissed)
        assertEquals(1, withAbout { click(Offset(4f, 4f)) }.dismissed)
        assertEquals(0, withAbout { click("Scrcpy Vwr") }.dismissed)
        // Only the first close acts while it fades out.
        assertEquals(1, withAbout { key(Key.Escape); click(Offset(4f, 4f)) }.dismissed)
    }

    private fun openCloseSteps(motion: Boolean, opening: Boolean): List<Long> {
        var visible by mutableStateOf(!opening)
        val d = helpScene { AboutDialog(visible, info, {}, {}, motion = motion, onCopy = {}, onOpenUrl = {}) }
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
        // The card takes 450 ms (and the logo 500 ms): still moving after 300 ms.
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

    @Test
    fun `the buttons glide when the dialog narrows enough to re-break their rows, and follow a smaller drag at once`() = onEdt {
        withAbout { _ ->
            fun close() = assertNotNull(node("Close"), "no Close").boundsInRoot
            // Under 488 dp of window the card narrows with it: a drag of a few dp only re-centres the rows.
            scene.constraints = Constraints.fixed(480, 760)
            frames(60)
            for (w in listOf(476, 472, 468)) {
                val before = close()
                scene.constraints = Constraints.fixed(w, 760)
                frame()
                val now = close()
                frames(60)
                assertEquals(close(), now, "Close follows the drag at once at $w (from $before)")
            }
            // Much narrower: the rows break differently and Close glides to its new place.
            val from = close()
            scene.constraints = Constraints.fixed(300, 760)
            val path = List(60) { frame(); close().topLeft }
            val to = path.last()
            assertTrue((to - from.topLeft).getDistance() > 20f, "Close moves: $from -> $to")
            assertTrue(path.count { (it - from.topLeft).getDistance() > 1f && (it - to).getDistance() > 1f } >= 4, "over several frames: $path")
            assertTrue(path.takeLast(10).all { (it - to).getDistance() < 0.5f }, "settled: $path")
        }
    }

    @Test
    fun `platform-tools version comes out of adb version's output`() {
        assertEquals("36.0.0", platformToolsVersion("1.0.41 (36.0.0)"))
        assertEquals("36.0.0", platformToolsVersion("36.0.0"))
        assertNull(platformToolsVersion(null))
        assertNull(platformToolsVersion(UNKNOWN_VERSION))
        assertNull(platformToolsVersion(" "))
    }

    @Test
    fun `current info comes from the build`() {
        val current = AboutInfo.current("1.0.41 (36.0.0)")
        assertEquals(APP_VERSION, current.appVersion)
        assertEquals(BuildInfo.SCRCPY_SERVER_VERSION, current.scrcpyServerVersion)
        assertEquals(BuildInfo.FFMPEG_VERSION, current.ffmpegVersion)
        assertEquals(BuildInfo.COMPOSE_VERSION, current.composeVersion)
        assertEquals("36.0.0", current.adbVersion)
        assertEquals(System.getProperty("java.version"), current.javaVersion)
        assertTrue(current.os.startsWith(System.getProperty("os.name")), current.os)
    }

    @Test
    fun `the Help rows open About and the licenses`() = onEdt {
        var about = 0
        var licenses = 0
        val d = helpScene(1280, 800) {
            SettingsContent(
                SettingsUiState(), UpdateUiState(), SettingsNav(selected = SettingsCategoryId.Help),
                SettingsActions(onShowAbout = { about++ }, onShowLicenses = { licenses++ }),
            )
        }
        try {
            d.frames(40)
            assertNotNull(d.node("About Scrcpy Vwr"))
            assertNotNull(d.node("Version $APP_VERSION · what's inside and where it came from."))
            assertNotNull(d.node("The libraries and tools Scrcpy Vwr is built on, with their licences."))
            d.click("About")
            d.click("View licenses")
        } finally {
            d.scene.close()
        }
        assertEquals(1 to 1, about to licenses)
    }
}
