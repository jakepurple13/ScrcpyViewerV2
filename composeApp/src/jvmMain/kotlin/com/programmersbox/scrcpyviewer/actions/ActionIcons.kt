package com.programmersbox.scrcpyviewer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector

data class ActionIcon(val name: String, val vector: ImageVector, val keywords: List<String>)

data class IconGroup(val title: String, val icons: List<ActionIcon>)

/** Fixed, name-keyed icon set (no reflection) so the shrink-only release build keeps exactly these. */
object ActionIcons {
    const val TOGGLE_DEFAULT = "toggle_on"
    const val RUN_ONCE_DEFAULT = "play_arrow"

    private fun i(name: String, vector: ImageVector, vararg keywords: String) =
        ActionIcon(name, vector, keywords.toList())

    val groups: List<IconGroup> = listOf(
        IconGroup(
            "Connectivity", listOf(
                i("wifi", Icons.Filled.Wifi, "wireless", "network"),
                i("wifi_off", Icons.Filled.WifiOff, "wireless", "disconnect"),
                i("bluetooth", Icons.Filled.Bluetooth, "pair", "wireless"),
                i("signal_cellular_alt", Icons.Filled.SignalCellularAlt, "mobile data", "cell", "lte"),
                i("airplanemode_active", Icons.Filled.AirplanemodeActive, "airplane", "flight"),
                i("nfc", Icons.Filled.Nfc, "tap", "pay"),
                i("vpn_key", Icons.Filled.VpnKey, "key", "secret"),
                i("network_check", Icons.Filled.NetworkCheck, "ping", "connection"),
            )
        ),
        IconGroup(
            "Device", listOf(
                i("battery_full", Icons.Filled.BatteryFull, "power", "charge", "stay awake"),
                i("battery_saver", Icons.Filled.BatterySaver, "power", "low"),
                i("brightness_6", Icons.Filled.Brightness6, "screen", "dim"),
                i("volume_up", Icons.AutoMirrored.Filled.VolumeUp, "sound", "loud", "audio"),
                i("volume_off", Icons.AutoMirrored.Filled.VolumeOff, "mute", "silent", "sound"),
                i("screen_rotation", Icons.Filled.ScreenRotation, "rotate", "orientation"),
                i("lock", Icons.Filled.Lock, "secure", "keyguard"),
                i("power_settings_new", Icons.Filled.PowerSettingsNew, "shutdown", "reboot", "off"),
            )
        ),
        IconGroup(
            "Display and input", listOf(
                i("dark_mode", Icons.Filled.DarkMode, "night", "theme"),
                i("light_mode", Icons.Filled.LightMode, "day", "theme", "sun"),
                i("touch_app", Icons.Filled.TouchApp, "tap", "finger", "touches"),
                i("visibility", Icons.Filled.Visibility, "show", "eye"),
                i("screenshot", Icons.Filled.Screenshot, "capture", "screen"),
                i("keyboard", Icons.Filled.Keyboard, "type", "ime", "input"),
                i("text_fields", Icons.Filled.TextFields, "text", "font", "size"),
                i("smartphone", Icons.Filled.Smartphone, "phone", "device"),
            )
        ),
        IconGroup(
            "Apps", listOf(
                i("apps", Icons.Filled.Apps, "packages", "grid"),
                i("android", Icons.Filled.Android, "robot", "system"),
                i("install_mobile", Icons.Filled.InstallMobile, "install", "apk"),
                i("delete", Icons.Filled.Delete, "clear", "uninstall", "remove"),
                i("cleaning_services", Icons.Filled.CleaningServices, "clean", "clear data", "wipe"),
                i("open_in_new", Icons.AutoMirrored.Filled.OpenInNew, "launch", "open", "start"),
                i("restart_alt", Icons.Filled.RestartAlt, "restart", "reset", "force stop"),
                i("download", Icons.Filled.Download, "pull", "save"),
            )
        ),
        IconGroup(
            "Debug", listOf(
                i("bug_report", Icons.Filled.BugReport, "bugreport", "issue"),
                i("terminal", Icons.Filled.Terminal, "shell", "logs", "logcat"),
                i("code", Icons.Filled.Code, "script", "dev"),
                i("speed", Icons.Filled.Speed, "performance", "fps", "gpu"),
                i("memory", Icons.Filled.Memory, "ram", "cpu", "meminfo"),
                i("developer_mode", Icons.Filled.DeveloperMode, "developer", "options"),
                i("analytics", Icons.Filled.Analytics, "stats", "dumpsys"),
                i("list_alt", Icons.AutoMirrored.Filled.ListAlt, "list", "dump", "packages"),
            )
        ),
        IconGroup(
            "General", listOf(
                i("toggle_on", Icons.Filled.ToggleOn, "switch", "toggle"),
                i("play_arrow", Icons.Filled.PlayArrow, "run", "start", "go"),
                i("bolt", Icons.Filled.Bolt, "fast", "lightning", "quick"),
                i("refresh", Icons.Filled.Refresh, "reload", "update"),
                i("schedule", Icons.Filled.Schedule, "time", "clock", "wait"),
                i("location_on", Icons.Filled.LocationOn, "gps", "mock", "place"),
                i("notifications", Icons.Filled.Notifications, "alert", "bell"),
                i("photo_camera", Icons.Filled.PhotoCamera, "camera", "picture", "photo"),
            )
        ),
    )

    private val byName: Map<String, ActionIcon> = groups.flatMap { it.icons }.associateBy { it.name }

    /** Unknown or null names fall back to [default]'s vector. */
    fun forName(name: String?, default: String): ImageVector =
        (byName[name] ?: byName.getValue(default)).vector

    /** Matches names and keywords, case-insensitive; empty groups dropped; blank query returns [groups]. */
    fun search(query: String): List<IconGroup> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val hits = group.icons.filter { icon ->
                icon.name.contains(q) || icon.keywords.any { it.contains(q) }
            }
            if (hits.isEmpty()) null else IconGroup(group.title, hits)
        }
    }
}
