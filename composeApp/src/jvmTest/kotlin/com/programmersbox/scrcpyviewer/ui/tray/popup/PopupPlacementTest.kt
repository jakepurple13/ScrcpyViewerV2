package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.ui.window.ScreenArea
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PopupPlacementTest {
    private val mac = ScreenArea(Rectangle(0, 0, 1512, 982), Rectangle(0, 33, 1512, 949))

    @Test
    fun `macOS menu bar click puts popup under the bar, right-aligned to click`() {
        val r = assertNotNull(popupBounds(1300, 12, 360, 500, listOf(mac)))
        assertEquals(Rectangle(1300 + 18 - 360, 41, 360, 500), r)
        assertTrue(r.x + 360 <= 1512 - 8)
    }

    @Test
    fun `click near the right edge is clamped inside the work area`() {
        val r = assertNotNull(popupBounds(1500, 12, 360, 500, listOf(mac)))
        assertEquals(1512 - 8 - 360, r.x)
    }

    @Test
    fun `menu bar on secondary screen of different size`() {
        val second = ScreenArea(Rectangle(1512, -200, 2560, 1440), Rectangle(1512, -175, 2560, 1415))
        val r = assertNotNull(popupBounds(3900, -190, 360, 500, listOf(mac, second)))
        assertEquals(-175 + 8, r.y)
        assertTrue(r.x >= 1512 + 8)
        assertTrue(r.x + 360 <= 1512 + 2560 - 8)
    }

    @Test
    fun `bottom taskbar puts popup above it`() {
        val s = ScreenArea(Rectangle(0, 0, 1920, 1080), Rectangle(0, 0, 1920, 1032))
        val r = assertNotNull(popupBounds(1800, 1050, 360, 500, listOf(s)))
        assertEquals(1032 - 8 - 500, r.y)
        assertEquals(500, r.height)
    }

    @Test
    fun `tall content is capped to the work area`() {
        val r = assertNotNull(popupBounds(1300, 12, 360, 2000, listOf(mac)))
        assertEquals(949 - 16, r.height)
    }

    @Test
    fun `left taskbar puts popup beside it`() {
        val s = ScreenArea(Rectangle(0, 0, 1920, 1080), Rectangle(48, 0, 1872, 1080))
        val r = assertNotNull(popupBounds(20, 700, 360, 500, listOf(s)))
        assertEquals(48 + 8, r.x)
        assertEquals(700 - 250, r.y)
    }

    @Test
    fun `right taskbar puts popup beside it`() {
        val s = ScreenArea(Rectangle(0, 0, 1920, 1080), Rectangle(0, 0, 1872, 1080))
        val r = assertNotNull(popupBounds(1900, 20, 360, 500, listOf(s)))
        assertEquals(1872 - 8 - 360, r.x)
        assertEquals(8, r.y)
    }

    @Test
    fun `click inside the work area falls back to top-right`() {
        val r = assertNotNull(popupBounds(500, 500, 360, 500, listOf(mac)))
        assertEquals(Rectangle(1512 - 8 - 360, 33 + 8, 360, 500), r)
    }

    @Test
    fun `no screens gives null`() {
        assertNull(popupBounds(0, 0, 360, 500, emptyList()))
    }
}
