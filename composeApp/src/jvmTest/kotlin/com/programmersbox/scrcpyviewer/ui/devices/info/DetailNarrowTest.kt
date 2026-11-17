package com.programmersbox.scrcpyviewer.ui.devices.info

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.programmersbox.scrcpyviewer.info.BatteryHealth
import com.programmersbox.scrcpyviewer.info.BatteryInfo
import com.programmersbox.scrcpyviewer.info.ChargeStatus
import com.programmersbox.scrcpyviewer.info.DisplayInfo
import com.programmersbox.scrcpyviewer.info.LiveInfo
import com.programmersbox.scrcpyviewer.info.MemoryInfo
import com.programmersbox.scrcpyviewer.info.NetworkInfo
import com.programmersbox.scrcpyviewer.info.PowerSource
import com.programmersbox.scrcpyviewer.info.SoftwareInfo
import com.programmersbox.scrcpyviewer.info.StaticInfo
import com.programmersbox.scrcpyviewer.info.StorageBreakdown
import com.programmersbox.scrcpyviewer.info.StorageInfo
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.ControlSize
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The detail pane's tab row and the Info tab in a narrow pane (1 px = 1 dp): the tabs fold to a menu below 344 dp and
 * never overlap the back arrow, "Copy as text" becomes an icon below 220 dp of content, and a long IP stays inside
 * its card. Motion is on and passed explicitly: CI runners report reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DetailNarrowTest {
    @Test
    fun `the tabs are a menu below 344 dp`() {
        assertEquals(ControlSize.Menu, detailTabsSize(343.dp))
        assertEquals(ControlSize.Full, detailTabsSize(344.dp))
    }

    @Test
    fun `the footer copies with an icon below 220 dp`() {
        assertTrue(infoFooterIconCopy(219.dp))
        assertFalse(infoFooterIconCopy(220.dp))
    }

    private val now = 1_800_000_000_000L

    private val info = DeviceInfoUiState(
        serial = "R5CX12GDWPZ", title = "Galaxy A15 5G",
        static = Load.Ready(
            StaticInfo(
                software = SoftwareInfo(
                    androidVersion = "16", sdk = 36, securityPatch = "2026-07-05", buildId = "BP4A.251205.006.A156U1UESCEZG1",
                    abi = "arm64-v8a", manufacturer = "samsung", model = "SM-A156U1", oneUiVersion = "8.5",
                    kernel = "5.15.189-android13-8-33641424",
                ),
                display = DisplayInfo(1080, 2340, 450, null, null, null, 90.0),
                storage = StorageInfo(110534640L * 1024, 18165728L * 1024, StorageBreakdown(14848917338, 781352384, 24251482112, 1679273984)),
                network = NetworkInfo("Office-5G", "fe80:0000:0000:0000:1234:5678:9abc:def0", 351),
            ),
        ),
        live = Load.Ready(LiveInfo(BatteryInfo(100, ChargeStatus.Full, PowerSource.Usb, 29.2, 4334, BatteryHealth.Good), MemoryInfo(3644192, 931408))),
        liveUpdatedAt = now - 12_000,
    )

    private var tab by mutableStateOf(DetailTab.Info)
    private var width by mutableIntStateOf(224)

    /** The tab row (with the back arrow when [back]) over the Info pane, in a pane [startWidth] wide. */
    private fun scene(startWidth: Int, back: Boolean = true, block: SceneDriver.() -> Unit) = onEdt {
        width = startWidth
        tab = DetailTab.Info
        val driver = SceneDriver(ImageComposeScene(1000, 900, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false, LocalInfoNow provides { now }) {
                    Surface {
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            Column {
                                DetailTabRow(
                                    selected = true, tab = tab, onTab = { tab = it }, onBack = if (back) ({}) else null, reduceMotion = false,
                                )
                                DeviceInfoPane(
                                    info, {}, Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp),
                                    reduceMotion = false,
                                )
                            }
                        }
                    }
                }
            }
        })
        try {
            driver.frames(150)
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out.filter { it.layoutInfo.isPlaced }
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    private fun SemanticsNode.described(label: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    private fun SemanticsNode.has(label: String): Boolean = label in texts() || described(label) || children.any { it.has(label) }
    private fun SemanticsNode.shown() = boundsInRoot.width > 0f && boundsInRoot.height > 0f

    private fun SceneDriver.clickable(label: String) = nodes().filter { SemanticsActions.OnClick in it.config && it.has(label) && it.shown() }
    private fun SceneDriver.text(label: String) = nodes().filter { label in it.texts() && it.shown() }
    private fun SceneDriver.described(label: String) = nodes().filter { it.described(label) && it.shown() }

    /** The node holding both tabs: the segmented row, or the menu button. */
    private fun SceneDriver.tabControl() = clickable("Info").first().let { info ->
        clickable("Options").firstOrNull()?.let { options -> info.boundsInRoot.copy(left = minOf(info.boundsInRoot.left, options.boundsInRoot.left)) }
            ?: info.boundsInRoot
    }

    @Test
    fun `224 wide - one Info menu, the back arrow clear of it, and the icon copy inside the pane`() = scene(224) {
        assertEquals(1, clickable("Info").size, "one Info control")
        assertTrue(text("Options").isEmpty(), "Options hides in the menu")
        val arrow = described("Back to list").single().boundsInRoot
        val tabs = tabControl()
        assertFalse(arrow.overlaps(tabs), "arrow $arrow and tabs $tabs overlap")
        assertTrue(arrow.bottom > tabs.top && arrow.right <= tabs.left, "one line, the arrow ends before the tabs: $arrow, $tabs")
        assertTrue(tabs.right <= 224f && tabs.left >= 0f, "the tabs fit: $tabs")
        val copy = assertNotNull(described("Copy as text").firstOrNull(), "the icon copy").boundsInRoot
        assertTrue(copy.left >= 0f && copy.right <= 224f, "copy inside 224: $copy")
        assertTrue(text("Copy as text").isEmpty(), "no text label")

        clickable("Info").single().config[SemanticsActions.OnClick].action?.invoke()
        frames(60)
        assertTrue(text("Options").isNotEmpty(), "the menu offers Options")
    }

    @Test
    fun `a long IP wraps under its label inside the card`() = scene(224) {
        // Below the fold: read the laid-out bounds, not the scroll-clipped ones.
        fun rect(label: String) = nodes().single { label in it.texts() }.let { Rect(it.positionInRoot, it.size.toSize()) }
        val ip = rect("fe80:0000:0000:0000:1234:5678:9abc:def0")
        val label = rect("IP address")
        assertTrue(ip.top >= label.bottom, "under the label: ip $ip, label $label")
        assertTrue(ip.right <= 224f - 18f, "inside the pane: $ip")
    }

    @Test
    fun `640 wide - Options and Info segments, and Copy as text`() = scene(640) {
        assertTrue(text("Options").isNotEmpty(), "Options shows")
        assertTrue(text("Info").isNotEmpty(), "Info shows")
        assertTrue(text("Copy as text").isNotEmpty(), "the labelled copy button")
    }

    @Test
    fun `the back arrow and tabs share a line with the tabs clear of the arrow`() = scene(640) {
        for (w in listOf(640, 520, 420, 360, 300, 224)) {
            width = w
            frames(150)
            val arrow = described("Back to list").single().boundsInRoot
            val tabs = tabControl()
            assertFalse(arrow.overlaps(tabs), "$w: arrow $arrow and tabs $tabs overlap")
            assertTrue(arrow.bottom > tabs.top && arrow.right <= tabs.left, "$w: arrow $arrow, tabs $tabs")
        }
    }

    @Test
    fun `no back arrow - Full tabs centred in the full width`() = scene(400, back = false) {
        val tabs = tabControl()
        assertTrue(text("Options").isNotEmpty())
        assertTrue(kotlin.math.abs((tabs.left + tabs.right) / 2 - 200f) < 2f, "centred: $tabs")
    }
}
