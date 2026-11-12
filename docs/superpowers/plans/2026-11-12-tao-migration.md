# Nucleus Tao Window Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every Scrcpy Vwr window from AWT and Jewel to Nucleus's Tao backend, so window resizing is as smooth
as Claude Manager's.

**Architecture:** Task 0 is a throwaway spike on macOS. It decides go or no-go, and **all later tasks wait on it**.
Tasks 1–8 put a seam in front of each AWT dependency while the app still runs on AWT, so every PR stays runnable.
Task 9 swaps Jewel for Nucleus Material windows on Nucleus's AWT backend. Task 10 flips the backend to Tao,
implements the Tao side of each seam, and removes Jewel, JBR and the edge-strip workaround. Task 11 is the macOS
hand pass and the one release.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.12.2, Nucleus 2.5.18 (`nucleus-application`,
`decorated-window-awt` → `decorated-window-tao`, `decorated-window-material3`), JNA (existing `ObjC` helper in
`ui/window/MacVibrancy.kt`), Koin.

**Spec:** `docs/superpowers/specs/2026-11-12-tao-migration-design.md` (approved, commit `bfff13a`).

## Global Constraints

- Nucleus 2.5.18 for every `dev.nucleusframework` module. Compose Multiplatform 1.12.2, Kotlin 2.3.21.
- The final app ships only `nucleus.decorated-window-tao` as its window backend. At runtime, `nucleusApplication`
  must resolve to `NucleusBackend.Tao`.
- After Task 10, no code path may initialize the AWT toolkit, windows or event loop. That rules out `Toolkit`,
  `FileDialog`, `JFileChooser`, `Robot`, `KeyboardFocusManager`, `java.awt.dnd`, `Taskbar`, `Desktop`,
  `GraphicsEnvironment`, `SwingUtilities`, `ComposeWindow`, `FrameWindowScope` and `LocalAwtWindow`. Data classes
  are allowed: `java.awt.event.KeyEvent` constants, `java.awt.Rectangle`, `java.awt.datatransfer.StringSelection`,
  and `java.awt.Cursor` used through `PointerIcon`.
- Windows and Linux **degrade safely**. They must run, not crash, and keep every feature usable. List each gap in
  its PR. Hand testing is macOS only.
- **Release only at the end.** Middle PRs merge with CI green and no version bump. Bump and release once, in
  Task 11.
- If the Task 0 spike fails (video freezes, or the aspect lock fails during the drag), **stop and report to the
  user**. Do not fall back to a mixed AWT/Tao app.
- Device work: run `cdm acquire --category phone --android` before touching a device, and `cdm release --android`
  when done.
- Test command: `./gradlew :composeApp:jvmTest --tests '<fqcn>'`. Full suite: `./gradlew :composeApp:jvmTest`.

## Review Focus

1. **Keys while the quick actions sheet is focused.** Typing in the sheet goes to the sheet and never to the
   device. Release events for keys held when focus moved still reach the device (`sheetFocus.onGained`). The test
   goes in Task 4.
2. **Ctrl/Cmd+V paste with an empty or non-text clipboard.** The device gets nothing, the V press is consumed, and
   nothing throws. The test goes in Task 4.
3. **Pointer capture when the window loses focus mid-capture.** Capture releases, the cursor shows again, and held
   HID buttons release. The test goes in Task 6.
4. **A drop of zero files, or directories only, on a disconnected device.** The overlay shows the existing
   `dropHint` text, and no transfer starts. The test goes in Task 5.
5. **A mirror window straddling two screens, or on a secondary screen with a different scale.** Sheet growth and
   aspect max height use the window's own screen work area. The test goes in Task 2.

---

### Task 0: Throwaway go/no-go spike (macOS)

**Files (branch `spike/tao-mirror`, never merged):**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/spike/TaoSpike.kt`
- Modify: `composeApp/build.gradle.kts` (add the deps below, plus a `JavaExec` task `runTaoSpike` on
  `jvmRuntimeClasspath`, using the same JDK and `jvmArgs` as `nucleus.application`)
- Modify: `gradle/libs.versions.toml` (add `nucleus-application`, `nucleus-window-tao` and `nucleus-window-material3`,
  with the same coordinates as Claude Manager's `gradle/libs.versions.toml:17-19`)
- Create: `docs/superpowers/notes/2026-11-12-tao-spike.md`, committed to the **main work branch**, not the spike
  branch.

- [ ] **Step 1: Write `fun main()` in `TaoSpike.kt`.**
  It calls `startAppKoin()`, then `koin.get<MirrorController>().open(System.getenv("SPIKE_SERIAL"))`, then
  `nucleusApplication { MaterialDecoratedWindow(...) }`. Inside the window:
  - Draw `session.frames` letterboxed with a Compose `Canvas`, using `frame.use { Image.makeFromBitmap(it) }` and
    `drawIntoCanvas { it.nativeCanvas.drawImageRect(...) }`.
  - Show an overlay with the draws per second over the last 1 s, and with `session.fps`.
  - On macOS, set `NSWindow.contentAspectRatio` to the frame's aspect, adding the title-bar height to the content
    height. Use `ObjC.msgVoid(nsWindow, "setContentAspectRatio:", NSSize)` with the handle from
    `nucleusWindow.unsafe.taoWindow!!.getNsWindowHandle()`.
- [ ] **Step 2: Acquire a device.** Run `cdm acquire --category phone --android --real` and wait for it. Then
  `SPIKE_SERIAL=<serial> ./gradlew :composeApp:runTaoSpike`.
- [ ] **Step 3: Measure on macOS, dragging every edge and corner fast for 10 s.** Record:
  - (a) draws/s and `session.fps` during the drag compared with idle
  - (b) whether the frame aspect holds during the drag, with no snap on release
  - (c) whether there is an edge strip
  - (d) whether there is lag, stretch, flicker or stutter, compared side by side with Claude Manager
  - (e) whether Material3 `AlertDialog` opens under Tao
  - (f) whether `onPreviewKeyEvent` events carry `nativeKeyEvent is java.awt.event.KeyEvent`, for KEY_TYPED too
  - (g) whether `onResized` and `outerBoundsPx` units are physical pixels or points
- [ ] **Step 4: Write `docs/superpowers/notes/2026-11-12-tao-spike.md`.** Include results (a)–(g), and **GO**
  only if draws/s during the drag is at least 80% of idle and (b) holds. Release the device with
  `cdm release --android`.
- [ ] **Step 5: Report to the user and wait.** On NO-GO, stop the plan. On GO, record (f) and (g) in the note, since
  Tasks 4 and 10 depend on them, and get the user's go-ahead before Task 1.

---

### Task 1: Screen geometry without AWT

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/ScreenGeometry.kt`
- Modify: `ui/mirror/MirrorWindow.kt:749,759-760,965-967` (`maximumWindowBounds`, `screenRoom`)
- Modify: `ui/mirror/MirrorWindowMotion.kt:139,208-215` (`screenBounds`, `usableScreenBounds`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/window/ScreenGeometryTest.kt`

**Interfaces:**
- Produces:
  - `data class ScreenArea(val bounds: Rectangle, val workArea: Rectangle)`, in window units (AWT user space, which
    is points on macOS).
  - `interface ScreenGeometry { fun screens(): List<ScreenArea> }`.
  - `fun screenFor(window: Rectangle, screens: List<ScreenArea>): ScreenArea?`, which returns the screen with the
    largest overlap, or else the nearest one.
  - `fun flipMacRect(x: Double, y: Double, w: Double, h: Double, mainHeight: Double): Rectangle`.
  - `object NativeScreenGeometry : ScreenGeometry`. On macOS it uses `NSScreen.screens` `frame` / `visibleFrame`
    through `ObjC`. On Windows it uses JNA `EnumDisplayMonitors` + `GetMonitorInfo`. On Linux, `workArea = bounds`
    from the X11 root, or `emptyList()` (degrade).
  - `object AwtScreenGeometry : ScreenGeometry` is kept until Task 10 and selected through Koin.

- [ ] **Step 1: Write failing tests.**
  - `screenFor_picksLargestOverlap`: two screens `(0,0,1440,900)` and `(1440,0,1920,1080)`. A window at
    `(1300,100,400,400)` gets screen 2.
  - `screenFor_offscreenPicksNearest`.
  - `flipMacRect_convertsBottomLeftOrigin`: `flipMacRect(0.0, 25.0, 1440.0, 875.0, 900.0) == Rectangle(0,0,1440,875)`.
  - `screenRoom_usesWindowsOwnScreen` (Review Focus 5): a window on screen 2 measures the room against screen 2's
    work area.
- [ ] **Step 2: Run them.** `./gradlew :composeApp:jvmTest --tests '*ScreenGeometryTest'`. Expect FAIL (unresolved).
- [ ] **Step 3: Implement.** Change `screenRoom(window: java.awt.Window)` to
  `screenRoom(window: Rectangle, area: ScreenArea): Pair<Int, Int>`. Call sites get the area from
  `screenFor(...)`.
- [ ] **Step 4: Run the tests, plus `AspectLockTest`, `MirrorWindowMotionTest` and `SheetGrowthTest`.** Expect PASS.
- [ ] **Step 5: Commit.** `feat(window): screen geometry without AWT`

### Task 2: Mirror window handle seam

**Files:**
- Create: `ui/mirror/MirrorWindowHandle.kt`
- Modify: `ui/mirror/MirrorWindow.kt` (every `awtWindow.*` / `awtWindowRef` use at 516-520, 632-889, 911-912)
- Modify: `ui/mirror/MirrorWindowMotion.kt:164` (`LocalAwtWindow`), and `animateWindowBounds`
- Modify: `ui/window/MacVibrancy.kt:256,300,328`, so `animateMacFrame`, `setMacAlpha` and
  `disableMacAppearAnimation` take `nsWindow: Pointer`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/MirrorWindowHandleTest.kt`

**Interfaces:**
- Consumes: `ScreenArea`, `screenFor` (Task 1).
- Produces:
  ```kotlin
  interface MirrorWindowHandle {
      var bounds: Rectangle            // window units; setter moves + resizes atomically
      val size: IntSize                // window units
      val isShowing: Boolean
      val focused: StateFlow<Boolean>
      val nsWindow: Pointer?           // null off macOS
      fun toFront()
      fun screen(): ScreenArea?
  }
  class AwtMirrorWindowHandle(window: java.awt.Window, geometry: ScreenGeometry) : MirrorWindowHandle
  val LocalMirrorWindowHandle: ProvidableCompositionLocal<MirrorWindowHandle?>
  ```
  `focused` replaces the `WindowAdapter` focus listener (spec item 5).

- [ ] **Step 1: Write a failing test.** `FakeMirrorWindowHandle` with `sheetGrowth_setsBoundsPerFrame`: drive the
  sheet effect's bounds math through the handle and assert the `bounds` sequence equals `sheetFrameBounds` outputs.
  If the effect isn't reachable from a test, extract the per-frame loop into
  `internal suspend fun growForSheet(handle, base, from, to, spec, screenRight, onPin)` and test that.
- [ ] **Step 2: Run it.** Expect FAIL.
- [ ] **Step 3: Implement.**
  - Add `AwtMirrorWindowHandle`.
  - Replace every `awtWindow` read and write in `MirrorWindow.kt` with the handle.
  - Replace `lastWindowSize: Array<Dimension>` with `Array<IntSize>`.
- [ ] **Step 4: Run the full mirror tests.** `./gradlew :composeApp:jvmTest --tests 'com.programmersbox.scrcpyviewer.ui.mirror.*'`.
  Expect PASS.
- [ ] **Step 5: Hand check on macOS with a device (cdm).** Check the open/close zoom, sheet open/close/tab
  switch, and the aspect lock after a drag. Everything must behave as before.
- [ ] **Step 6: Commit.** `refactor(mirror): window handle seam in front of AWT`

### Task 3: Clipboard without the AWT toolkit

**Files:**
- Create: `ui/common/PcClipboard.kt`
- Modify: `di/AppModule.kt:45-46,183`, `ui/mirror/MirrorWindow.kt:659`, `ui/common/CopyButton.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/common/PcClipboardTest.kt`

**Interfaces:**
- Produces: `class PcClipboard { fun bind(clipboard: androidx.compose.ui.platform.Clipboard, scope: CoroutineScope); fun setText(text: String); suspend fun text(): String? }`.
  It is a Koin `single`. The main window binds `LocalClipboard.current`. `setText` before `bind` keeps the latest
  text and writes it on bind. It uses the same `ClipEntry(StringSelection(...))` pattern as `CopyButton.kt`.

- [ ] **Step 1: Write failing tests.** Use a fake `Clipboard`.
  - `setTextBeforeBind_isWrittenOnBind`.
  - `text_nonTextEntry_returnsNull`.
- [ ] **Step 2: Run them.** Expect FAIL.
- [ ] **Step 3: Implement.** Wire `AppModule` `setClipboard` to `PcClipboard.setText`.
- [ ] **Step 4: Run them.** Expect PASS.
- [ ] **Step 5: Commit.** `refactor: clipboard through Compose instead of the AWT toolkit`

### Task 4: Keyboard and focus through Compose

**Files:**
- Create: `ui/mirror/MirrorKeys.kt`
- Modify: `ui/mirror/MirrorWindow.kt:639-701` (`KeyEventDispatcher`, `WindowAdapter`, `KeyboardFocusManager`)
- Modify: `ui/window/AppWindow.kt` (pass `onPreviewKeyEvent` through to the window)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/MirrorKeysTest.kt`

**Interfaces:**
- Consumes: `PcClipboard.text()` (Task 3) and `MirrorWindowHandle.focused` (Task 2).
- Produces:
  - `class MirrorKeys(...)` holds today's dispatcher logic unchanged, with every dependency passed as a lambda or
    value.
  - `fun onKey(ev: java.awt.event.KeyEvent): Boolean`, where true means consumed.
  - `fun onFocusChanged(focused: Boolean)`.
  - The window calls it from `onPreviewKeyEvent = { (it.nativeKeyEvent as? AwtKeyEvent)?.let(keys::onKey) ?: false }`.
  - Paste reads the clipboard in a launched coroutine and sends when it arrives. The V press is consumed
    synchronously.

- [ ] **Step 1: Write failing tests,** building `KeyEvent`s with a `java.awt.Canvas()` source, as
  `SheetKeyFocusTest` does.
  - `sheetFocused_keysGoToSheetNotDevice` (Review Focus 1).
  - `focusMovesToSheet_heldHidKeysReleased` (Review Focus 1).
  - `paste_emptyClipboard_consumedNothingSent` (Review Focus 2).
  - `paste_text_sendsSetClipboardPaste`.
  - `zoomKeys_neverForwarded`.
  - `focusLost_releasesHidAndButtons`.
- [ ] **Step 2: Run them.** Expect FAIL.
- [ ] **Step 3: Implement.** Delete the `KeyEventDispatcher` / `WindowAdapter` code.
- [ ] **Step 4: Run `MirrorKeysTest`, `SheetKeyFocusTest`, `KeyRoutingTest`, `ZoomTest` and `InputMapperTest`.**
  Expect PASS.
- [ ] **Step 5: Hand check on macOS with a device.** Typing, HID keyboard, paste, zoom keys, and the sheet text
  field.
- [ ] **Step 6: Commit.** `refactor(mirror): device keys through onPreviewKeyEvent`

### Task 5: File drop through Compose drag-and-drop

**Files:**
- Modify: `ui/mirror/FileDrop.kt:196-258` (`CopyOnlyFileDropListener`, `FileDropTarget(window: Window, ...)`)
- Modify: `ui/mirror/MirrorWindow.kt:706`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/FileDropTest.kt`

**Interfaces:**
- Produces: `fun Modifier.fileDropTarget(transfers: FileTransfers): Modifier`, built on Compose
  `Modifier.dragAndDropTarget`. It accepts COPY only, reuses `dropHint` / `DropHintHolder` / `DropOverlay`, and is
  applied to the mirror's root `Box`. Paths come from the `DragAndDropEvent`'s files. `copyDropAction` stays as the
  pure decision.

- [ ] **Step 1: Write failing tests.**
  - `paths_fromDropEvent_extracted`, through an extracted `fun dropPaths(event): List<Path>?` with a fake event.
  - `noFiles_or_dirsOnly_disconnected_hintOnly_noTransfer` (Review Focus 4).
- [ ] **Step 2: Run them.** Expect FAIL.
- [ ] **Step 3: Implement.** Remove the `java.awt.dnd` code and `Component.descendants()`.
- [ ] **Step 4: Run `FileDropTest`.** Expect PASS.
- [ ] **Step 5: Hand check.** Drop an APK and a file on a mirror window. Both install or push, and the overlay
  motion is unchanged.
- [ ] **Step 6: Commit.** `refactor(mirror): file drop via Compose drag-and-drop`

### Task 6: Pointer capture without Robot

**Files:**
- Create: `ui/mirror/CursorControl.kt`
- Modify: `ui/mirror/PointerCapture.kt:13-18,72-140`, `ui/mirror/InputMapper.kt:72` (`clickCount`),
  `ui/mirror/MirrorWindow.kt:518-520`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/PointerCaptureTest.kt`,
  `ClickCounterTest.kt`

**Interfaces:**
- Consumes: `MirrorWindowHandle` (Task 2), which provides the content origin on screen as
  `bounds` + title-bar height.
- Produces:
  - `interface CursorControl { fun warp(screenX: Int, screenY: Int): Boolean; fun setHidden(hidden: Boolean) }`.
  - `object NativeCursorControl : CursorControl`:
    - macOS: `CGWarpMouseCursorPosition`, then `CGAssociateMouseAndMouseCursorPosition(1)`, plus
      `NSCursor hide` / `unhide`.
    - Windows: `SetCursorPos` and `ShowCursor`.
    - Linux X11: `XWarpPointer` through libX11.
    - Wayland, or when a call fails: `warp` returns false, and capture stays off with a snackbar (degrade).
  - `fun screenPoint(contentOrigin: IntOffset, local: Offset, density: Float): IntOffset`.
  - `class ClickCounter(intervalMs: Long = 500, slopPx: Float = 4f) { fun press(at: Offset, nowMs: Long): Int }`
    replaces `awtEventOrNull?.clickCount`.
  - `WindowPointerCapture.pointer(ev, window: MirrorWindowHandle, ...)` and its constructor take `CursorControl`
    instead of `Robot`.

- [ ] **Step 1: Write failing tests.**
  - `ClickCounterTest`: `secondPressWithin500msAnd4px_is2`, `farPress_resetsTo1`, `latePress_resetsTo1`.
  - `PointerCaptureTest`: `deltasFromScreenPoint_matchAwtPath` uses the same moves as the existing AWT-based
    cases.
  - `focusLostMidCapture_releasesShowsCursorReleasesButtons` (Review Focus 3).
  - `warpFails_captureStaysOff`.
- [ ] **Step 2: Run them.** Expect FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `PointerCaptureTest`, `ClickCounterTest`, `InputMapperTest` and `HidMouseTest`.** Expect PASS.
- [ ] **Step 5: Hand check on macOS with a device in `mouse=uhid` mode.** Capture, move, release with the key,
  and focus loss.
- [ ] **Step 6: Commit.** `refactor(mirror): pointer capture without java.awt.Robot`

### Task 7: Native file pickers

**Files:**
- Create: `ui/common/NativeFileDialogs.kt`
- Modify: `ui/mirror/FileDrop.kt:76-104`, `ui/presets/PresetFileDialogs.kt:19-32`,
  `ui/settings/SettingsFileDialogs.kt:21-51`, and `ui/App.kt:63-66,202-204,244`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/common/NativeFileDialogsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface NativeFileDialogs {
      fun open(title: String, extensions: List<String>, multiple: Boolean, directories: Boolean = false, initial: Path? = null): List<Path>
      fun save(title: String, suggestedName: String, extension: String): Path?
  }
  ```
  - The seams `TransferFileDialogs`, `PresetFileDialogs` and `SettingsFileDialogs` keep their method names. Their
    `parent: Frame?` parameters are dropped.
  - New implementations: `NativeTransferFileDialogs`, `NativePresetFileDialogs` and `NativeSettingsFileDialogs`.
  - **Choice rule:** first try `io.github.vinceglb:filekit-dialogs` at its latest version at least two weeks
    old. Run the app with `-verbose:class` and check for `java.awt.Toolkit`. If FileKit loads the toolkit on macOS,
    write `NativeFileDialogs` directly:
    - macOS: `NSOpenPanel` / `NSSavePanel` via `ObjC`, run modal on the main thread with `ObjC.onMain`.
    - Windows: JNA `GetOpenFileNameW` / `GetSaveFileNameW` (comdlg32).
    - Linux: `zenity`, then `kdialog`, through `ProcessBuilder`. If neither is present, return empty and show a
      snackbar (degrade).

- [ ] **Step 1: Write failing tests** for the pure parts:
  - `extensionsFilter_apk`.
  - `suggestedName_getsExtension`: `exportFileName("My Preset")` plus `.json`, matching the current
    `PresetFileDialogs` behavior.
  - `linuxPickerCommand_zenityThenKdialog`.
- [ ] **Step 2: Run them.** Expect FAIL.
- [ ] **Step 3: Implement,** and swap the Koin/App wiring to the Native* implementations.
- [ ] **Step 4: Run them.** Expect PASS.
- [ ] **Step 5: Hand check on macOS.** Install APK, push file, preset export/import, action export/import, the adb
  path picker, and the capture directory picker.
- [ ] **Step 6: Commit.** `refactor: native file pickers instead of FileDialog/JFileChooser`

### Task 8: App shell without AWT

**Files:**
- Modify: `AppIcon.kt:18-21`, `AppShutdown.kt:48-51`, `Main.kt:51,66,88,108-113`, `ui/theme/Styles.kt:254`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/AppShutdownTest.kt` (extend)

**Interfaces:**
- Produces:
  - `AppShutdown.installQuitHandler(onQuit)` keeps its signature. Its body moves to a `QuitHook` chosen per backend
    in Task 10. Until then it stays AWT.
  - `fun fontAvailable(family: String): Boolean`, through `org.jetbrains.skia.FontMgr.default.matchFamily(family).count() > 0`.
  - `bringToFront(window: NucleusWindow)` lands in Task 9. Here, only `fontAvailable` and the `QuitHook` seam.

- [ ] **Step 1: Write failing tests.**
  - `fontAvailable_unknownFamily_false`.
  - `quitHook_invokesRequestOnce`: the existing `AppShutdown` contract, so a double quit runs shutdown once.
- [ ] **Step 2: Run them.** Expect FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expect PASS.
- [ ] **Step 5: Commit.** `refactor: app shell seams (quit hook, font check)`

### Task 9: Nucleus Material windows on the AWT backend; Jewel removed

**Files:**
- Modify: `gradle/libs.versions.toml` and `composeApp/build.gradle.kts`:
  - Add `nucleus-application`, `nucleus-window-awt` (`dev.nucleusframework:nucleus.decorated-window-awt`) and
    `nucleus-window-material3`.
  - Remove `jewel-decorated-window` and the coroutines-fork substitution at lines 93-100.
- Modify: `Main.kt` (`application` → `nucleusApplication`; `Window` → `MaterialDecoratedWindow` for
  FinishingRecording), `ui/window/AppWindow.kt` (rewrite), `ui/window/WindowChrome.kt`, `ui/mirror/MirrorWindow.kt:613`,
  `ui/settings/SettingsControls.kt:61`, `ui/settings/SettingsViewModel.kt:197`, `state/AppSettings.kt:25`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/window/WindowChromeTest.kt` (update or delete)

**Interfaces:**
- Produces:
  - `NucleusApplicationScope.AppWindow(onCloseRequest, title, theme, state, visible, alwaysOnTop, icon, onPreviewKeyEvent, titleBar: @Composable AppTitleBarScope.() -> Unit, content: @Composable NucleusDecoratedWindowScope.() -> Unit)`,
    built on `MaterialDecoratedWindow` + `MaterialTitleBar`. It keeps the theme reveal, animated bar colors, and
    the Neon title.
  - `AppTitleBarScope.interactive(key)` becomes a no-op, since Nucleus title-bar children are interactive.
  - `MirrorWindowHandle` gets its AWT window from `nucleusWindow.unsafe.awtWindow`.
  - `bringToFront(window: NucleusWindow)` uses `setMinimized(false)`, `toFront()` and `requestFocus()`, and
    replaces `Main.kt:108-113`.
- **Decision (Claude's call, per the spec):** retire "Use system title bar". `MaterialDecoratedWindow` has no
  native-title mode. Hide the Settings control and ignore the stored value, but keep the `AppSettings` field so
  old settings files still parse. Note the removal in the PR.

- [ ] **Step 1: Update `WindowChromeTest`** for the retired setting (`useSystemTitleBar` ignored). Run it and
  expect FAIL.
- [ ] **Step 2: Implement.** Run `./gradlew :composeApp:jvmTest`. Expect PASS.
- [ ] **Step 3: Hand check on macOS:** `./gradlew :composeApp:run`. Check the main and mirror title bars, the
  theme reveal, Glass blur, the open/close zoom, the sheet, keys, drop, the pickers, and Cmd+Q with a recording in
  progress.
- [ ] **Step 4: Commit.** `feat(window): Nucleus Material windows; drop Jewel`

### Task 10: Switch to the Tao backend

**Files:**
- Modify: `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`:
  - `nucleus-window-awt` → `nucleus-window-tao`.
  - Remove the JBR 25 `javaHome` block at lines 113-124, unless the Task 0 note shows Tao needs it.
- Create: `ui/mirror/TaoMirrorWindowHandle.kt`, `ui/window/TaoScreenGeometry.kt` (if Tao's `TaoScreenGeometry`
  covers the work area, wrap it; otherwise keep `NativeScreenGeometry`), and
  `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/NoAwtToolkitTest.kt`
- Modify: `ui/window/MacVibrancy.kt` (target `nsWindow: Pointer`; delete `findSkiaLayer`, `setWindowBackground`
  and `WindowBackgroundEffect`), `ui/window/AppWindow.kt`, `ui/mirror/MirrorWindow.kt` (aspect lock), `Main.kt`,
  `AppIcon.kt`, `AppShutdown.kt`, and `di/AppModule.kt` (Koin picks the Tao / Native implementations)

**Interfaces:**
- Consumes: every seam from Tasks 1–9, plus the Task 0 note's results (f) and (g).
- Produces:
  - `TaoMirrorWindowHandle(nucleusWindow: NucleusWindow)`. `bounds` goes through `setOuterPosition` +
    `setInnerSize` and `outerBoundsPx`, divided by `scaleFactor` if (g) says pixels. `focused` is
    `nucleusWindow.focusFlow`, and `nsWindow` is `getNsWindowHandle()`.
  - **Live aspect lock on macOS:** set `NSWindow.contentAspectRatio` as proven in the spike, updated on each frame
    size or Fit change, and cleared while the sheet animates or when Fit is off. The existing settle correction
    (`ASPECT_SETTLE_MS`) stays as the Windows/Linux path (degrade: corrects on release).
  - Glass: windows are created with `transparent = true` when the shown theme is Glass + desktop blur. Because that
    is creation-time only, a style change applies to windows opened afterwards. Note this in the PR.
  - `QuitHook` on Tao: the app-quit hook that `TaoApplication` / `NucleusApplicationScope` exposes. If none exists,
    Cmd+Q in each window's `onPreviewKeyEvent`, plus `onCloseRequest`, routes to `shutdown.request()`.
  - The dock icon is left to packaging; delete `installTaskbarIcon`.
  - `NoAwtToolkitTest` scans `composeApp/src/jvmMain` for the banned imports listed under Global Constraints.
    The allowlist holds only data classes: `java.awt.event.KeyEvent`, `java.awt.Rectangle`,
    `java.awt.datatransfer.StringSelection` and `java.awt.datatransfer.DataFlavor`, plus `java.awt.Cursor`.

- [ ] **Step 1: Write `NoAwtToolkitTest`.** Run it and expect FAIL, listing the remaining AWT users.
- [ ] **Step 2: Implement until it passes.** Check each `Dialog` / `Popup` call site (spec item 12) under Tao, and
  move any that fails to `MaterialDecoratedDialog`.
- [ ] **Step 3: Run `./gradlew :composeApp:jvmTest`.** Expect PASS.
- [ ] **Step 4: Run with `-verbose:class` and grep for `java.awt.Toolkit` / `sun.lwawt`.** Expect no AWT toolkit
  class loaded after a session that opens a mirror, a picker and a dialog.
- [ ] **Step 5: Push and open a draft PR.** CI must be green on macOS, Windows and Linux, tests and packaging. List
  the Windows/Linux gaps in the PR body.
- [ ] **Step 6: Commit.** `feat(window): run on the Nucleus Tao backend`

### Task 11: macOS hand pass and release

- [ ] **Step 1: Acquire a device with cdm.** Walk through every "Must not regress" bullet in the spec on macOS.
  Then run the spec's success check side by side with Claude Manager: drag every edge and corner of the main and
  mirror windows, and drag fast for 10 s with video playing.
- [ ] **Step 2: Fix anything found,** with a test for each fix.
- [ ] **Step 3: Ask the user for the version bump and release.** Expected is a minor bump, 1.15.0 → 1.16.0. Then
  bump, merge, tag, and watch the release run, following the established flow.
- [ ] **Step 4: Run `cdm release --android`.**
