package com.programmersbox.scrcpyviewer.ui.window.launch

import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.IconUnfold
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.Off
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation.ScreenOn
import com.programmersbox.scrcpyviewer.ui.common.gnomeAnimationsOff
import com.programmersbox.scrcpyviewer.ui.window.launch.OverlayPlatform.LinuxWayland
import com.programmersbox.scrcpyviewer.ui.window.launch.OverlayPlatform.LinuxX11
import com.programmersbox.scrcpyviewer.ui.window.launch.OverlayPlatform.MacOS
import com.programmersbox.scrcpyviewer.ui.window.launch.OverlayPlatform.Windows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchPolicyTest {
    private fun on(
        platform: OverlayPlatform = MacOS,
        setting: com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation = IconUnfold,
        reduceMotion: Boolean = false,
        glassBlur: Boolean = false,
        fullscreen: Boolean = false,
        enabled: Set<OverlayPlatform> = OverlayEnabled,
    ) = launchEffect(LaunchConditions(setting, platform, reduceMotion, glassBlur, fullscreen, enabled))

    @Test
    fun `off is none`() {
        for (p in OverlayPlatform.entries) assertEquals(LaunchEffect.None, on(p, Off, reduceMotion = true, glassBlur = true))
    }

    @Test
    fun `chosen animation on an enabled platform is the overlay`() {
        assertEquals(LaunchEffect.Overlay(IconUnfold), on(MacOS, IconUnfold))
        assertEquals(LaunchEffect.Overlay(ScreenOn), on(MacOS, ScreenOn))
    }

    @Test
    fun `reduce motion fades`() = assertEquals(LaunchEffect.Fade, on(reduceMotion = true))

    @Test
    fun `glass blur fades`() = assertEquals(LaunchEffect.Fade, on(glassBlur = true))

    @Test
    fun `full screen fades`() = assertEquals(LaunchEffect.Fade, on(fullscreen = true))

    @Test
    fun `a minimized window quits without animation`() {
        assertEquals(LaunchEffect.None, launchEffect(LaunchConditions(IconUnfold, MacOS, false, false, false, minimized = true)))
        assertEquals(LaunchEffect.None, launchEffect(LaunchConditions(ScreenOn, MacOS, true, false, false, minimized = true)))
    }

    @Test
    fun `previews loop on hover unless the OS asks for less motion`() {
        assertTrue(LaunchPreview.loops(hovered = true, reduceMotion = false))
        assertFalse(LaunchPreview.loops(hovered = true, reduceMotion = true))
        assertFalse(LaunchPreview.loops(hovered = false, reduceMotion = false))
    }

    @Test
    fun `a cached value is read once per window of time`() {
        var now = 0L
        var reads = 0
        val cache = TimedCache(ttlMs = 1_000, clock = { now }) { reads++; reads }
        assertEquals(1, cache.get())
        now = 999
        assertEquals(1, cache.get())
        now = 1_000
        assertEquals(2, cache.get())
    }

    @Test
    fun `platforms not yet confirmed fade`() {
        for (p in listOf(Windows, LinuxX11, LinuxWayland)) {
            assertEquals(LaunchEffect.Fade, on(p))
            assertEquals(LaunchEffect.Overlay(IconUnfold), on(p, enabled = OverlayPlatform.entries.toSet()))
        }
    }

    @Test
    fun `macOS is the only platform on until the spike is confirmed elsewhere`() =
        assertEquals(setOf(MacOS), OverlayEnabled)

    @Test
    fun `linux session type`() {
        val env = { vars: Map<String, String> -> { k: String -> vars[k] } }
        assertEquals(LinuxWayland, OverlayPlatform.current(env(mapOf("XDG_SESSION_TYPE" to "wayland")), isMac = false, isWindows = false))
        assertEquals(LinuxWayland, OverlayPlatform.current(env(mapOf("WAYLAND_DISPLAY" to "wayland-0")), isMac = false, isWindows = false))
        assertEquals(LinuxX11, OverlayPlatform.current(env(mapOf("XDG_SESSION_TYPE" to "x11")), isMac = false, isWindows = false))
        assertEquals(LinuxX11, OverlayPlatform.current(env(emptyMap()), isMac = false, isWindows = false))
        assertEquals(MacOS, OverlayPlatform.current(env(mapOf("WAYLAND_DISPLAY" to "x")), isMac = true, isWindows = false))
        assertEquals(Windows, OverlayPlatform.current(env(emptyMap()), isMac = false, isWindows = true))
    }

    @Test
    fun `gnome animations setting`() {
        assertTrue(gnomeAnimationsOff("false\n"))
        assertFalse(gnomeAnimationsOff("true\n"))
        assertFalse(gnomeAnimationsOff(null))
        assertFalse(gnomeAnimationsOff("No such schema"))
    }
}
