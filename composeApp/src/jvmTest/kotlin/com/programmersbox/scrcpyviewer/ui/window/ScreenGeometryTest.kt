package com.programmersbox.scrcpyviewer.ui.window

import com.programmersbox.scrcpyviewer.ui.mirror.screenRoom
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenGeometryTest {
    private val one = ScreenArea(Rectangle(0, 0, 1440, 900), Rectangle(0, 25, 1440, 835))
    private val two = ScreenArea(Rectangle(1440, 0, 1920, 1080), Rectangle(1440, 0, 1920, 1040))
    private val screens = listOf(one, two)

    @Test
    fun screenFor_picksLargestOverlap() {
        assertEquals(two, screenFor(Rectangle(1300, 100, 400, 400), screens))
        assertEquals(one, screenFor(Rectangle(100, 100, 400, 400), screens))
    }

    @Test
    fun screenFor_offscreenPicksNearest() {
        assertEquals(two, screenFor(Rectangle(3500, 100, 200, 200), screens))
        assertEquals(one, screenFor(Rectangle(-900, 100, 200, 200), screens))
        assertNull(screenFor(Rectangle(0, 0, 10, 10), emptyList()))
    }

    @Test
    fun flipMacRect_convertsBottomLeftOrigin() {
        assertEquals(Rectangle(0, 0, 1440, 875), flipMacRect(0.0, 25.0, 1440.0, 875.0, 900.0))
        // A screen to the right, bottom-aligned, 180 pt taller than the main one, reaches above the main screen's top.
        assertEquals(Rectangle(1440, -180, 1920, 1080), flipMacRect(1440.0, 0.0, 1920.0, 1080.0, 900.0))
    }

    @Test
    fun screenRoom_usesWindowsOwnScreen() {
        val window = Rectangle(1500, 200, 400, 600)
        val area = screenFor(window, screens)!!
        assertEquals(two, area)
        // Screen 2's work area is 1040 tall and ends at y=1040; the window's top is at 200.
        assertEquals(1040 to 840, screenRoom(window, area))
        // Measured against screen 1 it would differ.
        assertEquals(835 to 660, screenRoom(window, one))
    }

    @Test
    fun windowsMonitorPixels_dividedByTheMonitorsDpiScale() {
        // A 3840x2160 monitor at 150 % (144 DPI), right of the primary one, is 2560x1440 window units.
        assertEquals(Rectangle(1280, 0, 2560, 1440), NativeScreenGeometry.scaled(Rectangle(1920, 0, 3840, 2160), 1.5))
        val r = Rectangle(10, 20, 30, 40)
        assertEquals(r, NativeScreenGeometry.scaled(r, 1.0))
    }

    @Test
    fun nativeScreens_doesNotThrow_andHasAMainScreenOnMac() {
        val native = NativeScreenGeometry.screens()
        println("native screens: $native")
        if (com.programmersbox.scrcpyviewer.Os.isMac) {
            assertEquals(true, native.isNotEmpty())
            assertEquals(true, native.all { it.workArea.width > 0 && it.bounds.contains(it.workArea) })
        }
    }
}
