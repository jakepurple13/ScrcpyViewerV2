# Menu Bar Popup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Left-click on the Scrcpy Vwr menu bar / tray icon opens a Compose popup (mockup layout C, "Stage") with live
device screens, device actions, Send input to…, Arrange, and Open / Settings / Quit; right-click shows a fixed native
menu.

**Architecture:** Keep `OffMainTray` (bundled path icon, tray thread) and add its `primaryAction`. The popup is our own
undecorated, transparent, always-on-top `DecoratedWindow`, positioned from the pointer location and
`NativeScreenGeometry`. Pure models (device list, stage choice, send-input state, placement) are unit tested; the UI is
a stateless composable driven by them.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, Nucleus Tao (`nucleus.decorated-window-tao` 2.5.18),
`composenativetray` 2.1.7, JNA (ObjC runtime, Win32), Koin, kotlin.test + `ImageComposeScene`.

**Spec:** `docs/superpowers/specs/2026-11-19-tray-popup-design.md`

All paths below are under `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/` (main) or
`composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/` (test) unless they start with `docs/`.

Run tests: `./gradlew :composeApp:jvmTest --tests '<FQN or pattern>'`. Serial only: never run two Gradle builds at
once (this machine OOMs).

## Global Constraints

- Popup width fixed at 360 dp; height follows content, capped at the screen work area minus a margin, then scrolls.
- No AWT anywhere (`NoAwtToolkitTest` must pass): no `MouseInfo`, `java.awt.Toolkit`, `awtWindow`. `java.awt.Rectangle`
  as a data type is allowed (already used by `ScreenGeometry.kt`).
- Every native tray call stays on the tray thread inside `OffMainTray`; the icon stays the bundled path icon
  (`TrayIconFiles.kt`). Never use the ImageVector / Painter tray overloads or `TrayApp`.
- Native menu callbacks and `primaryAction` only post requests (flow emission / state change); they never run app code.
- macOS: opening the popup must not activate the app in a way that brings main or mirror windows forward. Losing Esc is
  acceptable; outside click and icon click must close it.
- Device set = today's `trayMenu` set: every adb device plus every open mirror whose device adb no longer lists; an
  open mirror always counts as mirroring.
- Motion: sizes / positions use `layoutSpring()`; read `LocalReduceMotion.current`; clamp animated sizes to ≥ 0; under
  reduce motion changes fade or snap. Every state change animates (no snaps).
- Theme: the popup uses `ScrcpyViewerTheme(settings.appTheme)` and the styled controls in `ui/theme/StyledControls.kt`
  (`Button`, `TextButton`, `FilledTonalButton`, `FilterChip`, `Switch`) and `StyleDecor.kt` modifiers, so all four
  `ThemeStyle`s apply.
- Tests pass `LocalReduceMotion` explicitly (CI reports reduce motion on).
- Copy (exact): "Devices", "N connected" ("1 connected"), "Mirroring", "Ready", "Bring to front", "Screenshot",
  "Send input to…", "Stop mirroring", "Mirror", "Stop following", "Arrange mirror windows", "Keep tiled",
  "Open Scrcpy Vwr", "Settings…", "Quit Scrcpy Vwr", "No device connected",
  "Send <name>'s taps, keys and text to:", "<name> follows <leader>, so its input comes from there.",
  "Picking a device here stops <leader> sending input.",
  "Unlock the phone and tap Allow on the USB debugging prompt.", "Offline".

## Review Focus

1. A device unplugged while it is on stage (or while the Send input panel is open): the stage moves on, the panel
   closes, nothing crashes or shows a stale name. → Task 1 test `stage moves when its device leaves`, Task 6 scene test
   `panel closes when stage device stops mirroring`.
2. A mirror open for a device adb dropped: it still appears, is on stage-able, and Stop mirroring works. → Task 1 test
   `open mirror missing from adb is listed as mirroring`.
3. Click on a secondary screen whose scale differs from the main one (menu bar on that screen): popup lands on that
   screen, inside its work area. → Task 2 test `menu bar on secondary screen of different size`.
4. Popup closed: no thumbnail copies at all. → Task 3 test `inactive refresher copies nothing`.
5. Icon clicked while the popup is open: closes and stays closed (focus loss + toggle must not reopen it). → Task 7
   test `icon click after focus loss does not reopen`.

---

### Task 1: Popup model

**Files:**
- Create: `ui/tray/popup/TrayPopupModel.kt`
- Test: `ui/tray/popup/TrayPopupModelTest.kt`

**Interfaces:**
- Consumes: `Device`, `DeviceState` (`adb/Device.kt`), `FollowState` (`ui/mirror/follow/InputFollowing.kt`).
- Produces:
  - `enum class PopupStatus { Mirroring, Ready, Unauthorized, Offline, Other }`
  - `data class PopupDevice(val serial: String, val title: String, val status: PopupStatus, val rawState: String)`
  - `fun popupDevices(devices: List<Device>, mirroring: Set<String>, title: (Device) -> String): List<PopupDevice>`
    (adb order, then open mirrors adb doesn't list, titled by serial)
  - `fun stageSerial(current: String?, lastFocused: String?, devices: List<PopupDevice>): String?`
  - `sealed interface SendPanel { data object Hidden; data class Choose(val targets: List<PopupDevice>, val selected: Set<String>, val otherLeader: String?); data class Following(val leader: String) }`
  - `fun sendPanel(stage: String?, devices: List<PopupDevice>, follow: FollowState): SendPanel`
  - `fun toggledFollowers(stage: String, target: String, follow: FollowState): Set<String>` (followers to pass to
    `InputFollowing.setFollowers(stage, …)`)
  - `fun withoutFollower(serial: String, follow: FollowState): FollowState` (for Stop following)

- [ ] **Step 1: Write the failing tests** in `TrayPopupModelTest`:
  - `open mirror missing from adb is listed as mirroring`: devices `[A ready]`, mirroring `{A, Z}` → serials `[A, Z]`,
    both `Mirroring`, Z titled `"Z"`.
  - `unauthorized mirror counts as mirroring`: A unauthorized, mirroring `{A}` → `Mirroring`.
  - `stage keeps current when still present`; `stage starts at last focused mirror`; `stage falls back to first
    mirroring then first device`; `stage moves when its device leaves` (current `B` gone → first mirroring);
    `no devices gives null stage`.
  - `send panel hidden with fewer than two mirrors`; `hidden when stage not mirroring`;
    `choose lists other mirroring devices` (stage A leads `{B}` → targets `[B, C]`, selected `{B}`, otherLeader null);
  - `choose names other leader` (C leads `{B}`, stage A → selected `{}`, otherLeader `"C"`);
  - `following when stage follows another` (C leads `{A}`, stage A → `Following("C")`).
  - `toggle adds and removes`; `toggle from another leader starts fresh` (C leads `{B}`, stage A toggles B → `{B}`).
  - `withoutFollower drops leader when last follower leaves` (→ `FollowState()`).

- [ ] **Step 2: Run to verify failure**
  Run: `./gradlew :composeApp:jvmTest --tests '*TrayPopupModelTest'` → FAIL (unresolved references).

- [ ] **Step 3: Implement** the Produces list in `TrayPopupModel.kt`. Status mapping: serial in `mirroring` →
  `Mirroring`; else `DeviceState.Device` → `Ready`, `Unauthorized` → `Unauthorized`, `Offline` → `Offline`, else
  `Other`.

- [ ] **Step 4: Run** the same command → PASS.

- [ ] **Step 5: Commit** `feat(tray): popup device, stage and send-input model`.

### Task 2: Popup placement and pointer location

**Files:**
- Create: `ui/tray/popup/PopupPlacement.kt`, `ui/window/PointerLocation.kt`
- Modify: `ui/window/MacVibrancy.kt` (`ObjC`: add `msgPoint(receiver, selector): DoubleArray` next to `msgRect`, an
  `NSPoint`/`CGPoint` `Structure.ByValue` with two doubles)
- Test: `ui/tray/popup/PopupPlacementTest.kt`

**Interfaces:**
- Consumes: `ScreenArea`, `screenFor`, `flipMacRect`, `NativeScreenGeometry` (`ui/window/ScreenGeometry.kt`).
- Produces:
  - `fun popupBounds(clickX: Int, clickY: Int, width: Int, contentHeight: Int, screens: List<ScreenArea>, gap: Int = 8): Rectangle?`
    in window units (points on macOS, scaled px on Windows). Null with no screens.
  - `fun pointerLocation(): IntArray?` (`[x, y]` in the same window units, top-left origin; null on Linux or failure).

- [ ] **Step 1: Write failing tests** in `PopupPlacementTest` (screens built as `ScreenArea(bounds, workArea)`):
  - `macOS menu bar click puts popup under the bar, right-aligned to click`: screen 0,0,1512,982 work 0,33,1512,949;
    click (1300, 12), w 360, h 500 → `Rectangle(1300 - 360 + 18, 41, 360, 500)` clamped so `x + 360 <= 1512 - 8`.
    (Rule: x = click x + 18 − width, clamped to [work.x + gap, work.right − gap − width].)
  - `menu bar on secondary screen of different size`: main 0,0,1512,982; second 1512,-200,2560,1440 (work y −175);
    click (3900, −190) → popup on second screen, `y == -175 + 8`, `x + 360 <= 1512 + 2560 - 8`.
  - `bottom taskbar puts popup above it`: screen 0,0,1920,1080 work 0,0,1920,1032; click (1800, 1050) →
    `y == 1032 - 8 - h`.
  - `tall content is capped to the work area`: h 2000 on work height 949 → `height == 949 - 16`.
  - `left taskbar puts popup beside it`: work 48,0,1872,1080; click (20, 700) → `x == 48 + 8`, y = click y clamped.
  - `no screens gives null`.

- [ ] **Step 2: Run** `--tests '*PopupPlacementTest'` → FAIL.

- [ ] **Step 3: Implement `popupBounds`.** Screen = `screenFor(Rectangle(clickX, clickY, 1, 1), screens)`. Click above
  the work area → top edge at `work.y + gap`; below → bottom edge at `work.bottom − gap`; left / right of it → beside
  that edge, y centered on click and clamped; inside (Linux fallback) → top-right of the work area. Height =
  `min(contentHeight, work.height − 2 * gap)`.

- [ ] **Step 4: Implement `pointerLocation()`.** macOS: `ObjC.msgPoint(ObjC.cls("NSEvent"), "mouseLocation")`, flip
  with the main screen height like `NativeScreenGeometry.macScreens` (y' = mainHeight − y). Windows: JNA
  `User32.GetCursorPos` (physical px), `MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST)`, divide by that monitor's
  scale the way `NativeScreenGeometry.scaled` does. Else null. Catch every Throwable → null.

- [ ] **Step 5: Run** `--tests '*PopupPlacementTest' --tests '*NoAwtToolkitTest'` → PASS.

- [ ] **Step 6: Commit** `feat(tray): place the popup at the icon on any screen`.

### Task 3: Screen thumbnails

**Files:**
- Modify: `media/Screenshot.kt`
- Create: `ui/tray/popup/ScreenThumbnails.kt`
- Test: `media/ScreenshotThumbnailTest.kt`, `ui/tray/popup/ScreenThumbnailsTest.kt`

**Interfaces:**
- Consumes: `VideoFrame` (`VideoFrame.of(bitmap)` for tests), `ScrcpySession.frames`, `MirrorController.open`.
- Produces:
  - `fun Screenshot.thumbnail(frame: VideoFrame, maxWidth: Int, maxHeight: Int): ImageBitmap?` (scaled inside
    `frame.use`, aspect kept, never upscaled; null when released)
  - `class ScreenThumbnails(scope: CoroutineScope, frames: () -> Map<String, () -> VideoFrame?>, maxWidth: Int = 380, maxHeight: Int = 760, periodMs: Long = 1_000)`
    with `val images: StateFlow<Map<String, ImageBitmap>>` and `fun setActive(active: Boolean)`. Inactive → cancels
    the loop and clears `images`. Max sizes are pixels (2x of the 190 / 112 dp stage sizes).

- [ ] **Step 1: Failing tests.** `thumbnail fits inside the box keeping aspect` (1080×2400 bitmap, box 380×760 →
  342×760); `thumbnail never upscales` (100×200 → 100×200); `released frame gives null`.
  `inactive refresher copies nothing` (counting frame source, advance virtual time 5 s → 0 calls, `images` empty);
  `active refresher copies once a second` (advance 3 s → 3 or 4 rounds); `deactivating clears images`.

- [ ] **Step 2: Run** `--tests '*ScreenshotThumbnailTest' --tests '*ScreenThumbnailsTest'` → FAIL.

- [ ] **Step 3: Implement.** Thumbnail: inside `frame.use { src -> }` draw `Image.makeFromBitmap(src)` scaled into a
  `Surface.makeRasterN32Premul(w, h)` with linear filtering, take `makeImageSnapshot().toComposeImageBitmap()`. Never
  keep `src` past the block. Refresher: one `launch` loop while active; `delay(periodMs)` between rounds; copies run on
  `Dispatchers.Default`.

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(tray): small live screen thumbnails for the popup`.

### Task 4: Screenshot request into the mirror window

**Files:**
- Modify: `ui/mirror/MirrorController.kt`, `ui/mirror/MirrorWindow.kt` (collect next to the existing
  `recordingResults` collector near line 505)
- Test: the existing MirrorController test file (`ui/mirror/MirrorControllerTest.kt`; create it if missing)

**Interfaces:**
- Produces: `MirrorController.screenshotRequests: SharedFlow<String>`, `fun requestScreenshot(serial: String)`
  (emits only when `serial` is open, like `focus`).

- [ ] **Step 1: Failing tests** `screenshot request for open mirror is emitted`, `screenshot request for closed serial
  is dropped`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** (`MutableSharedFlow(extraBufferCapacity = 16)`); in
  `MirrorWindow`, `LaunchedEffect(serial) { controller.screenshotRequests.filter { it == serial }.collect { takeScreenshot() } }`.
  The window is not brought forward.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(mirror): take a screenshot on request from outside the window`.

### Task 5: Popup UI: stage, strip, footer

**Files:**
- Create: `ui/tray/popup/TrayPopupContent.kt`
- Test: `ui/tray/popup/TrayPopupContentTest.kt`; modify `ui/NarrowWidthsTest.kt`

**Interfaces:**
- Consumes: Task 1 model; `ImageBitmap` map from Task 3; `TrayAction` (`ui/tray/TrayMenuModel.kt`).
- Produces:
  - `data class TrayPopupUiState(val devices: List<PopupDevice>, val stage: String?, val thumbnails: Map<String, ImageBitmap>, val sendPanel: SendPanel, val arrange: ArrangeState?, val keepTiled: Boolean, val screenshotNote: String? = null)`
  - `sealed interface PopupEvent { data class Pick(val serial: String); data class Act(val action: TrayAction, val serial: String?); data class Screenshot(val serial: String); data class Stop(val serial: String); data object ToggleSend; data class ToggleFollower(val serial: String); data object StopFollowing }`
  - `@Composable fun TrayPopupContent(state: TrayPopupUiState, sendOpen: Boolean, onEvent: (PopupEvent) -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: Failing scene tests** (360 dp wide scene, `LocalReduceMotion provides true`, new scene per case):
  `empty shows No device connected`; `mirroring stage shows its actions` ("Bring to front", "Screenshot",
  "Stop mirroring"; no "Send input to…" with one mirror); `ready stage shows Mirror`; `unauthorized stage shows the
  prompt text`; `strip lists every device and highlights the stage`; `clicking a strip item emits Pick`;
  `footer emits OpenMain, Settings and Quit`; `header counts devices` ("3 connected"). In `NarrowWidthsTest` add
  `Tray popup - Bring to front at its width` (width 360 only; primary "Bring to front" shown and inside, no text under
  40 px), and `each theme style renders the stage` (one scene per `ThemeStyle`).
- [ ] **Step 2: Run** `--tests '*TrayPopupContentTest' --tests '*NarrowWidthsTest'` → FAIL.
- [ ] **Step 3: Implement** header, stage, strip, footer per spec §"The popup". Stage screen: thumbnail when present,
  else a device-icon tile; landscape frames 190 dp wide, portrait 112 dp. Stage switch: `AnimatedContent` keyed by
  stage serial, horizontal slide in strip-order direction + fade (fade only under reduce motion). Strip: `LazyRow` with
  `animateItem()`; selection with `listRow(selected, shape)`. Tags crossfade. Thumbnails crossfade
  (`Crossfade` on the bitmap). Footer uses `TextButton`s. Whole content height animates (`animateContentSize(layoutSpring())`
  is fine here: the popup is fixed width, not window-sized). The device part scrolls (`verticalScroll`) when the
  caller's `Modifier.heightIn(max = …)` is shorter than the content; header and footer stay put.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(tray): popup stage, device strip and footer`.

### Task 6: Popup UI: Send input panel and Arrange

**Files:**
- Modify: `ui/tray/popup/TrayPopupContent.kt`
- Test: `ui/tray/popup/TrayPopupContentTest.kt`

**Interfaces:**
- Consumes: `SendPanel`, `PopupEvent.ToggleSend / ToggleFollower / StopFollowing / Act(Arrange|KeepTiled)`,
  `ArrangeState` (`movable`, current layout), `ArrangeLayout` labels (`ui/mirror/arrange/ArrangeControls.kt`).

- [ ] **Step 1: Failing scene tests:** `send button shows only with two mirrors`; `open panel lists targets with
  selection` (chip selected state); `chip click emits ToggleFollower`; `other leader note shows`;
  `following state shows Stop following`; `panel closes when stage device stops mirroring` (state switches to
  `SendPanel.Hidden` → panel text gone after settle); `arrange shows with two mirrors and emits layouts`;
  `keep tiled switch emits KeepTiled(true)`; `arrange buttons disabled when not movable`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** Send panel under the stage: `AnimatedVisibility(expandVertically(layoutSpring()) + fadeIn)`,
  arrow rotates with `animateFloatAsState`, follower count badge fades; chips are `FilterChip`s; panel text
  crossfades on stage change. Arrange: same fold at the 2-mirror threshold; five icon + label buttons with a sliding
  selected background (`layoutSpring()` offset), then `Switch` "Keep tiled". Copy exactly as Global Constraints.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(tray): send input and arrange in the popup`.

### Task 7: Popup window, left-click, fixed menu

**Files:**
- Create: `ui/tray/popup/TrayPopupWindow.kt`, `ui/tray/popup/TrayPopupController.kt`
- Modify: `ui/tray/OffMainTray.kt` (add `primaryAction: (() -> Unit)?`, passed to `tray.update`),
  `ui/tray/ScrcpyTray.kt` (fixed menu + primary action), `ui/tray/TrayMenuModel.kt` (replace `trayMenu` with
  `fixedTrayMenu(linux: Boolean = Os.isLinux): List<TrayRow>` = (Linux only: "Show devices…" first, since some desktops never deliver left-click) Open Scrcpy Vwr, Settings…, divider, Quit Scrcpy Vwr; keep `TrayAction`,
  `TrayCommands`, `render`), `Main.kt` (lines ~168–187: replace the `ScrcpyTray(...)` call)
- Test: `ui/tray/popup/TrayPopupControllerTest.kt`; update `ui/tray/TrayMenuModelTest.kt`

**Interfaces:**
- Consumes: Tasks 1–6; `InputFollowing` (Koin), `MirrorWindows.lastFocused`, `MirrorController`
  (`open`, `close`, `requestScreenshot`), `arranger`, `TrayCommands`, `Settings.afterShot`.
- Produces:
  - `class TrayPopupController(now: () -> Long = System::currentTimeMillis)`: `val visible: StateFlow<Boolean>`,
    `val anchor: StateFlow<IntArray?>`, `fun iconClicked(pointer: IntArray?)`, `fun dismissed()` (focus loss / Esc /
    action), with a 300 ms guard: an `iconClicked` within 300 ms after `dismissed()` stays closed.
  - `@Composable fun NucleusApplicationScope.TrayPopupWindow(controller: TrayPopupController, theme: AppTheme, content: @Composable (maxHeight: Int) -> Unit)`

- [ ] **Step 1: Failing tests:** `icon click opens with anchor`; `second icon click closes`;
  `icon click after focus loss does not reopen` (dismissed at t, iconClicked at t+100 → still hidden; at t+400 →
  opens); `fixed menu has open settings quit` (update `TrayMenuModelTest`: delete the device-row cases, keep
  arrange-label coverage only if `arrangeSubmenu` survives, otherwise delete it too).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement controller and tray changes.** `primaryAction = { controller.iconClicked(pointerLocation()) }`
  runs on the tray thread: `pointerLocation()` is a quick native read, and `iconClicked` only writes state.
- [ ] **Step 4: Implement `TrayPopupWindow`.** `DecoratedWindow(onCloseRequest = controller::dismissed, state, title = "Scrcpy Vwr", visible = visible, resizable = false, focusable = true, alwaysOnTop = true, undecorated = true, transparent = true, hiddenFromDock = true)`, created once and toggled with
  `visible`. Bounds from `popupBounds(anchor, 360, measured content height, NativeScreenGeometry.screens())`; update
  the window state when content height changes. Provide `LocalReduceMotion` from `systemReducesMotion()` at the root
  (allowed: this is a window root). Content: rounded `stylePane` surface with shadow inside the transparent window;
  open/close = scale 0.9→1 from the corner nearest the icon + fade. Close on `LocalWindowInfo.isWindowFocused`
  turning false (after it was true), on Esc (`onPreviewKeyEvent`), and via `dismissed()`. Thumbnails `setActive`
  follows `visible`.
- [ ] **Step 5: Wire in `Main.kt`.** Build `TrayPopupUiState` from `mirrorController.devices`, `mirrors.keys`,
  `inputFollowing.state`, `arranger.state`, thumbnails, `mirrorWindows.lastFocused`; route `PopupEvent`s:
  `Act` → `trayCommands.dispatch` then `dismissed()` for Focus / Mirror / OpenMain / Settings; `Stop` →
  `mirrorController.close`; `Screenshot` → `requestScreenshot` and set `screenshotNote` ("Saved to Screenshots" /
  "Copied" by `afterShot`; `dismissed()` when it opens the editor); follow events → `inputFollowing.setFollowers`.
  Same visibility condition as today (`showTrayIcon && trayIcons != null && Running`).
- [ ] **Step 6: Run** `./gradlew :composeApp:jvmTest` (full suite, serial) → PASS.
- [ ] **Step 7: Hand check** (launch the desktop app; no device hold needed): left-click opens under the icon,
  right-click shows the three items, actions work, plug a phone in and out with the popup open and closed.
- [ ] **Step 8: Commit** `feat(tray): menu bar popup on left-click, fixed right-click menu`.

### Task 8: macOS non-activating popup

**Files:**
- Create: `ui/tray/popup/MacPopupPanel.kt`
- Modify: `ui/tray/popup/TrayPopupWindow.kt`

**Interfaces:**
- Produces: `fun makeNonActivatingPanel(nsWindow: Pointer): Boolean` (true when converted), applied once after the
  popup's Tao window exists (`window.unsafe.taoWindow?.nsWindowHandle`, as `LaunchOverlay.setWindowAlpha` reads it).

- [ ] **Step 1: Implement** with the `ObjC` helper: create (once) a runtime subclass of `NSPanel` with
  `objc_allocateClassPair`, adding `canBecomeKeyWindow → YES`; `object_setClass` the Tao window to it (the
  tauri-nspanel technique); add `NSWindowStyleMaskNonactivatingPanel` (1 << 7) to `styleMask`; set
  `collectionBehavior` to canJoinAllSpaces | fullScreenAuxiliary; `setHidesOnDeactivate:NO`. Show with
  `orderFrontRegardless` + `makeKeyWindow`, never `NSApp activate`. Return false on any failure (popup then behaves
  as in Task 7).
- [ ] **Step 2: Hand check** on macOS with another app (Safari) in front and a mirror window open behind it: popup
  opens, Safari stays in front, mirror window doesn't move; Esc closes it (if not, note it: acceptable); clicking
  Safari, the desktop, or the icon closes it; Bring to front then activates and focuses the mirror. Repeat with the
  menu bar on a 1x external display.
- [ ] **Step 3: Run** `./gradlew :composeApp:jvmTest --tests '*NoAwtToolkitTest' --tests '*TrayPopup*'` → PASS.
- [ ] **Step 4: Commit** `feat(tray): macOS popup opens without raising other windows`.
