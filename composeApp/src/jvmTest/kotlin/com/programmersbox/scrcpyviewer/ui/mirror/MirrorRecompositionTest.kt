package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.apps.AppIconLoader
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.apps.DeviceApps
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What changes often in the mirror window (the fps and REC readouts every second; the quick actions sheet's icons,
 * search and drags) must not recompose the window: each host below reads what [MirrorWindow] reads to build its
 * [MirrorUiState] and counts its own compositions.
 */
class MirrorRecompositionTest {
    private val noAdb = object : AdbClient(adbPath = { error("no adb") }) {}
    private val eager = CoroutineScope(Dispatchers.Unconfined)
    private val frame = mutableStateOf<VideoFrame?>(null)

    private fun state(quickActions: QuickActionsUiState = QuickActionsUiState()) = MirrorUiState(
        model = "Pixel", details = "", status = SessionStatus.Running, frameSize = IntSize(1080, 2400),
        quickActions = quickActions,
    )

    private fun PixelMap.hash(): Int {
        var h = 1
        for (y in 0 until height) for (x in 0 until width) h = 31 * h + get(x, y).hashCode()
        return h
    }

    @Test
    fun `fps and REC ticks redraw the status line without recomposing the window`() = onEdt {
        val fps = mutableStateOf(0.0)
        val rec = mutableStateOf<String?>(null)
        val readouts = StatusReadouts(fps, rec)
        var host = 0
        var overlay = 0
        val driver = SceneDriver(ImageComposeScene(900, 700, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    SideEffect { host++ }
                    MirrorContent(
                        state(), frame, {}, {}, {}, {}, {}, {},
                        readouts = readouts,
                        stageOverlay = { SideEffect { overlay++ } },
                    )
                }
            }
        })
        try {
            driver.frames(30)
            val hostBefore = host
            val overlayBefore = overlay
            val before = driver.snapshot().hash()
            for (n in 1..3) {
                fps.value = 30.0 * n
                rec.value = recordingLabel(n * 1_000_000_000L)
                driver.frames(2)
            }
            val after = driver.snapshot().hash()
            assertNotEquals(before, after, "the status line shows the new readouts")
            assertEquals(hostBefore, host, "the window did not recompose")
            assertEquals(overlayBefore, overlay, "the stage did not recompose")
        } finally {
            driver.scene.close()
        }
    }

    private class ListedApps(val apps: List<DeviceApp>) : DeviceApps(
        object : AdbClient(adbPath = { error("no adb") }) {}, "SER", { null }, "5.0",
    ) {
        override suspend fun list(): List<DeviceApp> = apps
    }

    @Test
    fun `sheet search and icons recompose the sheet, not the window`() = onEdt {
        val png = org.jetbrains.skia.Surface.makeRasterN32Premul(16, 16).run {
            canvas.clear(0xFF3366CC.toInt())
            makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val iconGate = CompletableDeferred<Unit>()
        val loader = object : AppIconLoader(noAdb, "SER", null) {
            override suspend fun load(app: DeviceApp): ByteArray {
                iconGate.await()
                return png
            }
        }
        val apps = listOf(
            DeviceApp("com.android.chrome", "Chrome", false, "131", 677),
            DeviceApp("com.android.settings", "Settings", true, "14", 34),
        )
        val explorer = AppExplorer(ListedApps(apps), loader, eager) {}
        val qa = QuickActions(
            device = object : DeviceQuickActions(noAdb, "SER") {
                override suspend fun read(): Map<QuickToggle, Boolean?> = emptyMap()
            },
            scope = eager,
            apps = explorer,
        ) {}
        var host = 0
        var overlay = 0
        val driver = SceneDriver(ImageComposeScene(1400, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize()) {
                    SideEffect { host++ }
                    // MirrorWindow's narrow reads; the panel reads the full state through the lambda.
                    val narrow = QuickActionsUiState(open = qa.open, enabled = qa.enabled, tab = qa.shownTab, widths = qa.sheetWidths)
                    MirrorContent(
                        state(narrow), frame, {}, {}, {}, {}, {}, {},
                        onQuickAction = qa::onEvent,
                        quickActionsPanelState = { qa.uiState },
                        stageOverlay = { SideEffect { overlay++ } },
                    )
                }
            }
        })
        try {
            qa.toggleSheet()
            qa.onEvent(QuickActionEvent.SelectTab(SheetTab.Apps))
            driver.frames(60)
            assertEquals(apps, qa.uiState.apps.apps)
            val hostBefore = host
            val overlayBefore = overlay
            val before = driver.snapshot().hash()

            qa.onEvent(QuickActionEvent.Apps(AppsEvent.Search("set")))
            driver.frames(60)
            assertNotEquals(before, driver.snapshot().hash(), "the sheet shows the search")
            val searched = driver.snapshot().hash()

            iconGate.complete(Unit)
            driver.frames(60)
            assertTrue(qa.uiState.apps.icons.isNotEmpty(), "an icon arrived")
            assertNotEquals(searched, driver.snapshot().hash(), "the sheet shows the icon")

            assertEquals(hostBefore, host, "the window did not recompose")
            assertEquals(overlayBefore, overlay, "the stage did not recompose")
        } finally {
            driver.scene.close()
        }
    }
}
