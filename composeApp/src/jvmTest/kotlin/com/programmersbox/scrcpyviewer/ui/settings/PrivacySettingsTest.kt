package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.actions.AiSkillInstaller
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.telemetry.RecentEvent
import com.programmersbox.scrcpyviewer.telemetry.Telemetry
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Adb
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Ai
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Appearance
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Help
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Mirroring
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Privacy
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Updates
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Settings › Privacy: the consent switches, the recent-events list and the anonymous ID. */
class PrivacySettingsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("privacy-settings")
    private val settings = AppSettings(dir.resolve("settings.json"))

    /** An in-memory install ID and a settable recent list. */
    private class IdTelemetry : Telemetry by Telemetry.Noop {
        var id = UUID.randomUUID().toString()
        override val enabled = true
        override val recent: MutableStateFlow<List<RecentEvent>> = MutableStateFlow(emptyList())
        override fun installId() = id
        override fun resetInstallId() {
            id = UUID.randomUUID().toString()
        }
    }

    private val telemetry = IdTelemetry()

    private fun vm() = SettingsViewModel(
        settings, PresetStore(dir.resolve("presets.json"), scope),
        object : SettingsAdb {
            override fun bundledAdb(): Path = dir.resolve("adb")
            override suspend fun version(path: Path): String? = null
            override fun restartTracker() = Unit
        },
        AiSkillInstaller(dir.resolve("home"), dir.resolve("inbox"), dir.resolve("actions.json")), telemetry, scope,
    )

    private fun <T> StateFlow<T>.await(until: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { first(until) } }

    @Test
    fun privacyCategorySitsBetweenUpdatesAndHelp() =
        assertEquals(listOf(Appearance, Mirroring, Adb, Ai, Updates, Privacy, Help), settingsCategories.map { it.id })

    @Test
    fun usageSwitchPersists() {
        val vm = vm()
        assertFalse(settings.settings.value.usageStats)
        vm.setUsageStats(true)
        assertTrue(settings.settings.value.usageStats)
        vm.setCrashReports(false)
        assertFalse(settings.settings.value.crashReports)
    }

    @Test
    fun resetChangesInstallId() {
        val vm = vm()
        val before = vm.state.await { it.installId.isNotEmpty() }.installId
        assertEquals(telemetry.id, before)
        vm.resetInstallId()
        val after = vm.state.await { it.installId != before }.installId
        assertNotEquals(before, after)
        assertEquals(telemetry.id, after)
    }

    @Test
    fun stateCarriesEnabledAndRecent() {
        val vm = vm()
        val event = RecentEvent("app_opened", Instant.now(), sent = true)
        telemetry.recent.value = listOf(event)
        val state = vm.state.await { it.recent.isNotEmpty() }
        assertEquals(listOf(event), state.recent)
        assertTrue(state.telemetryEnabled)
    }

    @Test
    fun searchFindsPrivacyByAnalytics() {
        for (q in listOf("analytics", "telemetry", "crash", "privacy", "statistics", "posthog")) {
            val privacy = searchSettings(settingsCategories, q).firstOrNull { it.category.id == Privacy }
            assertNotNull(privacy, q)
        }
        val hit = searchSettings(settingsCategories, "analytics").single { it.category.id == Privacy }
        assertTrue("privacy-usage" in hit.entries.map { it.id })
        assertEquals(
            listOf("privacy-usage", "privacy-crash", "privacy-recent", "privacy-id"),
            settingsCategories.single { it.id == Privacy }.entries.map { it.id },
        )
    }

    @Test
    fun shortIdKeepsTheEnds() {
        assertEquals("01234567…cdef", shortInstallId("01234567-89ab-cdef-0123-456789abcdef"))
        assertEquals("short", shortInstallId("short"))
    }

    // Scenes.

    private fun scene(state: SettingsUiState, actions: SettingsActions = SettingsActions(), block: SceneDriver.() -> Unit) = onEdt {
        val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Surface { SettingsContent(state, UpdateUiState(), SettingsNav(selected = Privacy), actions) }
                }
            }
        })
        try {
            driver.frames(20)
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
    private fun SemanticsNode.has(label: String): Boolean = label in texts() || children.any { it.has(label) }
    private fun SceneDriver.text(label: String) = nodes().filter { label in it.texts() }

    /** The toggleable row holding [label]. */
    private fun SceneDriver.toggle(label: String) =
        assertNotNull(nodes().lastOrNull { SemanticsProperties.ToggleableState in it.config && it.has(label) }, "the $label row")

    private fun SceneDriver.click(label: String) {
        val node = assertNotNull(nodes().lastOrNull { SemanticsActions.OnClick in it.config && it.has(label) }, "no $label to click")
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(60)
    }

    @Test
    fun disabledBuildShowsBannerAndDisabledSwitches() = scene(SettingsUiState(telemetryEnabled = false)) {
        assertTrue(text(KEYLESS_BANNER).isNotEmpty(), "the banner")
        for (label in listOf("Share usage statistics", "Send crash reports")) {
            assertTrue(SemanticsProperties.Disabled in toggle(label).config, "$label is disabled")
        }
    }

    @Test
    fun enabledBuildHasNoBannerAndTogglesCallBack() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val actions = SettingsActions(onUsageStats = { calls += "usage" to it }, onCrashReports = { calls += "crash" to it })
        scene(SettingsUiState(telemetryEnabled = true), actions) {
            assertTrue(text(KEYLESS_BANNER).isEmpty(), "no banner")
            assertEquals(ToggleableState.Off, toggle("Share usage statistics").config[SemanticsProperties.ToggleableState])
            assertEquals(ToggleableState.On, toggle("Send crash reports").config[SemanticsProperties.ToggleableState])
            click("Share usage statistics")
            click("Send crash reports")
            assertEquals(listOf("usage" to true, "crash" to false), calls)
        }
    }

    @Test
    fun recentListOpensWithTimesAndNotSent() {
        val at = Instant.parse("2026-11-17T10:15:00Z")
        val recent = listOf(RecentEvent("app_opened", at, sent = true), RecentEvent("mirror_started", at, sent = false))
        scene(SettingsUiState(telemetryEnabled = true, recent = recent)) {
            assertTrue(text("app_opened").isEmpty(), "folded at first")
            click("Show")
            assertTrue(text("app_opened").isNotEmpty())
            assertTrue(text("mirror_started").isNotEmpty())
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(at)
            assertEquals(1, text(time).size, "one sent row shows its time")
            assertEquals(1, text("not sent").size, "the blocked row")
        }
    }

    /** The banner's height mid-way, then settled, after the build turns keyless; [reduceMotion] passed explicitly. */
    private fun bannerHeights(reduceMotion: Boolean): Triple<Float, Float, Float> {
        var heights = Triple(0f, 0f, 0f)
        onEdt {
            var state by mutableStateOf(SettingsUiState(telemetryEnabled = true))
            val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                        Surface { SettingsContent(state, UpdateUiState(), SettingsNav(selected = Privacy), SettingsActions()) }
                    }
                }
            })
            // The space the banner takes: how far it has pushed the first row down.
            val top = mutableListOf<Float>()
            fun height(): Float {
                val y = driver.text("Share usage statistics").first().positionInRoot.y
                if (top.isEmpty()) top += y
                return y - top.first()
            }
            try {
                driver.frames(20)
                val before = height()
                state = SettingsUiState(telemetryEnabled = false)
                driver.frames(4)
                val mid = height()
                driver.frames(100)
                heights = Triple(before, mid, height())
            } finally {
                driver.scene.close()
            }
        }
        return heights
    }

    @Test
    fun bannerUnfoldsWhenTheBuildTurnsKeyless() {
        val (before, mid, after) = bannerHeights(reduceMotion = false)
        assertEquals(0f, before)
        assertTrue(after > 0f)
        assertTrue(mid > 0f && mid < after, "mid-unfold $mid between 0 and $after")
    }

    @Test
    fun underReduceMotionTheBannerOnlyFades() {
        val (_, mid, after) = bannerHeights(reduceMotion = true)
        assertTrue(after > 0f)
        assertEquals(after, mid, "full height at once")
    }

    private val older = RecentEvent("app_opened", Instant.parse("2026-11-17T10:00:00Z"), sent = true)
    private val oldest = RecentEvent("mirror_started", Instant.parse("2026-11-17T09:00:00Z"), sent = false)
    private val newer = RecentEvent("screenshot_taken", Instant.parse("2026-11-17T10:30:00Z"), sent = true)

    /** The open list showing [older] and [oldest]; [change] then sets the events while [block] reads frames. */
    private fun openList(reduceMotion: Boolean, block: SceneDriver.((List<RecentEvent>) -> Unit) -> Unit) = onEdt {
        var recent by mutableStateOf(listOf(older, oldest))
        val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                    Surface { SettingsContent(SettingsUiState(recent = recent), UpdateUiState(), SettingsNav(selected = Privacy), SettingsActions()) }
                }
            }
        })
        try {
            driver.frames(20)
            driver.click("Show")
            driver.frames(60)
            driver.block { recent = it }
        } finally {
            driver.scene.close()
        }
    }

    private fun SceneDriver.y(label: String) = text(label).single().positionInRoot.y

    @Test
    fun aNewEventOpensItsRowAndPushesTheOthersDown() = openList(reduceMotion = false) { set ->
        val before = y("app_opened")
        set(listOf(newer, older, oldest))
        frames(4)
        val mid = y("app_opened")
        frames(100)
        val after = y("app_opened")
        assertTrue(after > before, "pushed down: $before -> $after")
        assertTrue(mid > before && mid < after, "mid-push $mid strictly between $before and $after")
        assertTrue(text("screenshot_taken").isNotEmpty())
    }

    @Test
    fun underReduceMotionTheOthersJumpDown() = openList(reduceMotion = true) { set ->
        set(listOf(newer, older, oldest))
        frames(2)
        val mid = y("app_opened")
        frames(100)
        assertEquals(y("app_opened"), mid)
    }

    @Test
    fun aDroppedEventShrinksAway() = openList(reduceMotion = false) { set ->
        set(listOf(older))
        frames(4)
        assertTrue(text("mirror_started").isNotEmpty(), "still leaving")
        frames(100)
        assertTrue(text("mirror_started").isEmpty(), "gone")
        assertTrue(text("app_opened").isNotEmpty())
    }

    @Test
    fun leavingRowsKeepTheirPlace() {
        assertEquals(listOf("n", "a", "b", "c"), withLeaving(listOf("a", "b", "c"), listOf("n", "a", "b")))
        assertEquals(listOf("a", "b", "c"), withLeaving(listOf("a", "b", "c"), listOf("a", "c")))
        assertEquals(listOf("x", "a"), withLeaving(listOf("x"), listOf("a")))
    }

    @Test
    fun idRowShowsShortIdAndResets() {
        var resets = 0
        val id = "01234567-89ab-cdef-0123-456789abcdef"
        scene(SettingsUiState(telemetryEnabled = true, installId = id), SettingsActions(onResetInstallId = { resets++ })) {
            assertTrue(text("01234567…cdef").isNotEmpty(), "the short ID")
            click("Reset ID")
            assertEquals(1, resets)
        }
    }
}
