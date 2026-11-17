package com.programmersbox.scrcpyviewer.telemetry

/**
 * Every analytics event, one subclass per event. Call sites build one of these, so event names and property names
 * cannot be free-form strings. Property values are `String`, `Number`, `Boolean` or `List<String>`. Durations and
 * sizes are taken raw and bucketed inside [props], so a call site cannot forget to bucket. Labels (stages, tabs,
 * layouts, enum names) go through [snakeCase] inside [props] too, so they are sent lowercase snake_case however the
 * call site spelled them: `ArrangeLayout.SideBySide.name` is sent as `side_by_side`. Versions and `error_class` (a
 * Java class name) are sent as they are.
 */
sealed class TelemetryEvent(val name: String) {
    abstract fun props(): Map<String, Any>

    data class AppOpened(val firstLaunch: Boolean, val themeStyle: String) : TelemetryEvent("app_opened") {
        override fun props() = mapOf("first_launch" to firstLaunch, "theme_style" to themeStyle.snakeCase())
    }

    data class AppClosed(val sessionMinutes: Long, val mirrorWindowsOpen: Int) : TelemetryEvent("app_closed") {
        override fun props() = mapOf(
            "session_minutes" to bucket(sessionMinutes, MINUTE_EDGES),
            "mirror_windows_open" to mirrorWindowsOpen,
        )
    }

    /** [outcome]: finished or skipped. */
    data class OnboardingFinished(val outcome: String, val lastStep: Int) : TelemetryEvent("onboarding_finished") {
        override fun props() = mapOf("outcome" to outcome.snakeCase(), "last_step" to lastStep)
    }

    data class UpdateInstalled(val fromVersion: String, val toVersion: String) : TelemetryEvent("update_installed") {
        override fun props() = mapOf("from_version" to fromVersion, "to_version" to toVersion)
    }

    /** [connection]: usb, wifi or emulator. */
    data class DeviceConnected(
        val connection: String,
        val androidMajor: Int,
        val devicesConnected: Int,
    ) : TelemetryEvent("device_connected") {
        override fun props() = mapOf(
            "connection" to connection.snakeCase(),
            "android_major" to androidMajor,
            "devices_connected" to devicesConnected,
        )
    }

    /** [method]: qr, code, ip or scan. */
    data class WifiPairing(val method: String, val success: Boolean) : TelemetryEvent("wifi_pairing") {
        override fun props() = mapOf("method" to method.snakeCase(), "success" to success)
    }

    data class EmulatorStarted(
        val coldBoot: Boolean,
        val success: Boolean,
        val bootSeconds: Long,
    ) : TelemetryEvent("emulator_started") {
        override fun props() = mapOf(
            "cold_boot" to coldBoot,
            "success" to success,
            "boot_seconds" to bucket(bootSeconds, SECOND_EDGES),
        )
    }

    data class AdbRestarted(val mirrorsOpen: Int) : TelemetryEvent("adb_restarted") {
        override fun props() = mapOf("mirrors_open" to mirrorsOpen)
    }

    data class MirrorStarted(
        val connection: String,
        val usedPreset: Boolean,
        val advancedOptions: Boolean,
        val optionCount: Int,
        val audio: Boolean,
    ) : TelemetryEvent("mirror_started") {
        override fun props() = mapOf(
            "connection" to connection.snakeCase(),
            "used_preset" to usedPreset,
            "advanced_options" to advancedOptions,
            "option_count" to optionCount,
            "audio" to audio,
        )
    }

    /** [reason]: closed, disconnected or error. */
    data class MirrorEnded(val minutes: Long, val reason: String) : TelemetryEvent("mirror_ended") {
        override fun props() = mapOf("minutes" to bucket(minutes, MINUTE_EDGES), "reason" to reason.snakeCase())
    }

    /** [stage]: push, server or decode. */
    data class MirrorFailed(val stage: String, val errorClass: String) : TelemetryEvent("mirror_failed") {
        override fun props() = mapOf("stage" to stage.snakeCase(), "error_class" to errorClass)
    }

    data class FirstFrameTime(val ms: Long, val connection: String) : TelemetryEvent("first_frame_time") {
        override fun props() = mapOf("ms" to bucket(ms, MS_EDGES), "connection" to connection.snakeCase())
    }

    data class WindowsArranged(
        val layout: String,
        val windowCount: Int,
        val keepTiled: Boolean,
    ) : TelemetryEvent("windows_arranged") {
        override fun props() = mapOf("layout" to layout.snakeCase(), "window_count" to windowCount, "keep_tiled" to keepTiled)
    }

    data class InputBroadcastStarted(val followerCount: Int) : TelemetryEvent("input_broadcast_started") {
        override fun props() = mapOf("follower_count" to followerCount)
    }

    /** [after]: thumbnail, copy or markup. */
    data class ScreenshotTaken(val after: String) : TelemetryEvent("screenshot_taken") {
        override fun props() = mapOf("after" to after.snakeCase())
    }

    data class RecordingStopped(val minutes: Long) : TelemetryEvent("recording_stopped") {
        override fun props() = mapOf("minutes" to bucket(minutes, MINUTE_EDGES))
    }

    /** [tab]: actions, apps, shell, files, logcat or links. */
    data class SheetTabOpened(val tab: String) : TelemetryEvent("sheet_tab_opened") {
        override fun props() = mapOf("tab" to tab.snakeCase())
    }

    /** [action]: a built-in id, or `custom`. */
    data class QuickActionRun(val action: String) : TelemetryEvent("quick_action_run") {
        override fun props() = mapOf("action" to action.snakeCase())
    }

    /** [direction]: pull or push. [outcome]: done, cancelled or failed. [sizeBytes] is bucketed. */
    data class FileTransfer(
        val direction: String,
        val sizeBytes: Long,
        val outcome: String,
    ) : TelemetryEvent("file_transfer") {
        override fun props() = mapOf(
            "direction" to direction.snakeCase(),
            "size" to bucket(sizeBytes, BYTE_EDGES),
            "outcome" to outcome.snakeCase(),
        )
    }

    data class PresetSaved(val totalPresets: Int) : TelemetryEvent("preset_saved") {
        override fun props() = mapOf("total_presets" to totalPresets)
    }

    data class PresetsImported(val count: Int, val conflictChoice: String) : TelemetryEvent("presets_imported") {
        override fun props() = mapOf("count" to count, "conflict_choice" to conflictChoice.snakeCase())
    }

    data class ThemeChanged(
        val style: String,
        val palette: String,
        val mode: String,
        val neon: Boolean,
    ) : TelemetryEvent("theme_changed") {
        override fun props() = mapOf(
            "style" to style.snakeCase(),
            "palette" to palette.snakeCase(),
            "mode" to mode.snakeCase(),
            "neon" to neon,
        )
    }

    data class SettingsOpened(val category: String) : TelemetryEvent("settings_opened") {
        override fun props() = mapOf("category" to category.snakeCase())
    }
}
