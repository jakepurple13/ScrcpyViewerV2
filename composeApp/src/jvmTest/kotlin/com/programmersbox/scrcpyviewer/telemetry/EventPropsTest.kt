package com.programmersbox.scrcpyviewer.telemetry

import com.programmersbox.scrcpyviewer.presets.ConflictChoice
import com.programmersbox.scrcpyviewer.ui.mirror.QuickToggle
import com.programmersbox.scrcpyviewer.ui.mirror.SheetTab
import com.programmersbox.scrcpyviewer.ui.mirror.arrange.ArrangeLayout
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.ThemePalette
import com.programmersbox.scrcpyviewer.ui.theme.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every event's property names, and that every label it sends is lowercase snake_case, whatever the caller passed. */
class EventPropsTest {
    /** Each event built the way its call site builds it, from enum names where the call site uses them. */
    private val events: List<Pair<TelemetryEvent, Set<String>>> = listOf(
        TelemetryEvent.AppOpened(true, ThemeStyle.Material.name) to setOf("first_launch", "theme_style"),
        TelemetryEvent.AppClosed(42, 2) to setOf("session_minutes", "mirror_windows_open"),
        TelemetryEvent.OnboardingFinished("finished", 3) to setOf("outcome", "last_step"),
        TelemetryEvent.UpdateInstalled("1.25.0", "1.26.1") to setOf("from_version", "to_version"),
        TelemetryEvent.DeviceConnected("usb", 14, 1) to setOf("connection", "android_major", "devices_connected"),
        TelemetryEvent.WifiPairing("qr", true) to setOf("method", "success"),
        TelemetryEvent.EmulatorStarted(true, true, 45) to setOf("cold_boot", "success", "boot_seconds"),
        TelemetryEvent.AdbRestarted(1) to setOf("mirrors_open"),
        TelemetryEvent.MirrorStarted("wifi", true, false, 3, true) to
            setOf("connection", "used_preset", "advanced_options", "option_count", "audio"),
        TelemetryEvent.MirrorEnded(0, "closed") to setOf("minutes", "reason"),
        TelemetryEvent.MirrorFailed("Push", "MirrorStageException") to setOf("stage", "error_class"),
        TelemetryEvent.FirstFrameTime(20_000, "emulator") to setOf("ms", "connection"),
        TelemetryEvent.WindowsArranged(ArrangeLayout.SideBySide.name, 2, true) to setOf("layout", "window_count", "keep_tiled"),
        TelemetryEvent.InputBroadcastStarted(2) to setOf("follower_count"),
        TelemetryEvent.ScreenshotTaken("thumbnail") to setOf("after"),
        TelemetryEvent.RecordingStopped(300) to setOf("minutes"),
        TelemetryEvent.SheetTabOpened(SheetTab.Logcat.name) to setOf("tab"),
        TelemetryEvent.QuickActionRun(QuickToggle.DarkMode.name) to setOf("action"),
        TelemetryEvent.FileTransfer("pull", 500, "done") to setOf("direction", "size", "outcome"),
        TelemetryEvent.PresetSaved(4) to setOf("total_presets"),
        TelemetryEvent.PresetsImported(2, ConflictChoice.KeepBoth.name) to setOf("count", "conflict_choice"),
        TelemetryEvent.ThemeChanged(ThemeStyle.Retro.name, ThemePalette.Teal.name, ThemeMode.Dark.name, false) to
            setOf("style", "palette", "mode", "neon"),
        TelemetryEvent.SettingsOpened(SettingsCategoryId.Privacy.name) to setOf("category"),
    )

    /** error_class is a Java class's simple name, sent as it is so it matches the stack trace. */
    private val label = Regex("^[a-z0-9_.<>=-]*$")

    @Test fun everyEventIsCovered() {
        assertEquals(
            TelemetryEvent::class.java.permittedSubclasses.map { it.simpleName }.toSet(),
            events.map { it.first.javaClass.simpleName }.toSet(),
        )
    }

    @Test fun propertyNamesArePinned() {
        for ((event, keys) in events) assertEquals(keys, event.props().keys, event.name)
    }

    @Test fun everyStringValueIsLowercaseSnakeCase() {
        for ((event, _) in events) {
            for ((key, value) in event.props()) {
                if (value !is String || key == "error_class") continue
                assertTrue(label.matches(value), "${event.name}.$key = $value")
            }
        }
    }

    @Test fun enumNamesBecomeSnakeCase() {
        assertEquals("dark_mode", TelemetryEvent.QuickActionRun(QuickToggle.DarkMode.name).props()["action"])
        assertEquals("keep_both", TelemetryEvent.PresetsImported(1, ConflictChoice.KeepBoth.name).props()["conflict_choice"])
        assertEquals("side_by_side", TelemetryEvent.WindowsArranged(ArrangeLayout.SideBySide.name, 1, false).props()["layout"])
        assertEquals("as_placed", TelemetryEvent.WindowsArranged(ArrangeLayout.AsPlaced.name, 1, false).props()["layout"])
        assertEquals("material", TelemetryEvent.AppOpened(false, ThemeStyle.Material.name).props()["theme_style"])
        assertEquals("adb", TelemetryEvent.SettingsOpened(SettingsCategoryId.Adb.name).props()["category"])
        assertEquals("ai", TelemetryEvent.SettingsOpened(SettingsCategoryId.Ai.name).props()["category"])
        assertEquals("privacy", TelemetryEvent.SettingsOpened(SettingsCategoryId.Privacy.name).props()["category"])
        assertEquals("push", TelemetryEvent.MirrorFailed("Push", "IOException").props()["stage"])
        assertEquals("IOException", TelemetryEvent.MirrorFailed("Push", "IOException").props()["error_class"])
    }

    @Test fun snakeCaseRules() {
        assertEquals("dark_mode", "DarkMode".snakeCase())
        assertEquals("wi_fi", "WiFi".snakeCase())
        assertEquals("adb", "ADB".snakeCase())
        assertEquals("http_server", "HTTPServer".snakeCase())
        assertEquals("keep_both", "keep_both".snakeCase())
        assertEquals("developer_options", "developer_options".snakeCase())
        assertEquals("v2_beta", "V2Beta".snakeCase())
    }
}
