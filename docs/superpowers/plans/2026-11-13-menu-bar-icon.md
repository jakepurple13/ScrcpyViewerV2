# Menu Bar Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a static menu bar / system tray icon whose native menu mirrors or focuses devices, opens the main window or Settings, and quits.

**Architecture:** `TrayIconFiles` copies bundled icon files to a stable data-dir path. `TrayMenuModel` is a pure description of the menu built from devices and open mirrors. `OffMainTray` (copied pattern from claude_manager) drives ComposeNativeTray's `NativeTray` on one background thread, keyed by the menu hash. `ScrcpyTray` composes it in `Main.kt` when the `showTrayIcon` setting is on.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12, Nucleus 2.5.18 (Tao), ComposeNativeTray 2.1.7, Koin, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-13-menu-bar-icon-design.md`

## Global Constraints

- Icon static: same `iconPath` / `windowsIconPath` strings every update; never ImageVector/Painter overloads; no menu item icons (emoji in labels).
- All `NativeTray` calls on one single-thread daemon executor ("scrcpyviewer-tray"), never the main thread.
- Menu updates keyed on `MenuContentHash.calculateMenuHash(menuContent)`; no per-second values in labels.
- Copy (exact): header "Scrcpy Vwr · N devices" ("1 device"; "No devices connected" when empty), row "<dot> <title> — Mirroring" / "— Mirror" / "— Accept the USB debugging prompt" / "— Offline", "Open Scrcpy Vwr", "Settings…", "Quit Scrcpy Vwr", tooltip "Scrcpy Vwr", setting "Show icon in menu bar" (macOS) / "Show icon in system tray" (Windows, Linux). Dots: 🟢 mirroring, ⚪ ready, 🟠 unauthorized, ⚫ offline/other.
- `AppSettings.Settings.showTrayIcon: Boolean = true`. Closing the main window still quits.
- Tests: `./gradlew :composeApp:jvmTest --tests '<pattern>'`; one Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Tray created before the first window, during adb startup: no main-thread deadlock (all calls off-main). Check in Task 2 by construction, Task 3 manual launch.
2. Device list churn (plug/unplug repeatedly): menu rebuilds only on real changes, no temp-file growth in the tray dir or java.io.tmpdir. Test in Task 2 (hash stable for equal models).
3. Turning the setting off then on: icon removed and re-added once, no duplicate icon on Windows. Task 3.
4. Mirror clicked from the menu for a device whose mirror is already open: focuses, doesn't open a second. Task 2 model + Task 3 wiring.
5. App quit with the tray present: tray disposed on its thread, no hang on exit. Task 3.

---

### Task 1: Tray icon files

**Files:** Create `composeApp/src/jvmMain/resources/tray/tray.png` (64×64 RGBA, from `icon.png`), `composeApp/src/jvmMain/resources/tray/tray.ico` (16/24/32/48/64), `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/tray/TrayIconFiles.kt`; Test `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/tray/TrayIconFilesTest.kt`. Add `composenativetray = "2.1.7"` and the library to `gradle/libs.versions.toml` and `composeApp/build.gradle.kts`.

**Interfaces:** Produces `data class TrayIcons(val png: Path, val ico: Path)` and `fun trayIconFiles(dir: Path): TrayIcons` (copies resources, writes only if bytes differ, temp + atomic move).

- [ ] Failing tests: `copies both files`, `same paths on repeat`, `does not rewrite identical bytes` (mtime unchanged), `rewrites changed bytes`, `png is 64 wide`, `ico header has 5 sizes`.
- [ ] Run → FAIL. Implement (see claude_manager `TrayIconFiles.kt` for the pattern). Run → PASS.
- [ ] Commit `feat(tray): bundled tray icon files`.

### Task 2: Menu model and off-main tray

**Files:** Create `ui/tray/TrayMenuModel.kt`, `ui/tray/OffMainTray.kt`; Test `ui/tray/TrayMenuModelTest.kt`.

**Interfaces:** Produces `sealed interface TrayRow { Header(text); DeviceRow(serial, label, action: TrayAction?); Divider; Command(label, action) }`, `enum class TrayAction { Mirror, Focus, OpenMain, Settings, Quit }` (device rows carry the serial), `fun trayMenu(devices: List<Device>, mirroring: Set<String>, title: (Device) -> String): List<TrayRow>`, and `@Composable fun OffMainTray(iconPath: String, windowsIconPath: String, tooltip: String, menuContent: TrayMenuBuilder.() -> Unit)` copied from claude_manager (dedicated executor named "scrcpyviewer-tray", update keyed on `MenuContentHash`, dispose on the executor).

- [ ] Failing tests: header for 0/1/2 devices; ready device → "⚪ <title> — Mirror" with Mirror; mirroring → "🟢 … — Mirroring" with Focus; unauthorized → "🟠 … — Accept the USB debugging prompt" no action; offline → "⚫ … — Offline" no action; order follows the given device list; fixed tail rows and dividers; equal inputs give equal models.
- [ ] Run → FAIL. Implement. Run → PASS.
- [ ] Commit `feat(tray): menu model and off-main tray driver`.

### Task 3: Wire the tray and the setting

**Files:** Create `ui/tray/ScrcpyTray.kt`; Modify `Main.kt` (compose `ScrcpyTray` inside the application scope when `showTrayIcon`), `state/AppSettings.kt` (`showTrayIcon`), settings registry/screen (switch row, hidden when tray unsupported); Tests: `AppSettingsTest` round-trip, settings screenshot of the row, `ScrcpyTray` action routing test with fakes if practical.

**Interfaces:** Consumes Task 1 and 2. `ScrcpyTray(devices, mirroring, onMirror(serial), onFocus(serial), onOpenMain, onSettings, onQuit)`; Mirror goes through `MirrorRequests` like the Devices Mirror button; Focus through MirrorController focus requests; OpenMain/Settings bring the main window to front (Settings also navigates to the Settings rail destination); Quit uses the same shutdown path as closing the main window. Tray support check: use the library's capability check if it has one; otherwise treat macOS and Windows as supported and Linux as supported only when the library reports it.

- [ ] Failing tests (settings round-trip; routing), implement, run `--tests '*Tray*' --tests '*AppSettingsTest*' --tests '*Settings*'` and the settings screenshot test.
- [ ] Manual (controller): launch on macOS, open the menu, mirror a device from it, focus an open mirror, Settings…, toggle the setting off/on, quit.
- [ ] Commit `feat(tray): menu bar icon with devices, open and quit`.
