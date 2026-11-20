package com.programmersbox.scrcpyviewer.ui.window

import com.sun.jna.Structure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the Win32 POINT marshalling shapes; runs on any OS since it only reflects on the types. */
class WinPointShapeTest {
    private fun param(name: String): Class<*> =
        CursorUser32::class.java.methods.single { it.name == name }.parameterTypes[0]

    @Test
    fun `GetCursorPos takes a by-reference point`() {
        val type = param("GetCursorPos")
        assertTrue(Structure::class.java.isAssignableFrom(type))
        assertFalse(Structure.ByValue::class.java.isAssignableFrom(type))
    }

    @Test
    fun `MonitorFromPoint takes a by-value point`() {
        assertTrue(Structure.ByValue::class.java.isAssignableFrom(param("MonitorFromPoint")))
    }

    @Test
    fun `point is two ints x then y`() {
        for (point in listOf(WinPoint(), WinPointByValue())) {
            assertEquals(8, point.size())
            assertEquals(listOf("x", "y"), point.fieldNames())
        }
    }

    private fun Structure.fieldNames(): List<String> {
        val m = Structure::class.java.getDeclaredMethod("getFieldOrder").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return m.invoke(this) as List<String>
    }
}
