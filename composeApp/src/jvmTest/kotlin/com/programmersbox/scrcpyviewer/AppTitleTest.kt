package com.programmersbox.scrcpyviewer

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class AppTitleTest {
    @Test
    fun `title shows the app version`() {
        val title = appTitle()
        assertTrue(Regex("""Scrcpy Vwr \(v\d+\.\d+\.\d+\)""").matches(title), title)
        assertContains(title, APP_VERSION)
    }
}
