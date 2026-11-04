package com.programmersbox.scrcpyviewer.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandTemplateTest {
    @Test
    fun `finds variables`() {
        assertEquals(
            listOf("package", "activity"),
            CommandTemplate.variables("pm clear {package} && am start {package}/{activity}"),
        )
    }

    @Test
    fun `ignores non-names`() {
        assertTrue(CommandTemplate.variables("echo {1x} { a } {}").isEmpty())
    }

    @Test
    fun `escapes braces`() {
        val cmd = "awk '{{print $1}}' {f}"
        assertEquals("awk '{print $1}' x", CommandTemplate.substitute(cmd, mapOf("f" to "x")))
        assertEquals(listOf("f"), CommandTemplate.variables(cmd))
    }

    @Test
    fun `leaves unknown values`() {
        assertEquals("1 {b}", CommandTemplate.substitute("{a} {b}", mapOf("a" to "1")))
    }
}
