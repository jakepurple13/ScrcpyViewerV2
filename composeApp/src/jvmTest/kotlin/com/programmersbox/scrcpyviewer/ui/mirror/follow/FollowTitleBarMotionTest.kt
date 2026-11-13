package com.programmersbox.scrcpyviewer.ui.mirror.follow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorUiState
import com.programmersbox.scrcpyviewer.ui.mirror.TopStrip
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.window.AppTitleBarScope
import com.programmersbox.scrcpyviewer.ui.window.WindowChrome
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Nothing snaps" in the mirror title bar: frame by frame (16 ms), no edge of a part moves by more than an animation
 * can in one frame. Edges are the parts' visible (clipped) bounds, so a part shrinking or expanding counts too.
 *
 * The bounds. Every title bar change runs 200–250 ms with [androidx.compose.animation.core.FastOutSlowInEasing]; its
 * steepest 16 ms moves about a quarter of the travel (measured: 11 of 61 px for a new leader's name, 38 of 154 px for
 * the chip leaving at 420 dp). So:
 * - [MAX_SHARE]: an edge seen in every frame moves at most 35 % of its total travel in one frame; a snap moves 100 %.
 * - [MAX_STEP_PX]: no edge moves more than 64 px in one frame; the widest part (the chip, at most
 *   [FOLLOWING_MAX] 220 dp plus "Layout differs") moves ~25 % of ~250 px ≈ 62 px at its steepest. This covers edges
 *   revealed or clipped away midway, whose travel is partial.
 * - [FIRST_FRAMES_PX]: the easing starts flat (under 2 % of the travel in the first two frames), so a jump there, such
 *   as a width cap changing at once (the leaving chip's did, by 16 dp), shows up even when it is small.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FollowTitleBarMotionTest {
    private val scenes = ArrayList<ImageComposeScene>()

    @AfterTest
    fun tearDown() = scenes.forEach { it.close() }

    private object Bar : AppTitleBarScope {
        override val chrome get() = WindowChrome.Custom
        override fun Modifier.interactive(key: String): Modifier = this
    }

    private val state = MirrorUiState(
        model = "Pixel 8 Pro",
        details = "Android 15 · USB",
        status = SessionStatus.Running,
        frameSize = IntSize(1344, 2992),
        maxSize = 1920,
        codec = "H.264",
        bitRate = "8 Mbps",
        fps = 59.8,
        fittedScale = 0.37f,
        command = "scrcpy -s 39121FDJH004AB --max-size=1920",
    )

    private val others = listOf(FollowCandidate("A15", "Galaxy A15 5G"), FollowCandidate("TAB", "Pixel Tablet · emulator"))

    private fun strip(role: FollowRole, leaderTitle: String?) =
        FollowStrip(role, others, captureOn = false, leaderTitle = leaderTitle, layoutDiffers = false, onChange = {})

    /**
     * Each labelled node's left and right edge (window px, clipped to what shows), keyed by its text or description.
     * A node clipped away to nothing (a part that has finished leaving) counts as gone: its empty bounds sit anywhere.
     */
    private fun SceneDriver.edges(): Map<String, Pair<Float, Float>> {
        val out = HashMap<String, Pair<Float, Float>>()
        fun walk(n: SemanticsNode) {
            val key = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            val b = n.boundsInWindow
            if (key != null && key !in out && b.width >= 1f) out[key] = b.left to b.right
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out
    }

    /** One edge's per-frame steps; [whole] when its node shows in every frame (not revealed or clipped away midway). */
    private class Track(val key: String, val steps: List<Float>, val whole: Boolean) {
        val travel get() = steps.sum()
        val max get() = steps.max()
    }

    /** Renders [from], switches to [to], and returns every edge's per-frame step for the 30 frames after. */
    private fun steps(width: Int, from: FollowStrip, to: FollowStrip): List<Track> {
        var follow by mutableStateOf(from)
        val scene = ImageComposeScene(width, 40, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) { Box(Modifier.fillMaxSize()) { Bar.TopStrip(state, follow) {} } }
        }
        scenes += scene
        val d = SceneDriver(scene)
        d.frames(60)
        val seen = arrayListOf(d.edges())
        follow = to
        repeat(30) { d.frame(); seen += d.edges() }
        val keys = seen.flatMap { it.keys }.toSet()
        return keys.flatMap { k ->
            listOf("$k (left)" to { e: Pair<Float, Float> -> e.first }, "$k (right)" to { e: Pair<Float, Float> -> e.second }).map { (name, side) ->
                val steps = seen.zipWithNext { a, b -> if (k in a && k in b) abs(side(b.getValue(k)) - side(a.getValue(k))) else 0f }
                Track(name, steps, whole = seen.all { k in it })
            }
        }
    }

    private fun assertSmooth(tracks: List<Track>, what: String) {
        val moving = tracks.filter { it.travel >= MIN_TRAVEL }
        assertTrue(moving.isNotEmpty(), "$what: something should move")
        for (t in moving) {
            val report = "$what: ${t.key} steps ${t.steps.map { "%.1f".format(it) }}"
            assertTrue(t.max <= MAX_STEP_PX, "no frame moves more than $MAX_STEP_PX px. $report")
            if (t.whole) assertTrue(t.max <= MAX_SHARE * t.travel, "no frame moves more than ${(MAX_SHARE * 100).toInt()} % of the travel. $report")
            assertTrue(t.steps.take(2).all { it <= FIRST_FRAMES_PX }, "the first two frames barely move. $report")
        }
    }

    @Test
    fun `a new leader's name crossfades and the chip's width follows`() {
        val a = strip(FollowRole.Follower("A15"), "Galaxy A15 5G")
        val b = strip(FollowRole.Follower("TAB"), "Pixel Tablet · emulator")
        assertSmooth(steps(1200, a, b), "leader A to B")
        assertSmooth(steps(1200, b, a), "leader B to A")
    }

    @Test
    fun `stopping following at a narrow width collapses the chip and brings the controls back smoothly`() {
        val following = strip(FollowRole.Follower("A15"), "Galaxy A15 5G")
        val idle = strip(FollowRole.None, null)
        for (w in listOf(320, 420, 560)) {
            assertSmooth(steps(w, following, idle), "stop at $w")
            assertSmooth(steps(w, idle, following), "start at $w")
        }
    }

    private companion object {
        const val MAX_STEP_PX = 64f
        const val MAX_SHARE = 0.35f
        const val FIRST_FRAMES_PX = 6f
        /** Edges that move less than this in all are rounding, not motion. */
        const val MIN_TRAVEL = 4f
    }
}
