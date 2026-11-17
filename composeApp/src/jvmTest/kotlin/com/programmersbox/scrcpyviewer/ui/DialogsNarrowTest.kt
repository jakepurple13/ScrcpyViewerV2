package com.programmersbox.scrcpyviewer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.devices.WifiPairingActions
import com.programmersbox.scrcpyviewer.ui.devices.WifiPairingDialog
import com.programmersbox.scrcpyviewer.ui.devices.WifiPairingState
import com.programmersbox.scrcpyviewer.ui.onboarding.OnboardingDialog
import com.programmersbox.scrcpyviewer.ui.update.UpdateDialog
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateState
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * Lays out each dialog in an offscreen window (1 px = 1 dp) and reads bounds off the semantics tree: narrow windows
 * (360x640) shrink and stack, wide ones keep the designed layout, and restacking animates the card height.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DialogsNarrowTest {
    private val colors = lightColorScheme()

    /** An offscreen scene with a virtual clock that keeps the last rendered frame. */
    private class Driver(val scene: ImageComposeScene) {
        private var time = 0L
        var image: Image? = null

        fun frames(count: Int) = repeat(count) {
            time += 16_000_000
            image = scene.render(time)
        }
    }

    private class Window(val driver: Driver) {
        fun all(): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(node: SemanticsNode) {
                out += node
                node.children.forEach(::walk)
            }
            driver.scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return out
        }

        private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

        private fun first(what: String, match: (SemanticsNode) -> Boolean): Rect =
            assertNotNull(all().firstOrNull(match), "$what is not in the semantics tree").boundsInRoot

        fun tag(tag: String) = first("tag $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun text(startsWith: String) = first("text $startsWith") { n -> n.texts().any { it.startsWith(startsWith) } }
        fun described(description: String) =
            first("image $description") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }

        /** The button around [label]: the clickable node whose subtree shows that text. */
        fun button(label: String): Rect {
            fun hasText(node: SemanticsNode): Boolean = node.texts().contains(label) || node.children.any(::hasText)
            return first("button $label") { SemanticsActions.OnClick in it.config && hasText(it) }
        }

        fun resize(width: Int, height: Int = 640) {
            driver.scene.constraints = Constraints.fixed(width, height)
            driver.frames(120)
        }

        /** Rows at the window's middle column that the card paints opaquely (the scrim is translucent). */
        fun drawnHeight(): Int {
            val image = checkNotNull(driver.image)
            val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
            image.readPixels(bitmap)
            val x = image.width / 2
            return (0 until image.height).count { y -> (bitmap.getColor(x, y) ushr 24) == 255 }
        }

        fun close() = driver.scene.close()
    }

    /** Must run on the Swing thread (see [onEdt]). */
    private fun window(width: Int, height: Int = 640, content: @Composable () -> Unit): Window {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme(colorScheme = colors) { content() }
        }
        return Window(Driver(scene)).also { it.driver.frames(120) }
    }

    private fun check(width: Int, height: Int = 640, content: @Composable () -> Unit, block: (Window) -> Unit) = onEdt {
        val w = window(width, height, content)
        try {
            block(w)
        } finally {
            w.close()
        }
    }

    private val pairing: @Composable () -> Unit = {
        WifiPairingDialog(
            WifiPairingState(qrPayload = "WIFI:T:ADB;S:studio-x;P:secret;;", qrName = "studio-x"),
            WifiPairingActions(),
            onDismiss = {},
        )
    }
    private val onboarding: @Composable () -> Unit = { OnboardingDialog(page = 0, onPage = {}, onFinish = {}, privacy = null) }

    private val release = Release(version = "1.6.0", body = "- Fix", htmlUrl = "https://example.test/r")
    private val update: @Composable () -> Unit = {
        UpdateDialog(
            UpdateUiState(
                state = UpdateState.Available(release),
                available = release,
                currentVersion = "1.5.0",
                showPopup = true,
                canSelfUpdate = true,
            ),
            {}, {}, {},
        )
    }

    @Test
    fun `pairing card fits a 360 window and the QR stacks above the steps`() = check(360, content = pairing) { w ->
        val card = w.tag("pairing-card")
        val qr = w.described("Pairing QR code")
        val steps = w.text("1. ")
        assertTrue(card.width <= 328f, "card width ${card.width} must be <= 328")
        assertTrue(qr.height <= 150f, "the QR shrinks to 150 dp when stacked: ${qr.height}")
        assertTrue(qr.bottom <= steps.top, "QR bottom ${qr.bottom} is above the first step's top ${steps.top}")
    }

    @Test
    fun `pairing keeps the QR beside the steps in a wide window`() = check(1280, 800, content = pairing) { w ->
        val qr = w.described("Pairing QR code")
        val steps = w.text("1. ")
        assertTrue(qr.right <= steps.left, "QR ${qr} is left of the steps ${steps}")
        assertTrue(qr.height >= 150f, "the QR is not shrunk: ${qr.height}")
        assertTrue(steps.top < qr.bottom && steps.bottom > qr.top, "QR and steps share a row")
    }

    @Test
    fun `onboarding shows Next inside a 360 window`() = check(360, content = onboarding) { w ->
        val next = w.button("Next")
        val card = w.tag("onboarding-card")
        assertTrue(next.left >= 0f && next.right <= 360f, "Next is inside the window: $next")
        assertTrue(card.width <= 328f, "card width ${card.width} must be <= 328")
        val dots = w.tag("page-dots")
        assertTrue(dots.bottom <= next.top, "dots take their own line above the buttons: $dots vs $next")
    }

    @Test
    fun `onboarding keeps the dots in the button row in a wide window`() = check(800, 800, content = onboarding) { w ->
        val next = w.button("Next")
        val dots = w.tag("page-dots")
        assertTrue(dots.right <= next.left, "dots are left of the buttons: $dots vs $next")
        assertTrue(dots.top < next.bottom && dots.bottom > next.top, "dots share the buttons' row: $dots vs $next")
    }

    @Test
    fun `update stacks Update now full width above Later`() = check(360, content = update) { w ->
        val button = w.button("Update now")
        val later = w.button("Later")
        assertTrue(button.width >= 280f, "Update now is full width: $button")
        assertTrue(button.bottom <= later.top, "Update now sits above Later: $button vs $later")
    }

    @Test
    fun `update keeps its buttons in a row in a full-size card`() = check(440, content = update) { w ->
        val button = w.button("Update now")
        val later = w.button("Later")
        assertTrue(later.right <= button.left, "Later is left of Update now: $later vs $button")
        assertTrue(later.top < button.bottom && later.bottom > button.top, "one row: $later vs $button")
    }

    /** [tag]'s height settles at [wide] and at [narrow]; a few frames after narrowing it is strictly in between. */
    private fun assertHeightAnimates(
        tag: String,
        content: @Composable () -> Unit,
        wide: Int,
        narrow: Int,
        drawn: Boolean = false,
    ) = onEdt {
        val w = window(wide, content = content)
        try {
            val wideHeight = w.tag(tag).height
            val wideDrawn = w.drawnHeight()
            w.resize(narrow)
            val narrowHeight = w.tag(tag).height
            val narrowDrawn = w.drawnHeight()
            w.resize(wide)
            assertTrue(narrowHeight > wideHeight, "$tag: narrow ($narrowHeight) is taller than wide ($wideHeight)")
            w.driver.scene.constraints = Constraints.fixed(narrow, 640)
            w.driver.frames(4)
            val midway = w.tag(tag).height
            val midDrawn = w.drawnHeight()
            assertTrue(
                midway > wideHeight && midway < narrowHeight,
                "$tag height $midway is strictly between $wideHeight and $narrowHeight a few frames in",
            )
            // What is painted, not just what is laid out: the card background must grow with the content.
            if (drawn) {
                assertTrue(
                    midDrawn > wideDrawn && midDrawn < narrowDrawn,
                    "drawn card height $midDrawn is strictly between $wideDrawn and $narrowDrawn a few frames in",
                )
            }
        } finally {
            w.close()
        }
    }

    @Test
    fun `update card height animates when the window narrows`() = assertHeightAnimates("update-card", update, 440, 330, drawn = true)

    @Test
    fun `pairing QR and steps height animates when the window narrows`() =
        assertHeightAnimates("qr-steps", pairing, 600, 360)
}
