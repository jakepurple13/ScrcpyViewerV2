package com.programmersbox.scrcpyviewer.ui.actions

import com.programmersbox.scrcpyviewer.actions.MatchMode
import kotlin.test.Test
import kotlin.test.assertEquals

class OnWhenTextTest {
    @Test
    fun `sentence names the rule in plain language`() {
        assertEquals("Shows On when the read command's output equals 7.", onWhenSentence(MatchMode.Equals, "7"))
        assertEquals("Shows On when the read command's output contains Awake.", onWhenSentence(MatchMode.Contains, "Awake"))
        assertEquals("Shows On when the read command's output matches ^[1-3]$.", onWhenSentence(MatchMode.Regex, "^[1-3]$"))
    }

    @Test
    fun `blank value asks for the on output`() {
        assertEquals("Enter the output that means On.", onWhenSentence(MatchMode.Equals, ""))
        assertEquals("Enter the output that means On.", onWhenSentence(MatchMode.Regex, "   "))
    }

    @Test
    fun `printed line is the trimmed first line capped at 40 characters`() {
        assertEquals("7", printedLine("  7\r\n"))
        assertEquals("first", printedLine("first\nsecond"))
        assertEquals("a".repeat(39) + "…", printedLine("a".repeat(50)))
        assertEquals("a".repeat(40), printedLine("a".repeat(40)))
        assertEquals("", printedLine("\n\n"))
    }
}
