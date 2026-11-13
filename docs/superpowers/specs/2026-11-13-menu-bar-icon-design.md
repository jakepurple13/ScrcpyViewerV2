# Menu bar icon

Date: 2026-11-13
Status: approved (mockup https://claude.ai/artifact/AEV6zuT1JVS9XRxa4GRaQt, "13 Menu bar icon" tab), with the user's
change: the icon is static.

## Goal

A menu bar icon on macOS and a system tray icon on Windows and Linux. Clicking it opens a native menu listing the
connected devices, so a device can be mirrored (or its open mirror brought to the front) without switching to the
main window. The menu also opens the main window, Settings, and quits.

## Decisions already made with the user

- The icon is static: the same image file at all times. No device-count badge, no mirroring dot, no color changes.
- Closing the main window quits the app, the same as today. The icon exists only while the app runs.
- The icon is on by default. A setting turns it off.
- On Linux, when the desktop has no tray support, the setting is hidden.
- The "Arrange mirror windows" entry comes later, with the window-arranging feature. The emulator section comes with
  the emulator launcher. Neither is in this feature.

## Lessons applied (from ~/Downloads/claude_manager, same Nucleus Tao stack)

- Library: ComposeNativeTray (`dev.nucleusframework:composenativetray:2.1.7`), used through `NativeTray` directly.
  Native menu, not Nucleus `TrayApp` (that popup lands off-screen on mixed-scale monitors).
- Icon: bundled `tray/tray.png` (64×64 RGBA, macOS and Linux) and `tray/tray.ico` (multi-size 16/24/32/48/64,
  Windows), copied once to `<data dir>/tray/` (written only when the bytes differ, via temp file + atomic move). The
  same path strings are passed on every update. Never the ImageVector/Painter overloads (they re-render to temp files
  and hit a JNI use-after-free on macOS → SIGTRAP).
- No icons on menu items (the library writes each to a new temp file on every rebuild). Status is shown with emoji in
  the label text.
- Every tray call (create, update, dispose) runs on one dedicated single-thread daemon executor, never the main
  thread (macOS startup deadlock otherwise).
- Updates are keyed on `MenuContentHash.calculateMenuHash(menuContent)`; labels hold no per-second values, so the menu
  only rebuilds when devices or mirrors actually change.

## Menu

Built from the device list (`DeviceTracker` / `DeviceSelection` state) and open mirrors (`MirrorController.open`):

```
Scrcpy Vwr · 2 devices                (disabled header; "No devices connected" when empty)
───────────
🟢 Galaxy A15 5G — Mirroring          → brings its mirror window to the front
⚪ Pixel Tablet — Mirror               → opens a mirror window
🟠 Galaxy Tab S9 — Accept the USB debugging prompt   (disabled)
⚫ Moto G Play — Offline              (disabled)
───────────
Open Scrcpy Vwr                       → brings the main window to the front
Settings…                             → main window, Settings destination
───────────
Quit Scrcpy Vwr
```

- Device title is the same `deviceTitle` the Devices list uses.
- Dots: 🟢 mirroring, ⚪ ready, 🟠 unauthorized, ⚫ offline/other.
- Tooltip: "Scrcpy Vwr" (static).
- Mirroring a device uses the same path as the Devices screen's Mirror button (`MirrorRequests`), including its
  preset/options. Bringing a mirror to the front uses `MirrorController` focus requests.

## Setting

Settings › General (or wherever app-level window behaviour lives): switch "Show icon in menu bar" (macOS) / "Show icon
in system tray" (Windows, Linux). Stored in `AppSettings` as `showTrayIcon: Boolean = true`. Turning it off removes
the icon at once; on adds it at once. Hidden on Linux when tray support isn't available.

## Motion

The icon and native menu are drawn by the OS, so they have no app motion. The settings switch uses the app's existing
switch and row motion.

## Tests

- `TrayIconFilesTest`: copies to the data dir, same paths on repeat calls, no rewrite when bytes match, PNG is 64 wide,
  ICO header valid.
- `TrayMenuModelTest`: a pure function builds the menu model from devices + open mirrors: header text for 0/1/N
  devices, row label and dot per state, mirroring vs mirror action, disabled rows, order matches the Devices list.
- Settings: the switch persists `showTrayIcon` and is hidden when tray support is false (screenshot test of the row).
- Manual: macOS menu bar, Windows CI-only (the user checks the build), Linux CI-only.

## Out of scope

Badges or any dynamic icon, arrange-windows entry (comes with 12), emulator section (comes with 11), screenshot-all.
