package com.programmersbox.scrcpyviewer.ui.mirror.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The per-browser scope rememberFilesBrowser cancels when a Reconnect replaces the browser. */
class ChildScopeTest {
    @Test
    fun `cancelling a child leaves the window scope and its other children running`() {
        val window = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = childScope(window)
        val next = childScope(window)
        old.cancel()
        assertFalse(old.isActive)
        assertTrue(window.isActive)
        assertTrue(next.isActive)
        window.cancel()
    }

    @Test
    fun `closing the window cancels every child`() {
        val window = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val child = childScope(window)
        window.cancel()
        assertFalse(child.isActive)
    }
}
