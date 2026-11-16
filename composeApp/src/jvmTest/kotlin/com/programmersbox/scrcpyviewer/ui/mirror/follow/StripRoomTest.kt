package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StripRoomTest {
    private val mins = StripMins(details = 740.dp, copy = 620.dp, fit = 480.dp, zoom = 340.dp)
    private val other = listOf(FollowCandidate("B", "Pixel 9"))
    private fun strip(role: FollowRole, others: List<FollowCandidate> = other, differs: Boolean = false) =
        FollowStrip(role, others, captureOn = false, leaderTitle = "Galaxy A15 5G", layoutDiffers = differs, onChange = {})

    private val chip = ChipWidths(model = 80.dp, following = 150.dp, differs = 106.dp, differsIcon = 28.dp)

    private fun room(w: Dp, follow: FollowStrip?, c: ChipWidths? = null, arrange: Boolean = false) =
        stripRoom(w, mins, hasDetails = true, follow, c, arrange)

    @Test
    fun `a lone window keeps today's cutoffs exactly and shows no send control`() {
        val alone = strip(FollowRole.None, others = emptyList())
        for (w in (300..1000 step 5).map { it.dp }) {
            val a = room(w, alone)
            assertEquals(room(w, null), a, "at $w")
            assertEquals(SendPlace.None, a.send)
        }
        assertTrue(room(740.dp, alone).details)
        assertFalse(room(739.dp, alone).details)
        assertTrue(room(620.dp, alone).copy)
        assertFalse(room(619.dp, alone).copy)
    }

    @Test
    fun `with another window open the button has words, then an icon, then sits in the overflow`() {
        val idle = strip(FollowRole.None)
        assertEquals(SendPlace.Words, room(760.dp, idle).send)
        assertEquals(SendPlace.Icon, room(759.dp, idle).send)
        assertEquals(SendPlace.Icon, room(480.dp, idle).send)
        val narrow = room(479.dp, idle)
        assertEquals(SendPlace.Overflow, narrow.send)
        assertTrue(narrow.overflow)
        // It takes its room from copy and the details first.
        assertFalse(room(679.dp, idle).copy)
        assertTrue(room(680.dp, idle).copy)
        assertFalse(room(889.dp, idle).details)
    }

    @Test
    fun `a no-control window has no send control, neither in the bar nor in the overflow`() {
        val noControl = strip(FollowRole.None).copy(control = false)
        for (w in (300..1400 step 10).map { it.dp }) {
            assertEquals(room(w, null), room(w, noControl), "today's cutoffs at $w")
        }
    }

    @Test
    fun `a follower never shows the send control`() {
        for (w in (300..1400 step 10).map { it.dp }) {
            assertEquals(SendPlace.None, room(w, strip(FollowRole.Follower("A")), chip).send, "at $w")
        }
    }

    @Test
    fun `a follower drops copy, then Fit, then zoom before the chip has to shrink`() {
        val f = strip(FollowRole.Follower("A"))
        val extra = 160.dp // the chip and its gap
        assertTrue(room(620.dp + extra, f, chip).copy)
        assertFalse(room(620.dp + extra - 1.dp, f, chip).copy)
        assertTrue(room(480.dp + extra, f, chip).fit)
        assertFalse(room(480.dp + extra - 1.dp, f, chip).fit)
        assertTrue(room(340.dp + extra, f, chip).zoom)
        assertFalse(room(340.dp + extra - 1.dp, f, chip).zoom)
        // In that order as the strip narrows: never Fit without copy's room gone first, and so on.
        for (w in (300..1400 step 5).map { it.dp }) {
            val r = room(w, f, chip)
            if (r.copy) assertTrue(r.fit && r.zoom, "at $w")
            if (r.fit) assertTrue(r.zoom, "at $w")
        }
    }

    @Test
    fun `Layout differs becomes an icon only once every control is gone and still nothing fits`() {
        val f = strip(FollowRole.Follower("A"), differs = true)
        val full = FOLLOWER_FIXED + chip.model + chip.following + chip.differs
        assertFalse(room(full, f, chip).compactWarning)
        val tight = room(full - 1.dp, f, chip)
        assertTrue(tight.compactWarning)
        assertFalse(tight.zoom || tight.fit || tight.copy, "controls go first: $tight")
        // With words the cutoffs count the full pill, with the icon only the icon.
        assertTrue(room(340.dp + 150.dp + 106.dp + 10.dp, f, chip).zoom)
        assertFalse(room(340.dp + 150.dp + 106.dp + 9.dp, f, chip).zoom)
    }

    // --- Arrange ---------------------------------------------------------------------------------------------------

    @Test
    fun `without arrange nothing changes and there is no arrange control`() {
        val alone = strip(FollowRole.None, others = emptyList())
        for (w in (300..1400 step 5).map { it.dp }) {
            for (f in listOf(null, alone, strip(FollowRole.None))) {
                assertEquals(ArrangePlace.None, room(w, f).arrange, "at $w")
                assertEquals(room(w, f), room(w, f, arrange = false), "at $w")
            }
        }
    }

    @Test
    fun `the arrange button sits in the strip down to the Fit cutoff, then in the overflow`() {
        // No send control (a no-control window, or follow not wired): every cutoff but zoom moves up by its room.
        assertEquals(ArrangePlace.Strip, room(480.dp + ARRANGE_ROOM, null, arrange = true).arrange)
        assertTrue(room(480.dp + ARRANGE_ROOM, null, arrange = true).fit)
        val narrow = room(480.dp + ARRANGE_ROOM - 1.dp, null, arrange = true)
        assertEquals(ArrangePlace.Overflow, narrow.arrange)
        assertFalse(narrow.fit)
        assertTrue(narrow.overflow)
        assertTrue(room(620.dp + ARRANGE_ROOM, null, arrange = true).copy)
        assertFalse(room(620.dp + ARRANGE_ROOM - 1.dp, null, arrange = true).copy)
        assertTrue(room(740.dp + ARRANGE_ROOM, null, arrange = true).details)
        assertFalse(room(740.dp + ARRANGE_ROOM - 1.dp, null, arrange = true).details)
        assertTrue(room(340.dp, null, arrange = true).zoom)
        assertFalse(room(339.dp, null, arrange = true).zoom)
    }

    @Test
    fun `next to the send button both go into the overflow together, and the words need the arrange room too`() {
        val idle = strip(FollowRole.None)
        val wide = room(760.dp + ARRANGE_ROOM, idle, arrange = true)
        assertEquals(SendPlace.Words, wide.send)
        assertEquals(ArrangePlace.Strip, wide.arrange)
        assertEquals(SendPlace.Icon, room(760.dp + ARRANGE_ROOM - 1.dp, idle, arrange = true).send)
        val atFit = room(480.dp + ARRANGE_ROOM, idle, arrange = true)
        assertEquals(SendPlace.Icon, atFit.send)
        assertEquals(ArrangePlace.Strip, atFit.arrange)
        val below = room(480.dp + ARRANGE_ROOM - 1.dp, idle, arrange = true)
        assertEquals(SendPlace.Overflow, below.send)
        assertEquals(ArrangePlace.Overflow, below.arrange)
        assertTrue(room(680.dp + ARRANGE_ROOM, idle, arrange = true).copy)
        assertFalse(room(680.dp + ARRANGE_ROOM - 1.dp, idle, arrange = true).copy)
        assertTrue(room(890.dp + ARRANGE_ROOM, idle, arrange = true).details)
        assertFalse(room(890.dp + ARRANGE_ROOM - 1.dp, idle, arrange = true).details)
    }

    @Test
    fun `a follower arranges too, and its parts still go copy, then Fit with Arrange, then zoom`() {
        val f = strip(FollowRole.Follower("A"))
        val extra = 160.dp
        assertEquals(ArrangePlace.Strip, room(480.dp + extra + ARRANGE_ROOM, f, chip, arrange = true).arrange)
        assertEquals(ArrangePlace.Overflow, room(480.dp + extra + ARRANGE_ROOM - 1.dp, f, chip, arrange = true).arrange)
        assertTrue(room(340.dp + extra, f, chip, arrange = true).zoom)
        for (w in (300..1400 step 5).map { it.dp }) {
            for (follow in listOf(f, strip(FollowRole.None), null)) {
                val r = room(w, follow, chip, arrange = true)
                assertEquals(r.fit, r.arrange == ArrangePlace.Strip, "Arrange goes with Fit at $w")
                if (r.copy) assertTrue(r.fit && r.zoom, "at $w")
                if (r.fit) assertTrue(r.zoom, "at $w")
                if (r.details) assertTrue(r.copy, "at $w")
            }
        }
    }
}
