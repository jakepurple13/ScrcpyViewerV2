package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayOrientationTest {
    private val frame = IntSize(400, 200)

    /** A view the oriented frame fills exactly (scale 1), so view pixels are displayed-image pixels. */
    private fun mapper(o: DisplayOrientation, view: Rect = o.orientedSize(frame).let { Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()) }) =
        InputMapper(frameSize = { frame }, viewRect = { view }, orientation = o)

    private fun at(o: DisplayOrientation, x: Float, y: Float) = mapper(o).toDevice(Offset(x, y), clamp = false)

    @Test
    fun `parse reads every cli value and falls back to 0`() {
        val values = listOf("0", "90", "180", "270", "flip0", "flip90", "flip180", "flip270")
        assertEquals(DisplayOrientation.entries, values.map(DisplayOrientation::parse))
        assertEquals(DisplayOrientation.R0, DisplayOrientation.parse(null))
        assertEquals(DisplayOrientation.R0, DisplayOrientation.parse("45"))
    }

    @Test
    fun `display-orientation wins over orientation, which also applies to the display`() {
        assertEquals(DisplayOrientation.R90, DisplayOrientation.forOptions(OptionValues(mapOf("orientation" to "90"))))
        assertEquals(
            DisplayOrientation.Flip180,
            DisplayOrientation.forOptions(OptionValues(mapOf("orientation" to "90", "display-orientation" to "flip180"))),
        )
        assertEquals(DisplayOrientation.R0, DisplayOrientation.forOptions(OptionValues(mapOf("record-orientation" to "90"))))
    }

    @Test
    fun `oriented size swaps for quarter turns`() {
        assertEquals(IntSize(200, 400), DisplayOrientation.R90.orientedSize(frame))
        assertEquals(IntSize(200, 400), DisplayOrientation.Flip270.orientedSize(frame))
        assertEquals(frame, DisplayOrientation.R180.orientedSize(frame))
        assertEquals(frame, DisplayOrientation.Flip0.orientedSize(frame))
    }

    @Test
    fun `maps a view point back to the frame under every rotation and flip`() {
        // screen.c sc_screen_convert_window_to_frame_coords, for the displayed point (10.5, 20.5).
        val expected = mapOf(
            DisplayOrientation.R0 to (10 to 20),
            DisplayOrientation.R90 to (20 to 189),
            DisplayOrientation.R180 to (389 to 179),
            DisplayOrientation.R270 to (379 to 10),
            DisplayOrientation.Flip0 to (389 to 20),
            DisplayOrientation.Flip90 to (379 to 189),
            DisplayOrientation.Flip180 to (10 to 179),
            DisplayOrientation.Flip270 to (20 to 10),
        )
        for ((o, xy) in expected) {
            assertEquals(Position(xy.first, xy.second, 400, 200), at(o, 10.5f, 20.5f), "$o")
        }
    }

    @Test
    fun `display corners land on frame corners`() {
        // 90 clockwise: the display's top-left shows the frame's bottom-left.
        assertEquals(Position(0, 199, 400, 200), at(DisplayOrientation.R90, 0f, 0f))
        assertEquals(Position(399, 199, 400, 200), at(DisplayOrientation.R180, 0f, 0f))
        assertEquals(Position(399, 0, 400, 200), at(DisplayOrientation.R270, 0f, 0f))
        assertEquals(Position(399, 0, 400, 200), at(DisplayOrientation.Flip0, 0f, 0f))
    }

    @Test
    fun `letterbox follows the oriented size`() {
        // A 200x400 displayed image in a 400x400 view: bars on the left and right (x < 100, x >= 300).
        val m = mapper(DisplayOrientation.R90, Rect(0f, 0f, 400f, 400f))
        assertNull(m.toDevice(Offset(50f, 200f), clamp = false))
        assertNull(m.toDevice(Offset(350f, 200f), clamp = false))
        assertEquals(Position(200, 100, 400, 200), m.toDevice(Offset(200f, 200f), clamp = false))
        // Clamped (a held button dragged into a bar) stays on the frame.
        assertEquals(Position(200, 199, 400, 200), m.toDevice(Offset(0f, 200f), clamp = true))
    }

    @Test
    fun `toView puts a frame position back where it shows, for every orientation`() {
        for (o in DisplayOrientation.entries) {
            // Letterboxed both ways, so the content rect's offset matters too.
            val m = mapper(o, Rect(10f, 20f, 610f, 520f))
            for (p in listOf(Offset(150f, 150f), Offset(300f, 400f), Offset(500f, 260f))) {
                val device = m.toDevice(p, clamp = false) ?: continue
                val back = m.toView(device)!!
                val again = m.toDevice(back, clamp = false)
                assertEquals(device, again, "$o: $p -> $device -> $back")
            }
        }
        assertNull(InputMapper(frameSize = { null }, viewRect = { Rect(0f, 0f, 10f, 10f) }).toView(Position(1, 1, 400, 200)))
    }
}
