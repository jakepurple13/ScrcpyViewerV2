package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class ClickCounterTest {
    @Test
    fun secondPressWithin500msAnd4px_is2() {
        val clicks = ClickCounter()
        assertEquals(1, clicks.press(Offset(10f, 10f), 1_000))
        assertEquals(2, clicks.press(Offset(12f, 13f), 1_400))
        assertEquals(3, clicks.press(Offset(11f, 11f), 1_800), "a triple click keeps counting")
    }

    @Test
    fun farPress_resetsTo1() {
        val clicks = ClickCounter()
        assertEquals(1, clicks.press(Offset(10f, 10f), 1_000))
        assertEquals(1, clicks.press(Offset(15f, 10f), 1_100))
    }

    @Test
    fun latePress_resetsTo1() {
        val clicks = ClickCounter()
        assertEquals(1, clicks.press(Offset(10f, 10f), 1_000))
        assertEquals(1, clicks.press(Offset(10f, 10f), 1_501))
    }
}
