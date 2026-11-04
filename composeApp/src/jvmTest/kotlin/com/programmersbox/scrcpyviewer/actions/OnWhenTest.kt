package com.programmersbox.scrcpyviewer.actions

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnWhenTest {
    @Test
    fun equalsTrimsOutput() = assertTrue(OnWhen(MatchMode.Equals, "7").matches("7\r\n"))

    @Test
    fun containsMatchesSubstring() = assertTrue(OnWhen(MatchMode.Contains, "on").matches(" mode: on "))

    @Test
    fun regexMatches() = assertTrue(OnWhen(MatchMode.Regex, "^[1-3]$").matches("2\n"))

    @Test
    fun invalidRegexIsFalse() = assertFalse(OnWhen(MatchMode.Regex, "(").matches("x"))
}
