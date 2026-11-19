# Menu bar popup: design

Date: 2026-11-19. Branch: `worktree-tray-app`. Mockup: https://claude.ai/artifact/X1QCUVUXSfvAkqSvccubpe (layout C,
"Stage", is the chosen one).

## Intent

Today the menu bar (macOS) / system tray (Windows, Linux) icon opens a native menu: one row per device, the "Arrange
mirror windows" submenu, then Open, Settings… and Quit (`ui/tray/TrayMenuModel.kt`, `ui/tray/ScrcpyTray.kt`). The user
wants a richer popup that opens from the icon. From it they want to:

- jump to a device's mirror window, or start mirroring a device;
- check on devices at a glance, including a live picture of each mirroring device's screen;
- act on a device without opening its window (screenshot, stop mirroring);
- choose which devices send input to which (the existing follow link);
- keep everything the menu does today (arrange layouts, Keep tiled, Open, Settings…, Quit).

A second reason: the native menu has to be rebuilt every time a device comes or goes, and those rebuilds caused a macOS
crash when devices were plugged in and out quickly (PR #87, `awaitMainQueue` in `OffMainTray.kt`). Once devices live in
Compose and the native menu never changes, that whole class of crash goes away.

## Goals

- Left-click on the icon opens a Compose popup anchored under the icon (above it on a bottom taskbar). Clicking the icon
  again, pressing Esc or clicking anywhere outside closes it.
- The popup follows mockup layout C ("Stage"), described below.
- Right-click on the icon shows a fixed native menu: Open Scrcpy Vwr, Settings…, Quit. It never changes, so it never
  rebuilds.
- Works on macOS, Windows and Linux, on every monitor, including mixed-scale setups (a Retina laptop with a 1x external
  display, and the menu bar on either one).

## Non-goals

- No new device features. Every action in the popup already exists elsewhere (mirror window, Devices screen, the
  arrange controls). The popup reuses them.
- No change to the follow link's rules (`ui/mirror/follow/InputFollowing.kt`): one leader, a set of followers, open
  mirrors only.
- No popup settings in this round (size, which sections show). The existing "menu bar icon" setting keeps working as it
  does: off hides the icon and the popup.

## Anti-goals (would make this a failure even if it works)

- Bringing back any crash or hang from the tray's history: the icon must keep using the stable bundled path icon
  (`TrayIconFiles.kt`), and every native tray call stays on the tray thread (`OffMainTray.kt`).
- The popup opening off-screen, at the wrong size, or blurry on a monitor whose scale differs from the main one.
- Screen thumbnails costing anything while the popup is closed.
- Anything snapping. Every state change animates (see Motion).

## Approaches considered

1. **Nucleus `TrayApp`** (`composenativetray-app` 2.1.7). Rejected. Read from the shipped jars: it has no icon-path
   overload (it always rasterises with `renderComposableToPngFile`, the path behind the old JNI use-after-free crash),
   it calls `NativeTray.update` on the main thread (the old startup deadlock), and its panel
   (`TaoStandalonePopupHostMac`) fixes its scale to the primary monitor's (`primaryMonitorScaleFactor()`), which is why
   its popup lands off-screen on mixed-scale setups.
2. **Our tray plus `TaoStandalonePopup`** (the panel TrayApp uses, public in `decorated-window-tao` 2.5.18). Rejected
   for the same primary-monitor scale bug.
3. **Our tray plus our own popup window (chosen).** Keep `OffMainTray` (path icon, tray thread), add its
   `primaryAction` for left-click, and show the popup in a Nucleus `DecoratedWindow` that is undecorated, transparent,
   always on top and hidden from the Dock. Normal Tao windows already handle each monitor's scale (the mirror windows
   prove it), and `LaunchOverlayWindow` (`ui/window/launch/LaunchOverlay.kt`) already uses this window style. We
   position it ourselves from the click point and `NativeScreenGeometry`.

## The popup (layout C, "Stage")

Fixed width 360 dp. Top to bottom:

1. **Header:** "Devices" and "N connected".
2. **Stage:** the selected device, large.
   - Mirroring: its screen (portrait phones about 112 dp wide; landscape or tablets about 190 dp wide), and beside it
     the device name, serial (monospace), a "Mirroring" tag, its follow role tag ("Leads 2" / "Follows Pixel 8 Pro"),
     then actions with words: **Bring to front**, **Screenshot**, **Send input to…** (only with 2+ mirrors open),
     **Stop mirroring**.
   - Connected but not mirroring: a device icon, name, serial, a "Ready" tag and **Mirror**. If its options can't
     mirror, Mirror opens the device on the Devices screen instead, the same as `TrayCommands.mirror` today.
   - Unauthorized or offline: a warning icon and "Unlock the phone and tap Allow on the USB debugging prompt" (or the
     matching state text the Devices screen uses).
   - No devices: an empty stage that says no device is connected.
3. **Send input to… panel** (folds open under the stage when the button is pressed). The device on stage is the one
   sending, like the mirror title bar's "Send input to…" control.
   - Normal case: "Send <device>'s taps, keys and text to:" and a chip per other mirroring device; selected chips show a
     check. Toggling chips calls `InputFollowing.setFollowers(stageSerial, chosen)`. The button shows a small count of
     the stage device's followers.
   - If another device leads: a note says picking a device here stops that device sending (the link allows one leader).
   - If the stage device is itself a follower: "<device> follows <leader>, so its input comes from there" and a
     **Stop following** button that removes it from the followers.
   - The panel closes itself when fewer than 2 mirrors are open or the stage device stops mirroring.
4. **Device strip:** every connected device, mirroring ones with a small live screen and the rest with an icon, name
   below. The selected one is highlighted. Clicking one puts it on stage. Order matches the Devices screen.
5. **Arrange mirror windows** (only with 2+ mirrors open): the five layouts as an icon + label segmented row (As placed,
   Grid, Side by side, Focus, Cascade) with the current one selected, and a **Keep tiled** switch. Same behaviour and
   enabled state (`ArrangeState.movable`) as today's submenu.
6. **Footer:** Open Scrcpy Vwr, Settings…, and a Quit icon button.

Which devices appear (strip, stage, "N connected"): the same set today's menu shows. Every device adb lists, plus every
open mirror even when adb no longer lists its device or reports it unauthorized or offline (as `trayMenu` does now). An
open mirror always counts as mirroring, whatever adb says, so its window can still be brought forward or stopped.

Which device is on stage: the most recently focused mirror window when the popup opens, else the first mirroring
device, else the first connected device. If the stage device disconnects, the stage moves to the next by the same rule.

Height follows the content, capped at the screen's work area minus a margin; past that the popup content scrolls.

### Screen thumbnails

Each open mirror already holds its latest decoded frame (`ScrcpySession.frames`, a `VideoFrame`). While the popup is
visible, about once a second, copy each mirroring device's latest frame into a small downscaled `ImageBitmap` (reuse
`Screenshot.copy` / `copyLatest` logic, scaled to the size it is drawn at, not full resolution). Stop the refresh and
drop the bitmaps when the popup hides. Never hold a `VideoFrame` past the copy (the decoder may release it).

### Actions map to existing code

| Popup action | Existing path |
|---|---|
| Mirror / Bring to front | `TrayCommands` (`TrayAction.Mirror`, `TrayAction.Focus`) |
| Screenshot | the mirror window's screenshot action (same save location, same thumbnail toast in that window) |
| Stop mirroring | the same close path as the mirror window's close |
| Send input to… / Stop following | `InputFollowing.setFollowers` |
| Arrange layouts, Keep tiled | `TrayAction.Arrange`, `TrayAction.KeepTiled` |
| Open, Settings…, Quit | `TrayAction.OpenMain`, `TrayAction.Settings`, `TrayAction.Quit` |

Every action that brings a window forward (Bring to front, Mirror, Open, Settings…) also closes the popup. Toggles
(chips, arrange, Keep tiled, picking a stage device) keep it open.

Screenshot (designer's call): the popup posts a screenshot request to that device's mirror window (a new request
channel into `MirrorWindow.takeScreenshot`, like `MirrorRequests`), and the window does what `Settings.afterScreenshot`
says. The mirror window is not brought forward. The popup stays open and gives its own feedback: the stage screen
flashes and a short line under the actions says what happened ("Saved to Screenshots", "Copied"). If the setting opens
the markup editor, the editor window opens and the popup closes, since a window came forward.

## The tray side

- `OffMainTray` gains a `primaryAction` (passed through to `NativeTray.update`) that only posts a "toggle popup"
  request, like `TrayCommands`: the native callback thread never runs app code.
- The menu content becomes fixed (Open Scrcpy Vwr, Settings…, Quit), so the tray updates only when the icon or tooltip
  changes. Keep `awaitMainQueue` anyway; it costs nothing.
- Without `primaryAction` the library opens the menu on every click; with it, left-click (macOS, Windows) toggles the
  popup and right-click opens the menu. On Linux, single-click behaviour depends on the desktop; if the desktop only
  ever shows the menu, the menu's "Open Scrcpy Vwr" stays the way in. (Builder: confirm on GNOME with AppIndicator and
  on KDE, and add an "Show devices…" menu item on Linux only if left-click doesn't reach `primaryAction`.)

## Positioning

- macOS: on the click, read the pointer location in points (`NSEvent.mouseLocation` via the existing `ObjC` helper in
  `ui/window/MacVibrancy.kt`), flip it to top-left coordinates the way `NativeScreenGeometry` does (`flipMacRect`), and
  pick that screen with `screenFor`. Place the popup's top edge just under that screen's menu bar (work area top + a
  small gap), its right edge near the click x, clamped inside the screen's work area.
- Windows: the pointer position at the click (Win32 `GetCursorPos` through JNA, physical pixels), mapped to the monitor
  and scaled to that monitor's logical units the way `NativeScreenGeometry.windowsScreens` does. Place the popup above
  the taskbar (or below / beside it when the taskbar is on another edge), clamped inside the work area.
- Linux: the builder picks the best available source (pointer position if the backend exposes it, else the top-right
  of the primary work area).
- No AWT anywhere (`NoAwtToolkitTest` must keep passing): no `MouseInfo`, no `java.awt.Toolkit`.

## The popup window

- `DecoratedWindow(undecorated = true, transparent = true, alwaysOnTop = true, hiddenFromDock = true, resizable =
  false, focusable = true)`, created once and kept (shown and hidden), so opening is instant. Its content is drawn with
  `ScrcpyViewerTheme` and the app's current theme, rounded corners and a shadow inside the transparent window.
- Closes when it loses focus, on Esc, or when the icon is clicked again. Make sure the click on the icon that closes it
  doesn't also count as a focus loss followed by a reopen.
- **macOS: opening the popup must not move the user's other windows (user's decision).** Opening it while another app
  is in front must not activate Scrcpy Vwr in a way that brings the main or mirror windows forward. Behave like a native
  menu bar app: a non-activating panel (for example, the popup's NSWindow given the non-activating panel behaviour and
  allowed to become key through the `ObjC` helper), with click-outside detected by a global mouse-down monitor
  (`NSEvent addGlobalMonitorForEventsMatchingMask:`) or the window resigning key. If Esc can't reach a non-activating
  popup, losing Esc is acceptable; clicking outside and clicking the icon must still close it. Activating the app is
  only acceptable once the user picks an action that brings a window forward. Windows: the tray click gives the app the
  right to take the foreground; use it (`SetForegroundWindow` on the popup) so focus-loss closing works.
- `nucleusApplication(dockIconFollowsWindows = true)` is already the app's setup; the popup must not add a Dock tile.
- Provides `LocalReduceMotion` like every `AppWindow` root (see CLAUDE.md).
- Theme styles (designer's call): the popup follows the app's palette and its theme style (Material, Glass, Soft,
  Retro) like every other window, using the shared styled controls in `ui/theme` instead of hand-drawn Material ones.
  The mockup shows only Material. Glass uses the same macOS vibrancy as `AppWindow` where the transparent popup allows
  it; if vibrancy doesn't work on the popup, Glass falls back to its translucent surface without the blur. Scene tests
  cover Material at every state and each other style at one state.

## Motion

Uses the app's motion rules (`layoutSpring()`, `LocalReduceMotion`; under reduce motion, changes fade or snap).

- Popup open: scale from about 0.9 to 1 from the corner nearest the icon, plus fade. Close: the reverse, faster.
- Popup height changes (panel folding, Arrange appearing, devices coming and going) animate the window's content height
  with a spring, clamped to at least 0.
- Stage switch: shared-axis slide sideways, direction matching the strip order, with a crossfade.
- Send input panel: folds open and shut with a spring; its arrow rotates; chip fill and check mark animate; its text
  crossfades when the stage device changes.
- Strip items: enter by growing and fading in, leave the reverse, others glide into place. The selection highlight
  slides between items.
- Tags (Mirroring, Ready, Leads, Follows) crossfade when they change.
- Arrange section folds in and out at the 2-mirror threshold; its selection slides between layouts; the Keep tiled
  switch thumb slides.
- Thumbnails crossfade to each new frame instead of swapping.

## Tests

- Unit: the stage-selection rule; the send-input panel's state for leader / follower / neither; the popup position
  function (pure: click point, screens, popup size, taskbar edge to rectangle) including a mixed-scale two-screen case
  and the menu bar on the secondary screen.
- Scene tests at the popup's fixed 360 dp width: 0, 1, 2 and 5 devices; the panel open; a short work area that forces
  scrolling. Add the popup to `ui/NarrowWidthsTest.kt` (primary action visible and inside, no text under 40 px).
- Pass `motion` / `LocalReduceMotion` explicitly (CI reports reduce motion on).
- Keep `TrayMenuModelTest` and friends passing, or replace them with tests for the fixed menu.
- Hand check on a Mac with a mixed-scale external display (menu bar on each), on Windows, and on one Linux desktop;
  plug devices in and out quickly with the popup open and closed.

## Left to the builder

- Exact sizes, paddings and type styles inside the stated layout.
- How the popup window hides (visible = false vs. moving off-screen) as long as it reopens instantly and doesn't leak.
- "Most recently focused mirror window" comes from `MirrorWindows.lastFocused`, which already exists.
- Thumbnail size and filtering, within "the size it's drawn at".
