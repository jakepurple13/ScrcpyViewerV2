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

    @Test
    fun restoresOnlyWhileTheClassIsStillThePanelClass() {
        assertEquals(true, MacPanelBits.mayRestore(current = 0x10L, panel = 0x10L))
        // KVO subclassed the window since the swap: left alone.
        assertEquals(false, MacPanelBits.mayRestore(current = 0x20L, panel = 0x10L))
        assertEquals(false, MacPanelBits.mayRestore(current = null, panel = 0x10L))
    }

    @Test
    fun ivarSlotsReproduceTheOffsetTable() {
        // Layout a probe NSWindow subclass got from the runtime (NSWindow is 528 bytes).
        val table = listOf(
            "tail" to 592L, "midFlag" to 528L, "midObj" to 536L, "focusable" to 544L,
            "cell" to 552L, "b2" to 560L, "b3" to 562L, "big" to 576L,
        )
        val slots = MacPanelBits.ivarSlots(table, instanceSize = 600L)!!
        assertEquals(
            listOf(
                MacPanelBits.IvarSlot("midFlag", 528, 8, 3),
                MacPanelBits.IvarSlot("midObj", 536, 8, 3),
                MacPanelBits.IvarSlot("focusable", 544, 8, 3),
                MacPanelBits.IvarSlot("cell", 552, 8, 3),
                MacPanelBits.IvarSlot("b2", 560, 2, 1),
                MacPanelBits.IvarSlot("b3", 562, 14, 1),
                MacPanelBits.IvarSlot("big", 576, 16, 4),
                MacPanelBits.IvarSlot("tail", 592, 8, 3),
            ),
            slots,
        )
        // Laid end to end from the first offset, every slot lands where the table has it.
        var end = 528L
        for (slot in slots) {
            val align = 1L shl slot.alignLog2
            end = (end + align - 1) / align * align
            assertEquals(slot.offset, end, slot.name)
            end += slot.size
        }
        assertEquals(600L, end)
    }

    @Test
    fun ivarAlignmentIsCappedBySizeAndSixteen() {
        assertEquals(4, MacPanelBits.alignLog2(offset = 1024, size = 64))
        assertEquals(0, MacPanelBits.alignLog2(offset = 1024, size = 1))
        assertEquals(0, MacPanelBits.alignLog2(offset = 529, size = 8))
        assertEquals(0, MacPanelBits.alignLog2(offset = 528, size = 0)) // zero-sized ivar
        assertEquals(2, MacPanelBits.alignLog2(offset = 532, size = 4))
    }

    @Test
    fun ivarSlotsRejectAnIvarPastTheInstance() {
        assertEquals(null, MacPanelBits.ivarSlots(listOf("a" to 528L, "b" to 640L), instanceSize = 600L))
        assertEquals(emptyList(), MacPanelBits.ivarSlots(emptyList(), instanceSize = 528L))
    }
}
