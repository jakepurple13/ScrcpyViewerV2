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

    private fun room(w: Dp, follow: FollowStrip?, c: ChipWidths? = null) = stripRoom(w, mins, hasDetails = true, follow, c)

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
}
