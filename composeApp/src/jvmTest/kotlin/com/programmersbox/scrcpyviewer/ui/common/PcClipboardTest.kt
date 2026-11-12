package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PcClipboardTest {
    private class FakeClipboard(var entry: ClipEntry? = null) : Clipboard {
        override val nativeClipboard: NativeClipboard get() = error("unused")
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
    }

    @Test
    fun setTextBeforeBind_isWrittenOnBind() = runTest {
        val fake = FakeClipboard()
        val pc = PcClipboard()
        pc.setText("first")
        pc.setText("latest")
        pc.bind(fake, this)
        advanceUntilIdle()
        assertEquals("latest", pc.text())
    }

    @Test
    fun setTextAfterBind_writesThrough() = runTest {
        val pc = PcClipboard()
        pc.bind(FakeClipboard(), this)
        pc.setText("hi")
        advanceUntilIdle()
        assertEquals("hi", pc.text())
    }

    @Test
    fun text_nonTextEntry_returnsNull() = runTest {
        val image = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
        }
        val pc = PcClipboard()
        pc.bind(FakeClipboard(ClipEntry(image)), this)
        assertNull(pc.text())
    }

    @Test
    fun text_unbound_returnsNull() = runTest { assertNull(PcClipboard().text()) }
}
