package com.programmersbox.scrcpyviewer.ui.tray.popup

import kotlin.test.Test
import kotlin.test.assertEquals

class TrayPopupMacPanelTest {
    @Test
    fun appKitValues() {
        assertEquals(128L, MacPanelBits.NON_ACTIVATING_PANEL)
        assertEquals(257L, MacPanelBits.COLLECTION_BEHAVIOR) // canJoinAllSpaces | fullScreenAuxiliary
        assertEquals(25L, MacPanelBits.STATUS_WINDOW_LEVEL)
    }

    @Test
    fun styleMaskAddsAndRemovesOnlyTheNonActivatingBit() {
        val resizable = 1L shl 3
        assertEquals(resizable or 128L, MacPanelBits.panelStyleMask(resizable))
        assertEquals(128L, MacPanelBits.panelStyleMask(128L))
        assertEquals(resizable, MacPanelBits.windowStyleMask(resizable or 128L))
        assertEquals(0L, MacPanelBits.windowStyleMask(0L))
    }

    @Test
    fun panelClassIsNamedAfterTheWindowClass() {
        assertEquals("ScrcpyVwrPopupPanel_TaoWindow", MacPanelBits.panelClassName("TaoWindow"))
    }
}
