# Arrange mirror windows and keyboard shortcuts

> Update 2026-11-13: keyboard shortcuts were dropped by the user; only Arrange ships.

Date: 2026-11-13
Status: approved (mockup https://claude.ai/artifact/AEV6zuT1JVS9XRxa4GRaQt, tabs "12 Tile windows" and "6·12 Shortcut
settings")

## Goal

1. **Arrange mirror windows.** Every mirror window's title bar gets an Arrange button that opens a menu: As placed,
   Grid, Side by side, Focus this window, Cascade, plus "Keep tiled as windows open and close". The same entries are in
   the menu bar icon's menu. Windows animate to their new bounds and keep their device's shape.
2. **Settings › Keyboard shortcuts.** A new settings page where the user records shortcuts. Nothing has a shortcut by
   default. Actions: "Send input to other windows" (multi-device follow on/off) and each Arrange layout. Arrange menu
   items show their shortcut once set.

## Decisions already made with the user

- Default layout is As placed, with Keep tiled off. Picking a layout tiles once; Keep tiled re-tiles when mirror
  windows open or close. Dragging or resizing a window by hand turns Keep tiled off.
- Layouts use the screen the main window is on.
- No default shortcuts; the user sets them in the new page.
- Every state change animates; nothing snaps.

## Arrange

### Layouts (pure function, `ui/mirror/arrange/Layouts.kt`)

Inputs: the work area of the main window's screen, each window's device frame size and chrome (title bar + side
toolbar + open sheet width), and for Focus the focused serial. Output: target outer bounds per serial.

- **As placed:** the bounds each window had before the first layout was applied (remembered when leaving As placed;
  a window opened later has no remembered bounds and stays where it is).
- **Grid:** try every column count 1..n, rows = ceil(n / columns), cell = work area minus 12 dp gaps; fit each window
  in its cell by aspect (`AspectLock.outerHeight`); pick the column count with the largest total window area; centre
  each window in its cell.
- **Side by side:** Grid with columns = n.
- **Focus this window:** the focused window takes the left 62% (fit and centred); the others stack in a column on
  the right, fit and centred in equal cells.
- **Cascade:** each window fit to 32% width × 62% height of the work area, offset 34 dp right and 30 dp down per
  window from the top-left of the work area.
- Windows keep their device aspect ratio; size includes the live chrome.

### Applying (`ui/mirror/arrange/WindowArranger.kt`, Koin singleton)

- A registry (`MirrorWindows`) lists each open mirror window's handle and layout info (frame size, chrome,
  floating/minimized/showing). MirrorWindow registers on first frame and unregisters on dispose.
- Applying animates each window with the existing `animateWindowBounds` (macOS native frame animation, per-frame
  bounds elsewhere), staggered 35 ms per window, 380 ms, gated on the "Animate mirror windows" setting and reduced
  motion (instant when off). While a window is being arranged its own sizing/aspect-correction effects are paused, the
  same way the open animation pauses them.
- Windows that are maximized/fullscreen, minimized or not yet shown are skipped.
- Keep tiled: on mirror open/close, re-apply the current layout (not As placed). A user drag or resize (bounds change
  not caused by the arranger, after the animation settles) turns Keep tiled off.
- Linux: when the screen geometry has no screens, use the main window's bounds as the work area. If a position set
  doesn't take effect (Wayland), the layout entries are disabled with the note "Your desktop doesn't let apps move
  windows".

### UI

- Title bar: an Arrange icon button (grid icon) in a `StripSlot`, shown when at least 2 mirror windows are open,
  collapsing into the ⋮ menu when narrow (same rules and motion as the multi-device button).
- Menu:
  ```
  ARRANGE MIRROR WINDOWS
  ✓ As placed                      ⌥⌘1 (when set)
    Grid
    Side by side
    Focus this window
    Cascade
  ─────────────
  [ ] Keep tiled as windows open and close
  ```
  The check mark moves to the picked layout (scale-in 300 ms), then the menu closes and windows move.
- Menu bar icon: "Arrange mirror windows ›" submenu with the same five entries (✓ prefix on the current one) and
  "Keep tiled" (✓ prefix when on); only shown when at least 2 mirrors are open. Text only (no item icons).

## Keyboard shortcuts (DROPPED 2026-11-13, not built; kept for history)

### Model (`ui/shortcuts/`)

- `Shortcut(keyCode, ctrl, alt, shift, meta)` with display text per OS: macOS symbols in the order ⌃⌥⇧⌘ + key
  ("⌥⌘G"), Windows/Linux "Ctrl+Alt+Shift+G". Stored in `AppSettings.shortcuts: Map<String, String>` (action id →
  encoded shortcut), default empty.
- Actions: `follow` ("Send input to other windows", group "Multi-device input"), `arrange-free`, `arrange-grid`,
  `arrange-row`, `arrange-focus`, `arrange-cascade` (group "Arrange mirror windows"; labels As placed, Grid, Side by
  side, Focus the front window, Cascade).
- A shortcut needs ⌘, ⌥ or ⌃ (Ctrl or Alt on Windows/Linux) so typing still reaches the device.
- Refused (system or app): ⌘Q ⌘W ⌘H ⌘M ⌘, ⌘⇥ ⌘V (macOS); Alt+F4, Ctrl+V, Ctrl+Alt+Del (Windows/Linux); the app's zoom
  keys; the mouse-capture release key.

### Dispatch

- Shortcuts work while any Scrcpy Vwr window is in front (the Tao backend has no global key hook).
- Mirror windows: matched in `MirrorKeys` right after the sheet-focus check and before anything goes to the device;
  the whole chord (press, repeats, release, typed char) is consumed so the device never sees it. Main window and the
  Logcat window get an `onPreviewKeyEvent` that runs the same matcher.
- `follow`: in a mirror window, if a link exists → stop it; otherwise this window leads and every other open
  mirror window that allows control follows. From the main window: same, with the most recently focused mirror window
  as leader; nothing when fewer than 2 mirrors are open.
- Arrange actions: apply that layout (Focus uses the most recently focused mirror window).

### Settings page

New category "Keyboard shortcuts" (after Mirroring). Rows grouped as above, each with a shortcut box:

- Unset: dashed box "Not set". Click → box widens and pulses (accent outline, 1.4 s loop) reading "Press keys… Esc
  cancels"; held modifiers show as keys while pressed.
- A valid chord pops in key by key (staggered 60 ms) and the row gets a clear (×) button that scales in.
- No ⌘/⌥/⌃: the box shakes and a hint expands: "Add ⌘, ⌥ or ⌃ so typing still reaches the device." (Windows/Linux:
  "Add Ctrl or Alt…"). Reserved: "⌘Q belongs to the system. Pick another."
- Taken by another action: a warning strip expands under the row: "⌥⌘G is already used by Grid." [Cancel] [Replace].
  Replace clears the other row (its box fades back to "Not set", the row flashes) and sets this one.
- Search field filters rows; rows and group headers fold closed/open (height + fade).
- "Clear all" clears every shortcut, staggered 70 ms.
- Footnote: "Shortcuts work while a Scrcpy Vwr window is in front. Keys you press in a mirror window go to the device
  unless they match a shortcut here."

## Motion

| Change | Motion |
|---|---|
| Arrange button appears/collapses | same `StripSlot` expand/shrink + fade 200 ms as the multi-device button |
| Picking a layout | check mark scale-in 300 ms, menu closes, windows animate 380 ms with 35 ms stagger (instant with reduced motion or "Animate mirror windows" off) |
| Recorder start | box width grows (FastOutSlowIn 400 ms), outline pulse |
| Keys captured | each key pops in (scale .7→1 + fade, 320 ms, 60 ms stagger) |
| Invalid / reserved | shake 400 ms + hint expand 300 ms |
| Conflict | strip expand 400 ms; Replace flashes the other row 700 ms |
| Clear | key boxes fade out, "Not set" fades in; × scales out |
| Search | rows fold (height + fade 220–320 ms) |

## Tests

- Layouts: grid picks the best column count, side by side is one row, focus split, cascade offsets, aspect kept,
  gaps, small work areas.
- Arranger: skips non-floating/minimized/hidden windows, remembers As placed bounds, Keep tiled re-applies on
  open/close, a manual move turns Keep tiled off, position-not-taking disables layouts.
- Shortcuts: encode/decode, display text per OS, modifier rule, reserved list, conflict detection, matcher
  consumes the whole chord (no stray key-up or typed char reaches the device).
- Dispatch: follow toggle from a mirror window and from the main window; arrange actions.
- Screenshots: title bar with the Arrange button and its menu; the Keyboard shortcuts page idle, recording,
  conflict, invalid.
- Manual: macOS with 3+ mirror windows: each layout, Keep tiled, drag to turn it off, shortcuts in mirror and main
  windows.

## Out of scope

Global shortcuts while the app isn't in front; arranging across several screens; saving layouts across restarts;
shortcuts for other commands (screenshot, record) — they can join the page later.
