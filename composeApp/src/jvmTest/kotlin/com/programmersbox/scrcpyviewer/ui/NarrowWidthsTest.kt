package com.programmersbox.scrcpyviewer.ui

import com.programmersbox.scrcpyviewer.ui.mirror.*
import com.programmersbox.scrcpyviewer.ui.mirror.files.*
import com.programmersbox.scrcpyviewer.ui.mirror.links.*
import com.programmersbox.scrcpyviewer.ui.mirror.logcat.*
import com.programmersbox.scrcpyviewer.ui.mirror.shots.gif.*
import com.programmersbox.scrcpyviewer.ui.devices.*
import com.programmersbox.scrcpyviewer.ui.presets.*
import com.programmersbox.scrcpyviewer.ui.actions.*
import com.programmersbox.scrcpyviewer.ui.settings.*
import com.programmersbox.scrcpyviewer.ui.navigation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OnWhen
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.ShellEvent
import com.programmersbox.scrcpyviewer.adb.ShellResult
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.apps.DeviceApp
import com.programmersbox.scrcpyviewer.files.DeviceEntry
import com.programmersbox.scrcpyviewer.files.DeviceFiles
import com.programmersbox.scrcpyviewer.files.DeviceSpace
import com.programmersbox.scrcpyviewer.files.JobState
import com.programmersbox.scrcpyviewer.files.Listing
import com.programmersbox.scrcpyviewer.files.SearchHit
import com.programmersbox.scrcpyviewer.files.TransferJob
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.TransferRequest
import com.programmersbox.scrcpyviewer.links.Extra
import com.programmersbox.scrcpyviewer.links.ExtraType
import com.programmersbox.scrcpyviewer.links.LinkStore
import com.programmersbox.scrcpyviewer.logcat.LogcatLine
import com.programmersbox.scrcpyviewer.logcat.LogcatSource
import com.programmersbox.scrcpyviewer.logcat.LogLevel
import com.programmersbox.scrcpyviewer.media.GifExporter
import com.programmersbox.scrcpyviewer.media.GifExportException
import com.programmersbox.scrcpyviewer.media.GifOptions
import com.programmersbox.scrcpyviewer.media.RecordingInfo
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionType
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.LaunchQuitAnimation
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.common.plainText
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.mirror.AppsUiState
import com.programmersbox.scrcpyviewer.ui.mirror.FileTransfers
import com.programmersbox.scrcpyviewer.ui.mirror.QuickActionsMemory
import com.programmersbox.scrcpyviewer.ui.mirror.shots.clipLength
import com.programmersbox.scrcpyviewer.ui.mirror.shots.gif.gifPreviewWidth
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.Rail
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.theme.AppTheme
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import com.programmersbox.scrcpyviewer.ui.tray.popup.PopupDevice
import com.programmersbox.scrcpyviewer.ui.tray.popup.PopupStatus
import com.programmersbox.scrcpyviewer.ui.tray.popup.TrayPopupContent
import com.programmersbox.scrcpyviewer.ui.tray.popup.TrayPopupUiState
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateDialog
import com.programmersbox.scrcpyviewer.ui.update.UpdatesContent
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap

/** Nov 8, 2:32 PM UTC. */
private val MATRIX_TIME = LocalDateTime.of(2026, 11, 8, 14, 32).toInstant(ZoneOffset.UTC).toEpochMilli()
private const val MATRIX_PHOTO = "IMG_20261108_143212.jpg"

/** The nav rail (60 dp) and its divider (1 dp) beside a screen hosted in App at a narrow window. */
private const val RAIL = 61

private class MatrixFiles : DeviceFiles(ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "serial"), {}) {
    val dirs = mutableMapOf<String, Listing>()
    override suspend fun list(dir: String): Listing = dirs[dir] ?: Listing.Failed("ls: $dir: No such file or directory")
    override suspend fun counts(dir: String, folders: List<String>): Map<String, Int> = folders.associateWith { 3 }
    override suspend fun freeSpace() = DeviceSpace(44_236_955_648, 122_880_000_000)
    override fun search(query: String, includeHidden: Boolean): Flow<List<SearchHit>> = flowOf(emptyList())
}

private class MatrixSource : LogcatSource(object : AdbClient(adbPath = { error("no adb") }) {}, "SER") {
    val streams = mutableListOf<Channel<LogcatLine>>()
    override fun lines(since: String?): Flow<LogcatLine> {
        val ch = Channel<LogcatLine>(Channel.UNLIMITED)
        streams += ch
        return ch.consumeAsFlow()
    }

    override suspend fun processes(): Map<Int, String> = mapOf(1234 to "com.example.notes")
}

@OptIn(ExperimentalComposeUiApi::class)
private class MatrixClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = null
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any get() = this
}

private class MatrixRunner : ShellRunner(object : AdbClient(adbPath = { error("no adb") }) {}, "s") {
    override suspend fun run(cmd: String, timeoutMs: Long) = ShellResult("Status: ok\nComplete", 0, 1)
}

/**
 * Every screen at four window widths (and the mirror sheet's tabs at four sheet widths), at rest: the screen's primary
 * action is displayed and inside the width, and no text longer than 3 characters is squeezed under 40 px (the
 * letter-per-line wrap). Window widths are the ones LocalWindowInfo reports; a screen hosted in App also gives up the
 * 60 dp rail and its 1 dp divider. Devices, Presets and Actions push at narrow widths, so an item is opened first; at
 * 1000 they show two panes. Settings keeps its sidebar at every width. Motion is passed explicitly: CI runners report
 * reduce motion on.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class NarrowWidthsTest {
    private val windows = listOf(360, 520, 700, 1000)

    // The semantics walk and the two checks.

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            // A cleared subtree (the list covered by a pushed detail) is out of the semantics tree.
            if (!node.config.isClearingSemantics) node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out.filter { it.layoutInfo.isPlaced }
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    private fun SemanticsNode.described(label: String) = config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    private fun SemanticsNode.unclipped() = Rect(positionInRoot, size.toSize())

    /** Displayed: a size, visible (not clipped away), and laid out inside 0..[width]. */
    private fun SemanticsNode.displayed(width: Int): Boolean {
        val laid = unclipped()
        return laid.width > 0f && laid.height > 0f && boundsInRoot.width > 0f && boundsInRoot.height > 0f &&
            laid.left >= -0.5f && laid.right <= width + 0.5f
    }

    private fun SceneDriver.text(label: String) = nodes().filter { label in it.texts() }
    private fun SceneDriver.described(label: String) = nodes().filter { it.described(label) }

    /** [label] is on screen as a text or as a description, inside 0..[width]. */
    private fun SceneDriver.assertPrimary(label: String, width: Int, where: String) {
        val found = (text(label) + described(label)).filter { it.displayed(width) }
        assertTrue(found.isNotEmpty(), "$where: \"$label\" is displayed inside $width (texts ${nodes().flatMap { it.texts() }.distinct().take(40)})")
    }

    /**
     * No Text longer than 3 characters squeezed into under 40 px: a text that wraps (or is cut) in that width breaks
     * mid-word, one letter a line. A short word laid out whole on one line (Save, Info) is not squeezed. Hidden
     * (zero-size) nodes are left aside.
     */
    private fun SceneDriver.assertNoShortTexts(where: String) {
        val squeezed = nodes().filter { node ->
            if (node.size.width !in 1..39 || node.size.height <= 0 || node.texts().none { it.length > 3 }) return@filter false
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            // No layout to read: a squeezed node cannot be told from a short word, so it counts as squeezed.
            layouts.isEmpty() || layouts.any { l -> l.lineCount > 1 || (0 until l.lineCount).any(l::isLineEllipsized) }
        }
        assertTrue(squeezed.isEmpty(), "$where: texts squeezed under 40 px: ${squeezed.map { it.texts() to it.size.width }}")
    }

    private fun SceneDriver.clickText(label: String, width: Int) {
        fun SemanticsNode.has(): Boolean = label in texts() || children.any { it.has() }
        val node = assertNotNull(nodes().lastOrNull { SemanticsActions.OnClick in it.config && it.displayed(width) && it.has() }, "no $label to click")
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(150)
    }

    private fun check(scene: SceneDriver, label: String, width: Int, where: String) {
        scene.assertPrimary(label, width, where)
        scene.assertNoShortTexts(where)
    }

    // Devices.

    private val pixel = Device("39121FDJH004AB", DeviceState.Device, "device", "Pixel_8_Pro", null, 1, false, DeviceInfo("Pixel 8 Pro", "15", 35))
    private val tablet = Device("R52W70ABCDE", DeviceState.Device, "device", "SM_X710", null, 2, false)
    private val unauthorized = Device("ZY22H8KLMN", DeviceState.Unauthorized, "unauthorized", null, null, null, false)

    private val devices = DevicesUiState(
        devices = listOf(pixel, tablet, unauthorized),
        selectedSerial = pixel.serial,
        visibleOptions = OptionCatalog.all,
        values = OptionValues(mapOf("max-size" to "1920", "no-audio" to null, "video-codec" to "h265")),
        presetLabel = "QA default (modified)",
        command = "scrcpy -s 39121FDJH004AB --max-size=1920",
        canMirror = true,
        adbConnected = true,
        hasBasePreset = true,
        isModified = true,
        advanced = false,
    )

    @Test
    fun `Devices - Mirror at every window width`() {
        for (window in windows) onEdt {
            val content = window - RAIL
            var state by mutableStateOf(devices)
            val d = SceneDriver(ImageComposeScene(window, 700, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Surface {
                            Box(Modifier.width(content.dp).fillMaxHeight()) {
                                val panes = rememberListDetailPanes()
                                DevicesContent(
                                    state, onSelect = { state = state.copy(selectedSerial = it) }, onOpen = {}, onQuery = {}, onGroup = {}, onToggle = { _, _ -> }, onValue = { _, _ -> },
                                    onSave = {}, onSaveAsNew = {}, onMirror = {}, panes = panes, motion = false,
                                )
                            }
                        }
                    }
                }
            })
            try {
                d.frames(150)
                if (window < 840) d.clickText("SM X710", content)
                check(d, "Mirror", content, "Devices at $window")
            } finally {
                d.scene.close()
            }
        }
    }

    // Presets.

    private val qa = Preset("qa", "QA default", "Sharp video, no audio.", OptionValues(mapOf("max-size" to "1920", "max-fps" to "60", "no-audio" to null, "show-touches" to null)))
    private val low = Preset("low", "Low bandwidth", options = OptionValues(mapOf("max-size" to "800", "no-audio" to null)))
    private val demo = Preset("demo", "Record demo", options = OptionValues(mapOf("record" to "demo.mp4")))

    @Test
    fun `Presets - Apply at every window width`() {
        for (window in windows) onEdt {
            val content = window - RAIL
            var state by mutableStateOf(PresetsUiState(presets = listOf(qa, low, demo), defaultId = "qa", selected = qa, selectedJson = "{}", canApply = true))
            val d = SceneDriver(ImageComposeScene(window, 800, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Surface {
                            Box(Modifier.width(content.dp).fillMaxHeight()) {
                                val panes = rememberListDetailPanes()
                                PresetsContent(
                                    state, onSelect = { id -> state = state.copy(selected = state.presets.first { it.id == id }) }, onNew = { qa }, onEdit = { _, _, _, _ -> }, onDuplicate = {},
                                    onDelete = {}, onSetDefault = {}, onApply = {}, onExport = {}, onExportAll = {}, onImportFile = {}, onImportPaste = {},
                                    panes = panes,
                                )
                            }
                        }
                    }
                }
            })
            try {
                d.frames(150)
                if (window < 840) d.clickText("Low bandwidth", content)
                check(d, "Apply", content, "Presets at $window")
            } finally {
                d.scene.close()
            }
        }
    }

    // Actions.

    private val awake = ToggleAction(
        "awake", "Stay awake", icon = "battery_full", on = "svc power stayon true", off = "svc power stayon false",
        read = "settings get global stay_on_while_plugged_in", onWhen = OnWhen(MatchMode.Equals, "7"),
    )
    private val logcatAction = RunOnceAction("logcat", "Logcat errors", icon = "terminal", command = "logcat *:E")

    @Test
    fun `Actions - Save at every window width`() {
        for (window in windows) onEdt {
            val content = window - RAIL
            var state by mutableStateOf(
                ActionsUiState(
                    actions = listOf(awake, logcatAction), selectedId = "awake",
                    draft = ActionDraft(
                        id = "awake", type = ActionType.Toggle, name = "Stay awake", icon = "battery_full",
                        on = awake.on, off = awake.off, read = awake.read.orEmpty(), matchValue = "7",
                    ),
                    editorKey = 1, devices = listOf("R5CT123ABC"), testDevice = "R5CT123ABC",
                ),
            )
            val d = SceneDriver(ImageComposeScene(window, 900, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Surface {
                            Box(Modifier.width(content.dp).fillMaxHeight()) {
                                val panes = rememberListDetailPanes()
                                ActionsContent(
                                    state,
                                    onEvent = { event ->
                                        if (event is ActionsEvent.Select) {
                                            val action = state.actions.first { it.id == event.id }
                                            state = state.copy(
                                                selectedId = action.id,
                                                draft = ActionDraft(id = action.id, type = ActionType.RunOnce, name = action.name, icon = "terminal"),
                                                editorKey = state.editorKey + 1,
                                            )
                                        }
                                    },
                                    panes = panes,
                                )
                            }
                        }
                    }
                }
            })
            try {
                d.frames(150)
                if (window < 840) d.clickText("Logcat errors", content)
                check(d, "Save", content, "Actions at $window")
            } finally {
                d.scene.close()
            }
        }
    }

    // Settings: the sidebar is always there, so Appearance's Theme control shows beside it at every width.

    @Test
    fun `Settings - the Theme control at every window width`() {
        for (window in windows) onEdt {
            val content = window - RAIL
            var nav by mutableStateOf(SettingsNav(selected = SettingsCategoryId.Appearance))
            val info = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize get() = IntSize(window, 800)
            }
            val actions = SettingsActions(onSelectCategory = { nav = nav.select(it) }, onQuery = { nav = nav.withQuery(it) })
            val d = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides true, LocalWindowInfo provides info) {
                        Surface {
                            Box(Modifier.width(content.dp).height(800.dp)) {
                                SettingsContent(SettingsUiState(settings = Settings()), UpdateUiState(), nav, actions)
                            }
                        }
                    }
                }
            })
            try {
                d.frames(150)
                val sidebar = assertNotNull(d.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == SettingsSidebarTag }, "the sidebar at $window")
                assertTrue(sidebar.unclipped().width >= 64f, "sidebar at $window: ${sidebar.unclipped()}")
                check(d, "Theme", content, "Settings at $window")
            } finally {
                d.scene.close()
            }
        }
    }

    // Settings › Privacy: the switches, the recent events (open) and the ID with its Reset ID button.

    @Test
    fun `Settings - Privacy's Reset ID at every window width`() {
        val recent = listOf(
            com.programmersbox.scrcpyviewer.telemetry.RecentEvent("mirror_started", java.time.Instant.now(), sent = true),
            com.programmersbox.scrcpyviewer.telemetry.RecentEvent("screenshot_taken", java.time.Instant.now(), sent = false),
        )
        val state = SettingsUiState(settings = Settings(), recent = recent, installId = "01234567-89ab-cdef-0123-456789abcdef")
        for (window in windows) onEdt {
            val content = window - RAIL
            val info = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize get() = IntSize(window, 800)
            }
            val d = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides true, LocalWindowInfo provides info) {
                        Surface {
                            Box(Modifier.width(content.dp).height(800.dp)) {
                                SettingsContent(state, UpdateUiState(), SettingsNav(selected = SettingsCategoryId.Privacy), SettingsActions())
                            }
                        }
                    }
                }
            })
            try {
                d.frames(150)
                d.clickText("Show", content)
                check(d, "Reset ID", content, "Privacy at $window")
                d.assertPrimary("not sent", content, "Privacy at $window")
            } finally {
                d.scene.close()
            }
        }
    }

    // The nav rail.

    private fun railTexts(window: Int): List<String> {
        var out = emptyList<String>()
        onEdt {
            val d = SceneDriver(ImageComposeScene(window, 600, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Rail(TopLevelNavigator(TopLevelRoute.Devices), settingsBadge = false, windowWidth = window.dp)
                    }
                }
            })
            try {
                d.frames(60)
                out = d.nodes().filter { it.texts().isNotEmpty() && it.size.width > 0 }.flatMap { it.texts() }
            } finally {
                d.scene.close()
            }
        }
        return out
    }

    @Test
    fun `the rail shows its text labels in a wide window and none in a narrow one`() {
        val labels = listOf("Devices", "Presets", "Actions", "Settings")
        val wide = railTexts(1000)
        assertTrue(labels.all { it in wide }, "wide: $labels in $wide")
        val narrow = railTexts(360)
        assertTrue(labels.none { it in narrow }, "narrow: none of $labels in $narrow")
    }

    // The mirror sheet's tabs.

    @Test
    fun `Data - Write to device at every sheet width`() {
        for (width in listOf(360, 520, QUICK_ACTIONS_DATABASE_WIDTH.value.toInt(), 700)) onEdt {
            val (controller, _) = com.programmersbox.scrcpyviewer.ui.mirror.database.notesController()
            controller.pick(com.programmersbox.scrcpyviewer.ui.mirror.database.DEBUG_APP)
            controller.setCell(0, 1, com.programmersbox.scrcpyviewer.database.SqlValue.Text("Groceries"))
            val d = sheet(width) {
                com.programmersbox.scrcpyviewer.ui.mirror.database.DatabaseTab(controller, motion = false, onShowDebuggableApps = {})
            }
            try {
                check(d, "Write to device", width, "Data at $width")
            } finally {
                d.scene.close()
                controller.close()
            }
        }
    }

    private fun sheet(width: Int, height: Int = 700, content: @Composable () -> Unit): SceneDriver = SceneDriver(
        ImageComposeScene(700, height, Density(1f)) {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalClipboard provides MatrixClipboard()) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                            Box(Modifier.width(width.dp).height(height.dp)) { content() }
                        }
                    }
                }
            }
        },
    ).also { it.frames(40) }

    @Test
    fun `Apps - Reload at every sheet width`() {
        val apps = listOf(DeviceApp("com.android.chrome", "Chrome", false, "131", 677), DeviceApp("com.android.settings", "Settings", true, "14", 34))
        for (width in listOf(280, 340, 400, QUICK_ACTIONS_APPS_WIDTH.value.toInt())) onEdt {
            val d = sheet(width) { AppsTabContent(AppsUiState(apps = apps), enabled = true, onEvent = {}) }
            try {
                check(d, "Reload app list", width, "Apps at $width")
            } finally {
                d.scene.close()
            }
        }
    }

    @Test
    fun `Shell - Run at every sheet width`() {
        for (width in listOf(280, 340, 400, QUICK_ACTIONS_SHELL_WIDTH.value.toInt()).distinct()) onEdt {
            val console = ShellConsole(MatrixRunner(), ShellHistory(), CoroutineScope(Dispatchers.Unconfined), {})
            val d = sheet(width) { ShellTabContent(console, emptyList()) {} }
            try {
                check(d, "Run", width, "Shell at $width")
            } finally {
                d.scene.close()
            }
        }
    }

    @Test
    fun `Files - Pull with a selection at every sheet width`() {
        val files = MatrixFiles().apply {
            dirs["/sdcard"] = Listing.Entries(listOf(DeviceEntry("Download", true, 4096, MATRIX_TIME)))
            dirs["/sdcard/Download"] = Listing.Entries(
                listOf(DeviceEntry(MATRIX_PHOTO, false, 4_300_000, MATRIX_TIME), DeviceEntry("notes.txt", false, 5_400, MATRIX_TIME)),
            )
        }
        for (width in listOf(280, 340, 400, QUICK_ACTIONS_FILES_WIDTH.value.toInt())) onEdt {
            val browser = FilesBrowser(
                "SER", files, FileTransfers(queue = null, pushTarget = "/sdcard/Download/") {}, QuickActionsMemory(),
                AppSettings(Files.createTempDirectory("matrix-files").resolve("settings.json")), "/sdcard/Download",
                CoroutineScope(Dispatchers.Unconfined), {}, enqueue = { emptyList() }, jobs = emptyFlow(),
            )
            val d = sheet(width) { FilesTab(browser, zone = ZoneOffset.UTC, locale = Locale.US, motion = false, isMac = true) }
            try {
                browser.click("notes.txt")
                browser.toggle(MATRIX_PHOTO)
                d.frames(40)
                check(d, "Pull", width, "Files at $width")
            } finally {
                d.scene.close()
            }
        }
    }

    @Test
    fun `Logcat - Pause at every sheet width`() {
        for (width in listOf(280, 340, 420, 600)) onEdt {
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(StandardTestDispatcher(scheduler))
            val source = MatrixSource()
            val controller = LogcatController(source, scope, {}, io = EmptyCoroutineContext)
            val d = sheet(width, 600) { LogcatTab(controller, width.dp, motion = false) }
            try {
                controller.play()
                repeat(3) {
                    scheduler.advanceTimeBy(100)
                    scheduler.runCurrent()
                }
                d.frames(30)
                source.streams.last().trySend(LogcatLine("11-13", "10:00:01.234", 1234, 1250, LogLevel.Warn, "OkHttp", "Sync request failed, will retry"))
                repeat(3) {
                    scheduler.advanceTimeBy(100)
                    scheduler.runCurrent()
                }
                d.frames(60)
                check(d, "Pause logcat", width, "Logcat at $width")
            } finally {
                d.scene.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `Links - Open on device at every sheet width`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        for (width in listOf(280, 340, 420, 600)) onEdt {
            val links = LinksState(
                "d1", MatrixRunner(), LinkStore(Files.createTempDirectory("links-matrix").resolve("links.json")), { emptyList() }, scope, clock = { 1L },
            ).also {
                it.onEvent(LinksEvent.ToggleDetails)
                it.onEvent(LinksEvent.SetExtra(Extra(ExtraType.String, "source", "qa")))
            }
            val d = SceneDriver(ImageComposeScene(700, 1200, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxHeight()) {
                        CompositionLocalProvider(LocalReduceMotion provides true) {
                            Box(Modifier.width(width.dp).fillMaxHeight()) {
                                LinksTabContent(links, AppsUiState(apps = emptyList()), enabled = true, onEvent = links::onEvent, motion = false, now = { 1L })
                            }
                        }
                    }
                }
            }).also { it.frames(30) }
            try {
                check(d, "Open on device", width, "Links at $width")
            } finally {
                d.scene.close()
            }
        }
    }

    @Test
    fun `GIF export - Export is inside a 600 by 500 window with a landscape clip`() = onEdt {
        val tmp = Files.createTempDirectory("gif-matrix")
        val state = GifExportState(tmp.resolve("clip.mp4"), RecordingInfo(18_000, 2340, 1080))
        val d = SceneDriver(ImageComposeScene(600, 500, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize()) {
                        GifExportContent(
                            state, false, FakeExport().fn, FakeFrames(), onClose = {}, onReveal = {}, say = {},
                            modifier = Modifier.fillMaxSize(), decode = EmptyCoroutineContext,
                        )
                    }
                }
            }
        }).also { it.frames(10) }
        try {
            check(d, "Export", 600, "GIF export")
        } finally {
            d.scene.close()
        }
    }

    // The menu bar popup.

    private val popupDevices = listOf(
        PopupDevice("PIX1", "Pixel 8 Pro", PopupStatus.Mirroring, "device"),
        PopupDevice("TAB2", "Galaxy Tab", PopupStatus.Ready, "device"),
        PopupDevice("LOCK3", "Locked phone", PopupStatus.Unauthorized, "unauthorized"),
    )

    @Test
    fun `Tray popup - Bring to front at its width`() = onEdt {
        val d = sheet(360, 900) {
            TrayPopupContent(TrayPopupUiState(popupDevices, "PIX1"), sendOpen = false, onEvent = {}, modifier = Modifier.width(360.dp))
        }
        try {
            check(d, "Bring to front", 360, "Tray popup")
        } finally {
            d.scene.close()
        }
    }

    @Test
    fun `each theme style renders the stage`() {
        for (style in ThemeStyle.entries) onEdt {
            val d = SceneDriver(ImageComposeScene(400, 900, Density(1f)) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    ScrcpyViewerTheme(AppTheme(mode = ThemeMode.Light, style = style)) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            TrayPopupContent(TrayPopupUiState(popupDevices, "PIX1"), sendOpen = false, onEvent = {}, modifier = Modifier.width(360.dp))
                        }
                    }
                }
            }).also { it.frames(40) }
            try {
                check(d, "Bring to front", 400, "Tray popup ${style.name}")
            } finally {
                d.scene.close()
            }
        }
    }
}
