package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.emulator.AndroidSdk
import com.programmersbox.scrcpyviewer.emulator.Avd
import com.programmersbox.scrcpyviewer.emulator.AvdKind
import com.programmersbox.scrcpyviewer.emulator.AvdState
import com.programmersbox.scrcpyviewer.emulator.SdkLookup
import com.programmersbox.scrcpyviewer.links.LinkStore
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.state.EmulatorPrefs
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorRow
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorSection
import com.programmersbox.scrcpyviewer.ui.devices.emulators.EmulatorsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.AppsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.links.LinksState
import com.programmersbox.scrcpyviewer.ui.mirror.links.LinksTabContent
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatController
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatTab
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * One reduce-motion source: the screens follow [LocalReduceMotion] without being passed `motion`. The scene provides
 * the local directly, as a window root (AppWindow) does. Under reduced motion a breakpoint flip lands in one frame;
 * otherwise a moved node is between its places on the third frame after the resize (the frame that lays out the new
 * width and the spring's start frame still show the old place) and settles on the layout spring.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MotionConsistencyTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    /** A screen [from] dp wide, then [to]; [probe] reads the moved node's position (or size) on a frame. */
    private class Case(val name: String, val from: Int, val to: Int, val content: @Composable () -> Unit, val probe: SceneDriver.() -> Float)

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out.filter { it.layoutInfo.isPlaced }
    }

    private fun SceneDriver.described(label: String): SemanticsNode = assertNotNull(
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true },
        "$label on screen",
    )

    private fun SceneDriver.text(label: String): SemanticsNode = assertNotNull(
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true },
        "\"$label\" on screen",
    )

    private fun logcat(): Case {
        val source = object : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {}
        val controller = LogcatController(source, scope, {}, io = EmptyCoroutineContext)
        // Under 420 dp the Play button narrows to its icon.
        return Case("Logcat", 720, 300, { LogcatTab(controller, widthNow.dp) }) {
            described("Start logcat").boundsInRoot.width
        }
    }

    private fun links(): Case {
        val runner = object : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "s") {
            override suspend fun run(cmd: String, timeoutMs: Long) = ShellResult("", 0, 1)
        }
        val links = LinksState(
            "d1", runner, LinkStore(Files.createTempDirectory("motion-links").resolve("links.json")), { emptyList() }, scope, clock = { 1L },
        )
        // Under 300 dp "Before opening" stacks under "Open with".
        return Case("Links", 520, 260, {
            LinksTabContent(links, AppsUiState(apps = emptyList()), enabled = true, onEvent = links::onEvent, now = { 1L })
        }) { text("Before opening").layoutInfo.coordinates.positionInRoot().y }
    }

    private fun emulators(): Case {
        val root = Paths.get("sdk-root")
        val avd = Avd("Pixel_9_API_36", "Pixel 9", 36, "16", AvdKind.Phone, root.resolve("Pixel_9_API_36.avd"))
        val state = EmulatorsUiState(
            sdk = SdkLookup.Found(AndroidSdk(root, root.resolve("emulator").resolve("emulator"), "37.3.9")),
            rows = listOf(EmulatorRow(avd, AvdState.Running("emulator-5554"), 8_804_682_956L, EmulatorPrefs(lastBootMillis = 20_000))),
            expanded = true,
        )
        // Under 300 dp inside its padding, the row's Stop moves to a second line.
        return Case("Emulators", 360, 280, { EmulatorSection(state, {}, {}, now = { 10_000 }) }) {
            nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Stop") == true }.positionInRoot.y
        }
    }

    private var widthNow by mutableIntStateOf(0)

    private fun run(case: Case, reduceMotion: Boolean, block: SceneDriver.(before: Float) -> Unit) {
        widthNow = case.from
        val driver = SceneDriver(ImageComposeScene(800, 900, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface {
                        Box(Modifier.width(widthNow.dp).fillMaxHeight()) { case.content() }
                    }
                }
            }
        })
        try {
            driver.frames(60)
            val before = driver.(case.probe)()
            widthNow = case.to
            driver.block(before)
        } finally {
            driver.scene.close()
        }
    }

    private fun cases() = listOf(logcat(), links(), emulators())

    @Test
    fun `under reduced motion from the window root, a breakpoint flip lands in one frame`() = onEdt {
        for (case in cases()) {
            run(case, reduceMotion = true) { before ->
                frame()
                val oneFrame = case.probe(this)
                frames(60)
                val settled = case.probe(this)
                assertTrue(settled != before, "${case.name}: the flip moves the node ($before -> $settled)")
                assertEquals(settled, oneFrame, "${case.name}: no in-between position ($before, $oneFrame, $settled)")
            }
        }
        scope.cancel()
    }

    @Test
    fun `with motion from the window root, a breakpoint flip glides and settles on the layout spring`() = onEdt {
        for (case in cases()) {
            run(case, reduceMotion = false) { before ->
                // The frame that lays out the new width and the spring's start frame still show the old place.
                val glide = List(3) { frame(); case.probe(this) }
                frames(60)
                val settled = case.probe(this)
                frames(60)
                assertEquals(settled, case.probe(this), "${case.name}: settled within 60 frames")
                val mid = glide.last()
                val between = if (settled > before) mid > before && mid < settled else mid < before && mid > settled
                assertTrue(between, "${case.name}: one frame into the glide it is between ($before, $glide, $settled)")
            }
        }
        scope.cancel()
    }
}
