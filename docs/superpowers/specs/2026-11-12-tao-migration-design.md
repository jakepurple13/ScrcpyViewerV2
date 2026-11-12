# Move Scrcpy Vwr to the Nucleus Tao window backend

Date: 2026-11-12. Status: draft for approval.

## Why

Resizing any Scrcpy Vwr window feels bad. While dragging an edge the user sees all four symptoms: content lagging
behind the edge, stretching, flicker, and the whole drag stuttering. A strip of window color also shows at the
dragged edge. `WindowBackgroundEffect` (`ui/window/MacVibrancy.kt:243`) only hides that strip by painting the AWT
window in the surface color.

Claude Manager (`~/Downloads/claude_manager`, same author, same Nucleus 2.5.18) resizes smoothly. The difference
is the window backend. Claude Manager runs `nucleusApplication { MaterialDecoratedWindow { … } }` with
`nucleus.decorated-window-tao`. That is a native (Rust `tao`) window with its own Metal / OpenGL / D3D renderer and
no AWT at all. Scrcpy Vwr runs Compose's AWT `application { }` with Jewel's `DecoratedWindow`, and AWT live resize
is the slow path.

## Goals

- Every window resizes like Claude Manager's: no lag, stretching, flicker or stutter, and no colored edge strip.
- All windows move: main, mirror (one per device), and "Finishing recording…".
- The process runs with no AWT event loop: `nucleusApplication` resolves to `NucleusBackend.Tao`.
- `WindowBackgroundEffect` and the strip workaround are deleted once the strip is confirmed gone.

## Must not regress

The user named these two:

1. **Mirror aspect lock during resize.** The mirror window keeps the phone's aspect ratio while the user drags.
   The existing correctness rules hold: it never drifts, and it stays right during sheet animations (see commit
   `94e0453`).
2. **Live video during resize.** Video keeps playing while the user drags. It must not freeze or blank.

Agreed during the conversation, and must work as they do today:

- Keyboard and mouse forwarding to the phone, including HID keyboard and mouse, key routing, and zoom keys.
- Pointer capture mode: relative mouse with a hidden, recentred cursor.
- Dropping files and APKs on a mirror window (copy only).
- File pickers: install APK, push file, preset and action import/export, adb path, and the capture directory.
- The quick actions sheet growing and shrinking the mirror window (`SheetGrowth`, `sheetFrameBounds`).
- Mirror open and close animations (`MirrorWindowMotion`, the `window-animation` setting).
- Glass style's desktop blur on macOS (`MacVibrancyEffect`).
- Theme reveal, the title bar colors following the theme, and the Neon title glow.
- Clipboard: copy buttons, PC-to-phone paste, and phone-to-PC clipboard sync.
- Cmd+Q and app-quit handling through `AppShutdown`: recordings still finish before exit.
- The dock/taskbar icon, and "bring main window to front" from a mirror's Actions request.
- In-app dialogs and popups: update, onboarding, pairing, template picker, icon picker, and action import.
- Update install after shutdown (`updateManager.installPending()`).
- macOS, Windows and Linux all keep working. CI's OS-matrix tests and packaging stay green.

## Non-goals

- No new features, and no visual redesign beyond the title bar change below.
- No change to the video decode pipeline (ffmpeg to Compose `ImageBitmap`). Tao's YUV / texture views are out of
  scope.
- No mirror sizing behavior changes beyond what the new backend requires.

## Anti-goals (failure even if it works)

- The drag is smooth, but the mirror's aspect ratio wobbles, drifts, or snaps after release.
- The drag is smooth, but video freezes until release.
- AWT still initializes anywhere: through `Toolkit`, `FileDialog`, `Robot`, `Desktop`, `Taskbar` or
  `GraphicsEnvironment`. Nucleus warns this is deadlock-prone on macOS under Tao.

## Decisions

- **Title bar (user approved):** Jewel's `DecoratedWindow` / `TitleBar` goes. Nucleus's
  `MaterialDecoratedWindow` / `MaterialTitleBar` (`nucleus.decorated-window-material3`) replaces it, as in Claude
  Manager. The title bar may look different from today. Jewel and its coroutines-fork substitution in
  `composeApp/build.gradle.kts` are removed.
- **Backend:** add `nucleus.nucleus-application` and `nucleus.decorated-window-tao`, plus
  `nucleus.decorated-window-material3`. The entry point becomes `nucleusApplication(...)`. Only the Tao backend
  module ships.
- **Spike first, and stop if it fails (user decided).** Before Jewel or JBR are removed, build a throwaway
  go/no-go spike: a Tao mirror window showing live video, with an aspect lock that holds during the drag, on
  macOS. Claude Manager has no video, so nothing yet shows that Tao keeps drawing frames during macOS live resize.
  If the spike shows video freezing or the aspect lock failing during the drag, **stop and report back** to the
  user. Do not migrate further, and do not fall back to a mixed AWT/Tao app. The spike also checks whether the
  edge strip is gone.
- **Windows and Linux degrade safely (user decided).** On those platforms the app must run without crashing and
  keep every feature usable. Small gaps are acceptable: the aspect corrects on release rather than during the drag,
  pointer capture is limited or off on Wayland, and so on. Each gap is listed in the PR. Hand testing is
  macOS only.
- **Release only at the end (user decided).** Middle PRs may merge without a release. A middle step may run on
  Nucleus's AWT backend (`nucleus.decorated-window-awt`) with the Material title bar before the switch to Tao.
  One version bump and release happen once everything runs on Tao.

## What the investigation found

These facts come from the Nucleus 2.5.18 jars in the Gradle cache.

**Inherited from Nucleus:**

- Tao builds real `java.awt.event.KeyEvent` objects (`TaoSyntheticKeyKt`). That means
  `event.nativeKeyEvent as? AwtKeyEvent` in `InputMapper.kt:207` probably keeps working, and the AWT-keycode
  mapping in `InputMapper` / `HidKeyboard` / `KeyRouting` / `Zoom` can stay. Using `java.awt.event.KeyEvent`,
  `java.awt.Rectangle` and similar classes as plain data is fine. Only AWT *windows / toolkit* are the problem.
- Tao maps `java.awt.Cursor` types to native cursors (`TaoCursorMappingKt`), so `PointerIcon(Cursor(...))` probably
  keeps working.
- Material3 `AlertDialog` works under Tao in Claude Manager.

**Native escape hatches:**

- `NucleusWindow` offers bounds, show/hide/toFront/focus, minimize/maximize, `focusFlow`, and
  `unsafe.taoWindow`.
- `TaoWindow` offers `onResized`, `onMoved`, `onSizeMoveChanged`, `setInnerSize`, `setOuterPosition`,
  `outerBoundsPx`, `getNsWindowHandle`, `getNativeHandle`, and `getX11WindowId`.
- The existing JNA / ObjC code in `MacVibrancy.kt` can target the `NSWindow` from `getNsWindowHandle()` instead of
  the AWT handle.

**No Tao equivalent found:**

- A file chooser.
- Cursor warping (AWT `Robot`).
- Screen insets and work area (`Toolkit.getScreenInsets`, `GraphicsEnvironment.maximumWindowBounds`).
- `Taskbar` and `Desktop.APP_QUIT_HANDLER`.
- Window aspect-ratio constraint.
- A global key hook like `KeyboardFocusManager`.

A full inventory of AWT use is below, grouped by feature.

## Work by feature

The builder decides the "how" of each item below. Constraints are stated.

1. **App root and windows.** `Main.kt` moves to `nucleusApplication`. `AppWindow` moves to Nucleus decorated
   windows, and so do `MirrorWindow` and `FinishingRecordingWindow`. `FrameWindowScope` / `ComposeWindow` /
   `LocalAwtWindow` give way to `NucleusWindow` / `TaoWindow`. Each Tao window gets the composition locals it
   needs, because Tao windows don't inherit them (see Claude Manager `SessionWindowHost.kt:191`).
2. **Mirror sizing and aspect lock** (`MirrorWindow.kt:731-889`). It now reads `awtWindow.size` /
   `bounds` / `graphicsConfiguration`. Move it to `TaoWindow` bounds and events. The lock must hold *during* the
   drag, not only correct after it. On macOS that most likely means `NSWindow.contentAspectRatio` (or
   `windowWillResize:toSize:`) through the `NSWindow` handle. Windows and Linux need their own approach. Screen
   work-area and insets need a non-AWT source: the native API per OS, or Tao's `TaoScreenGeometry` if it fits.
3. **Sheet growth and open/close animation.** Replace `awtWindow.bounds = …` and `animateWindowBounds` with
   `setInnerSize` / `setOuterPosition` or the native frame. On macOS, `animateMacFrame`, `setMacAlpha` and
   `disableMacAppearAnimation` keep working through the Tao `NSWindow` handle. The `sheetFrameBounds` /
   `SheetGrowth` logic and its tests stay. Only the I/O changes.
4. **Keyboard forwarding.** The `KeyEventDispatcher` on `KeyboardFocusManager` (`MirrorWindow.kt:639-701`) must
   become the mirror window's `onPreviewKeyEvent`. Each window then sees only its own keys, so
   `SwingUtilities.getWindowAncestor` scoping goes away. Clipboard paste in that path (`MirrorWindow.kt:659`) moves
   to a non-AWT clipboard.
5. **Window focus.** `addWindowFocusListener` / `isFocused` become `NucleusWindow.focusFlow` / `isFocused`.
6. **Pointer capture.** Replace `Robot` warping and the blank `Toolkit.createCustomCursor` with native cursor
   warp and hide: macOS `CGWarpMouseCursorPosition` / `CGAssociateMouseAndMouseCursorPosition` / `NSCursor hide`,
   Windows `SetCursorPos` / `ShowCursor`, Linux X11 `XWarpPointer`. Replace screen-space deltas from
   `awtEventOrNull` with ones that don't depend on AWT `MouseEvent`. Double-click count
   (`InputMapper.kt:72`) also needs a source other than `awtEventOrNull`.
7. **File drop.** Replace `java.awt.dnd.DropTarget` with Tao's drag and drop (`TaoDragAndDropManager` /
   `TaoFilesTransferable`, surfaced through Compose `dragAndDropTarget`). It stays copy only, and the drop overlay
   motion is unchanged.
8. **File pickers.** Replace every `FileDialog` / `JFileChooser` (`FileDrop.kt`, `PresetFileDialogs.kt`,
   `SettingsFileDialogs.kt`) with native pickers that don't touch AWT. The existing seams
   (`TransferFileDialogs`, `PresetFileDialogs`, `SettingsFileDialogs`) stay, so only the Awt* implementations
   change. The builder picks the library or writes JNA calls.
9. **Clipboard.** Replace `Toolkit.systemClipboard` (`di/AppModule.kt:183`, `MirrorWindow.kt:659`,
   `CopyButton.kt`) with Compose `LocalClipboard`, as provided by Tao, or a native call where a composable isn't
   available.
10. **Vibrancy and window background.** Point `MacVibrancyEffect` at the Tao `NSWindow`. Glass may also need the
    window created with `transparent = true`. Delete `WindowBackgroundEffect` after confirming the strip is gone.
11. **App shell bits.** Replace `Taskbar` icon, `Desktop.APP_QUIT_HANDLER`, `bringToFront(java.awt.Frame)`,
    `SwingUtilities.invokeLater`, and the `GraphicsEnvironment` font check (`Styles.kt:254`) with Nucleus / Tao /
    native equivalents. Cmd+Q must still go through `AppShutdown.request`.
12. **Dialogs and popups.** Check each `androidx.compose.ui.window.Dialog` / `Popup` call site under Tao. Move any
    that open an AWT window to Nucleus `DecoratedDialog` or an in-window overlay.
13. **Runtime.** Claude Manager runs without JBR. JBR 25 is pinned only for Jewel's title bar
    (`composeApp/build.gradle.kts:113-124`), so drop it if nothing else needs it. Keep the `jvmArgs`.
14. **Tests.** 9 jvmTest files import AWT, most as `KeyEvent` constants, which is fine. Screenshot and scene support
    use `SwingUtilities` (`SceneTestSupport.kt`, `ScreenshotSupport.kt`). Keep them if they don't start a window.
    `FileDropTest` changes with item 7. Add tests for new pure logic: aspect math and delta math.

## Constraints

- Nucleus 2.5.18, the same as now and as Claude Manager. Compose Multiplatform 1.12.2, Kotlin 2.3.21.
- Ship the work in reviewable steps. Each step leaves the app runnable on all three OSes.
- The user's established flow continues: PR and CI green on the OS matrix for each step, then one version bump
  and release at the end.
- Claude's calls: Wayland is best effort under "degrade safely", and the spike runs on macOS only.

## Success check

- Side by side with Claude Manager on macOS: drag every edge and corner of the main and mirror windows. Look for
  no lag, stretch, flicker, stutter or edge strip.
- With a phone mirroring, drag fast for 10 s. Video keeps playing, the aspect ratio stays locked throughout, and
  nothing snaps on release.
- Run through every "must not regress" item by hand on macOS. CI covers Windows and Linux tests and packaging.
- The full test suite passes.

## Left to the builder

- The order of steps and how to split them into PRs.
- Per-OS native APIs for aspect lock, cursor warp, and screen work area.
- The file-picker approach.
- Whether the "Use system title bar" setting (`useSystemTitleBar`, `WindowChrome.System`) survives: keep it if
  Nucleus can give a native-decorated Tao window, otherwise retire it.
