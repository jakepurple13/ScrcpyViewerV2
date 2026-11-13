# Launch and Quit Animations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The main window opens and quits with a user-chosen animation (Icon unfold by default, Screen on, or Off), drawn
in a short-lived see-through overlay window on macOS, Windows, and Linux.

**Architecture:** A pure motion model maps elapsed milliseconds to an `OverlayFrame` for each animation. A pure policy
decides per launch/quit whether to use the overlay, a 150 ms native fade, or nothing (setting, platform switch, reduce
motion, Glass blur). A Compose overlay window (Nucleus `DecoratedWindow`, transparent, made click-through and
non-focusable through its `TaoWindow`) draws the frame over a picture of the main window. `Main.kt` hands off between the
overlay and the real window; `AppShutdown` runs the quit animation after sessions close and before `install`.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, Nucleus 2.5.18 on Tao (`nucleus.decorated-window-tao`),
kotlinx.serialization, kotlin.test, JNA (existing `ObjC` helper).

**Spec:** `docs/superpowers/specs/2026-11-13-launch-quit-animation-design.md`

## Global Constraints

- Setting: `Settings.launchQuitAnimation: String = "IconUnfold"`; values `IconUnfold`, `ScreenOn`, `Off`; unknown → `IconUnfold`.
- Settings row: Appearance category, last entry, id `launch-quit-animation`, title "Launch & quit animation", supporting
  text "Plays when Scrcpy Vwr opens and quits.", segmented labels "Icon unfold", "Screen on", "Off".
- Launch durations: Icon unfold 720 ms, Screen on 650 ms. Quit: Icon unfold 420 ms, Screen on 380 ms. Fade 150 ms.
- Quit animation is bounded by a 1 000 ms timeout; failure or timeout never blocks exit.
- Curves: emphasized `0.2,0,0,1`; decelerate `0.05,0.7,0.1,1`; accelerate `0.3,0,0.8,0.15`; sweep `0.4,0,0.2,1`;
  CRT `0.5,0,0.75,0`.
- Overlay platform switches: macOS on; Windows, Linux X11, Linux Wayland off until the user confirms the spike there.
- Glass with desktop blur (macOS) always uses the fade.
- Off, and the fade path, keep today's quit flow (window hidden at request, `FinishingRecordingWindow`).
- The overlay never takes focus, never receives clicks, and never shows in Dock/taskbar/app switcher.
- Main window behaviour outside launch/quit is unchanged (resize, snap, shadow, Glass, focus).
- Tests: `./gradlew :composeApp:jvmTest`; CI runs it on macOS, Windows, Ubuntu (xvfb).

## Review Focus

- **Quit pressed twice, or Cmd+Q during the launch animation:** a quit during launch cancels the launch overlay, shows
  the real window at once, then runs quit normally; a second quit is ignored (existing `request()` guard). Test in Task 6.
- **Main window on a second monitor or a HiDPI screen:** the overlay covers exactly the main window's outer bounds on
  the same screen at the same scale. Checked by hand in Task 7; bounds math tested in Task 4.
- **Maximized or full-screen main window at quit:** full screen uses the fade (an overlay cannot sit over a macOS
  full-screen Space); maximized uses the overlay at the maximized bounds. Test in Task 3.
- **Update pending at quit:** `install` still runs after the animation and the total added delay is at most the
  animation length. Test in Task 6.
- **Setting changed in this session:** launch used the old value; quit reads the current value. Test in Task 6.

---

### Task 0: Spike: see-through overlay on each OS (throwaway)

Not product code. Lives in `.superpowers/spike/launch-overlay/` (git-ignored) as a second `main` in a scratch source
file the implementer runs with a temporary Gradle `JavaExec` task, or as a temporary file in `jvmMain` that is deleted
before Task 1 is committed. No tests.

**Files:**
- Create: `.superpowers/spike/launch-overlay/OverlaySpike.kt` (plus any temporary Gradle wiring, reverted after)
- Create: `docs/superpowers/notes/2026-11-13-launch-overlay-spike.md`

- [ ] **Step 1: Build the spike app.** A `nucleusApplication` with one ordinary main window (`AppWindow`, hidden at
  start) and one overlay: `DecoratedWindow(transparent = true, resizable = false, ...)` with no title bar content, at the
  main window's bounds. Through `nucleusWindow.unsafe.taoWindow` call `setIgnoreCursorEvents(true)`,
  `setFocusable(false)`, `setAlwaysOnTop(true)`. The overlay draws a teal rounded rect growing from 20% to 100% of its
  size over 700 ms on a transparent background. At the end: `mainWindow.show()`, wait for the main window's first frame
  (`onRedrawRequested` / a `withFrameNanos` in its content), then close the overlay.
- [ ] **Step 2: Add a quit path.** A button in the main window: take a `GraphicsLayer` picture of the main window's
  root content, open the overlay showing it, hide the main window on the overlay's first frame, squash the picture to a
  line over 380 ms, exit.
- [ ] **Step 3: Check taskbar hiding.** Find how a Tao window is kept out of the taskbar/Dock/Alt-Tab on each OS (Tao
  constructor flags, `NSWindow` collection behaviour / `NSWindowStyleMaskNonactivatingPanel` on macOS, `WS_EX_TOOLWINDOW`
  on Windows, `_NET_WM_WINDOW_TYPE_UTILITY` or skip-taskbar hint on X11). Record what works.
- [ ] **Step 4: Run on macOS here and record** see-through (desktop visible around the rect), click-through (clicking
  through the overlay hits the window below), no focus steal, no Dock entry, hand-off without flicker in either
  direction, correct position on a second display if one is attached.
- [ ] **Step 5: Write the notes** to `docs/superpowers/notes/2026-11-13-launch-overlay-spike.md`: per platform
  (macOS, Windows, Linux X11, Linux Wayland) a row of the five checks, how each was achieved, and the run command.
- [ ] **Step 6: STOP for the user.** Ask the user to run the spike on Windows and on Linux (X11 and Wayland sessions)
  and report the five checks. Proceed to Task 1 after macOS passes; the Windows/Linux answers only decide the switches
  in Task 3 and can arrive later.
- [ ] **Step 7: Commit the notes only.**

```bash
git add docs/superpowers/notes/2026-11-13-launch-overlay-spike.md
git commit -m "docs: launch overlay spike notes"
```

### Task 1: The setting

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/state/LaunchQuitAnimation.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/state/AppSettings.kt` (add field after `animateMirrorWindows`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsViewModel.kt` (setter next to `setAnimateMirrorWindows`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsRegistry.kt` (action + entry)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsScreen.kt` (bind action)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsControls.kt` (control)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/state/AppSettingsTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsViewModelTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsRegistryTest.kt`

**Interfaces:**
- Produces: `enum class LaunchQuitAnimation { IconUnfold, ScreenOn, Off; companion object { fun fromName(name: String?): LaunchQuitAnimation } }`;
  `Settings.launchQuitAnimation: String`; `val Settings.launchQuit: LaunchQuitAnimation` (extension);
  `SettingsViewModel.setLaunchQuitAnimation(value: LaunchQuitAnimation)`;
  `SettingsActions.onLaunchQuitAnimation: (LaunchQuitAnimation) -> Unit`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// AppSettingsTest
@Test fun `launch quit animation defaults to icon unfold and falls back on unknown names`() {
    assertEquals(LaunchQuitAnimation.IconUnfold, Settings().launchQuit)
    assertEquals(LaunchQuitAnimation.IconUnfold, LaunchQuitAnimation.fromName("Sparkles"))
    assertEquals(LaunchQuitAnimation.IconUnfold, LaunchQuitAnimation.fromName(null))
    assertEquals(LaunchQuitAnimation.ScreenOn, LaunchQuitAnimation.fromName("ScreenOn"))
}
@Test fun `launch quit animation persists`() {
    // same temp-file pattern as `settings persist and default when file missing or corrupt`
    // update to ScreenOn, reload AppSettings from the file, assert launchQuit == ScreenOn
}
// SettingsViewModelTest
@Test fun `setLaunchQuitAnimation stores the name`() { /* vm.setLaunchQuitAnimation(Off); assertEquals("Off", settings.value.launchQuitAnimation) */ }
// SettingsRegistryTest
@Test fun `launch quit animation is the last appearance entry`() {
    // the Appearance category's last entry has id "launch-quit-animation" and title "Launch & quit animation"
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :composeApp:jvmTest --tests '*AppSettingsTest*' --tests '*SettingsViewModelTest*' --tests '*SettingsRegistryTest*'`
Expected: compile failure on `LaunchQuitAnimation`.

- [ ] **Step 3: Implement** the enum, field, extension, setter, action, and entry. Control
  `LaunchQuitAnimationControl(b: SettingsBindings)` in `SettingsControls.kt`: a `SingleChoiceSegmentedButtonRow` with the
  three labels from Global Constraints, styled like `StylePicker.kt:93`. Keywords:
  `listOf("animation", "motion", "launch", "startup", "quit", "shutdown", "splash", "open", "close")`.
- [ ] **Step 4: Run to verify they pass** (same command). Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add composeApp/src
git commit -m "feat(settings): launch & quit animation choice"
```

### Task 2: Motion model

Pure functions, no Compose UI. Times in ms from the animation start.

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/LaunchMotion.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/LaunchMotionTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class LaunchPhase { Launch, Quit }
/** Fractions are of the main window's bounds (0..1). Alphas 0..1. */
data class OverlayFrame(
    val windowClip: FractionRect,        // where the window picture is visible
    val windowCorner: Float,             // corner radius in dp
    val windowAlpha: Float,
    val windowScaleX: Float, val windowScaleY: Float, // about the window centre (Screen on quit)
    val windowBrightness: Float,         // 1 = unchanged
    val iconScale: Float, val iconAlpha: Float, val iconOffsetY: Float, // icon centred on the window; offset in window-height fractions
    val backPhoneProgress: Float,        // 0 = stacked behind, 1 = slid out
    val screenFlash: Float,              // 0..1 teal on the icon's phone screen
    val beamY: Float?, val beamAlpha: Float, // Screen on launch sweep, fraction of height
    val showRealWindow: Boolean,         // true on the last frame: hand-off may happen
)
data class FractionRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
object LaunchMotion {
    fun durationMs(anim: LaunchQuitAnimation, phase: LaunchPhase): Int
    fun frame(anim: LaunchQuitAnimation, phase: LaunchPhase, tMs: Int): OverlayFrame
    /** The icon's phone-screen rect in window fractions: where Icon unfold grows from and folds into. */
    val PhoneScreen: FractionRect // = FractionRect(0.44f, 0.30f, 0.56f, 0.75f), from the mockup's inset(30% 44% 25% 44%)
}
```

- [ ] **Step 1: Write the failing tests** (`LaunchMotionTest`), one per row:

```kotlin
@Test fun durations() { // 720, 650, 420, 380 for IconUnfold/ScreenOn × Launch/Quit; Off throws IllegalArgumentException
}
@Test fun `icon unfold launch keyframes`() {
    // t=0: iconScale 0.6, iconAlpha 0, windowAlpha 0
    // t=260: iconScale 1, iconAlpha 1
    // t=360: windowClip == PhoneScreen
    // t=700: windowClip == FractionRect(0,0,1,1), windowAlpha 1, iconAlpha 0
    // t=720: showRealWindow
}
@Test fun `icon unfold quit keyframes`() {
    // t=0: windowClip full, windowAlpha 1; t=260: windowClip == PhoneScreen, windowAlpha 0
    // t=420: iconAlpha 0, iconScale <= 0.25, iconOffsetY > 0 (moved down)
}
@Test fun `screen on launch keyframes`() {
    // t=0: beamY 0, windowClip bottom 0; t=520: beamY 1, windowClip bottom 1; t=650: beamAlpha 0, windowBrightness 1
}
@Test fun `screen on quit keyframes`() {
    // t=220: windowScaleY within 0.012±0.002, windowScaleX 1, windowBrightness >= 2.5
    // t=320: windowScaleX within 0.02±0.005
    // t=380: windowAlpha 0
}
@Test fun `times past the end clamp to the last frame`() { // frame(..., 10_000) == frame(..., duration) }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :composeApp:jvmTest --tests '*LaunchMotionTest*'` — Expected: compile failure.

- [ ] **Step 3: Implement `LaunchMotion`** from the spec's timing tables, with Compose's `CubicBezierEasing` for the
  curves in Global Constraints. Sub-step easing the spec leaves open is the implementer's choice.
- [ ] **Step 4: Run to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(launch): launch and quit motion model"`

### Task 3: Policy: overlay, fade, or nothing

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/LaunchPolicy.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/MirrorWindowMotion.kt` (`systemReducesMotion`: add Linux)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/LaunchPolicyTest.kt`

**Interfaces:**
- Consumes: `LaunchQuitAnimation` (Task 1).
- Produces:

```kotlin
enum class OverlayPlatform { MacOS, Windows, LinuxX11, LinuxWayland;
    companion object { fun current(env: (String) -> String? = System::getenv): OverlayPlatform } }
/** Which platforms draw the overlay; the rest fade. Flip after the user confirms the spike there. */
val OverlayEnabled: Set<OverlayPlatform> = setOf(OverlayPlatform.MacOS)
sealed interface LaunchEffect { data class Overlay(val anim: LaunchQuitAnimation) : LaunchEffect; data object Fade : LaunchEffect; data object None : LaunchEffect }
data class LaunchConditions(
    val setting: LaunchQuitAnimation, val platform: OverlayPlatform, val reduceMotion: Boolean,
    val glassBlur: Boolean, val fullscreen: Boolean, val enabled: Set<OverlayPlatform> = OverlayEnabled,
)
fun launchEffect(c: LaunchConditions): LaunchEffect
```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `off is none`()                       // Off on any platform -> None
@Test fun `chosen animation on an enabled platform is the overlay`() // IconUnfold, MacOS -> Overlay(IconUnfold); ScreenOn -> Overlay(ScreenOn)
@Test fun `reduce motion fades`()                // IconUnfold, MacOS, reduceMotion -> Fade
@Test fun `glass blur fades`()                   // glassBlur -> Fade
@Test fun `full screen fades`()                  // fullscreen -> Fade
@Test fun `platforms not yet confirmed fade`()   // Windows, LinuxX11, LinuxWayland with default enabled -> Fade; with enabled = all -> Overlay
@Test fun `linux session type`()                 // env XDG_SESSION_TYPE=wayland -> LinuxWayland; WAYLAND_DISPLAY set -> LinuxWayland; else LinuxX11 (only when Os.isLinux; otherwise MacOS/Windows from Os)
```

- [ ] **Step 2: Run to verify they fail** — `./gradlew :composeApp:jvmTest --tests '*LaunchPolicyTest*'`.
- [ ] **Step 3: Implement** `launchEffect` (precedence: Off → None; reduceMotion, glassBlur, fullscreen, platform not
  in `enabled` → Fade; else Overlay) and `OverlayPlatform.current`. Extend `systemReducesMotion()` on Linux: run
  `gsettings get org.gnome.desktop.interface enable-animations` with a 500 ms timeout; `false` output → true; any
  failure → false.
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(launch): choose overlay, fade, or nothing per platform"`

### Task 4: Overlay window and scene

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/LaunchOverlay.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/WindowPicture.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/OverlaySceneTest.kt`

**Interfaces:**
- Consumes: `OverlayFrame`, `LaunchMotion` (Task 2).
- Produces:

```kotlin
/** Records the content it wraps into a GraphicsLayer; capture() returns the latest frame. */
class WindowPicture { suspend fun capture(): ImageBitmap? }
@Composable fun rememberWindowPicture(): WindowPicture
fun Modifier.recordInto(picture: WindowPicture): Modifier
/** Draws one frame: icon, phone screen flash, picture clipped/scaled, beam. Fallback shape when picture is null. */
@Composable fun OverlayScene(frame: OverlayFrame, picture: ImageBitmap?, surface: Color, titleBar: Color, accent: Color, modifier: Modifier = Modifier)
/** The see-through, click-through, non-focusable, always-on-top window over [bounds] (screen px, outer). Plays [anim]/[phase]
 *  from t=0 and calls [onFrame] with each frame; [onEnd] after the last frame has been drawn. */
@Composable fun NucleusApplicationScope.LaunchOverlayWindow(
    bounds: NucleusWindowBounds, anim: LaunchQuitAnimation, phase: LaunchPhase, picture: () -> ImageBitmap?,
    theme: AppTheme, onEnd: () -> Unit,
)
```

- [ ] **Step 1: Write the failing test.** `OverlaySceneTest` with `runComposeUiTest` (as other UI tests in the repo):
  render `OverlayScene` at 400×250 for `LaunchMotion.frame(ScreenOn, Launch, 260)` with a solid red picture; assert a
  pixel at 25% height is red (revealed) and at 75% height is transparent (not yet revealed). Second test: Icon unfold
  launch at t=0 with `picture = null` has no red and some non-transparent pixel at the centre (the icon).
- [ ] **Step 2: Run to verify it fails** — `./gradlew :composeApp:jvmTest --tests '*OverlaySceneTest*'`.
- [ ] **Step 3: Implement.** Icon drawn from `composeApp/icons/app-icon.svg` geometry (bg rounded rect, back phone
  outline, front phone, screen); icon width = 22% of the window's shorter side. `LaunchOverlayWindow` applies what the
  Task 0 notes say works per OS (click-through, focus, always-on-top, taskbar hiding), sizes from `bounds` and the
  window's scale factor, and drives `t` with `withFrameNanos`. `WindowPicture` uses `rememberGraphicsLayer()` +
  `drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }` and
  `layer.toImageBitmap()`.
- [ ] **Step 4: Run to verify it passes.**
- [ ] **Step 5: Commit** — `git commit -m "feat(launch): overlay window and scene"`

### Task 5: Launch wiring

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/LaunchHandoff.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/Main.kt` (main window `visible`, overlay, `recordInto`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/devices/DevicesScreen.kt:343` (row stagger)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/LaunchHandoffTest.kt`

**Interfaces:**
- Consumes: `launchEffect`, `LaunchConditions` (Task 3); `LaunchOverlayWindow`, `WindowPicture` (Task 4).
- Produces:

```kotlin
/** Launch hand-off: Playing -> ShowReal (overlay finished) -> RealDrawn (real window's first frame) -> Done (overlay closed).
 *  cancel() (a quit during launch) jumps to Done and shows the real window at once. */
class LaunchHandoff(effect: LaunchEffect) {
    enum class Step { Playing, ShowReal, RealDrawn, Done }
    val step: StateFlow<Step>
    val realVisible: StateFlow<Boolean>   // false only while Playing
    val overlayOpen: StateFlow<Boolean>   // true until Done
    fun overlayEnded(); fun realWindowDrew(); fun cancel()
}
/** Row entrance for Icon unfold: delay = 40 ms × index, 220 ms, decelerate. Provided only on the launch that played Icon unfold. */
val LocalLaunchEntrance: ProvidableCompositionLocal<Boolean>
```

- [ ] **Step 1: Write the failing tests** (`LaunchHandoffTest`):

```kotlin
@Test fun `overlay launch hides the real window until the overlay ends`()  // Overlay: realVisible false, overlayOpen true; overlayEnded -> realVisible true, overlayOpen true; realWindowDrew -> overlayOpen false, step Done
@Test fun `fade and none show the real window at once with no overlay`()  // Fade/None: realVisible true, overlayOpen false, step Done
@Test fun `cancel during playing shows the real window and closes the overlay`() // cancel -> realVisible true, overlayOpen false
@Test fun `realWindowDrew before overlayEnded is ignored`()
```

- [ ] **Step 2: Run to verify they fail** — `--tests '*LaunchHandoffTest*'`.
- [ ] **Step 3: Implement and wire.** In `Main.kt`: compute the effect once at composition start from
  `settings.launchQuit`, `OverlayPlatform.current()`, `systemReducesMotion()`, `settings.appTheme` Glass+blur on macOS,
  `fullscreen = false`. `AppWindow(visible = handoff.realVisible && shuttingDown-rule, ...)`; wrap the root content in
  `Modifier.recordInto(picture)`; signal `realWindowDrew()` from a `LaunchedEffect` that awaits one `withFrameNanos`
  after the window becomes visible. Show `LaunchOverlayWindow` while `overlayOpen`, bounds from the main window's
  `nucleusWindow.boundsOnScreen()`; until the window exists use the window state's initial bounds. Fade launches use
  native alpha 0 → 1 over 150 ms (`setMacAlpha` on macOS; no animation elsewhere). Apply the row entrance at the
  `DeviceRow(` call when `LocalLaunchEntrance` is true.
- [ ] **Step 4: Run all tests** — `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Hand-check on macOS:** launch with each setting value; Icon unfold and Screen on play, Off pops in
  as before, Glass+blur fades.
- [ ] **Step 6: Commit** — `git commit -m "feat(launch): animate the main window on launch"`

### Task 6: Quit wiring

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/AppShutdown.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/Main.kt` (visibility rule, in-window wait, overlay on quit)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/AppShutdownTest.kt`

**Interfaces:**
- Consumes: `LaunchEffect`, `launchEffect` (Task 3); `LaunchOverlayWindow`, `WindowPicture` (Task 4); `LaunchHandoff.cancel()` (Task 5).
- Produces: `AppShutdown(..., animateOut: suspend () -> Unit = {}, animateTimeoutMs: Long = 1_000)` — called in `run()`
  after `quitHook.release()` and before `install`, inside `withTimeoutOrNull(animateTimeoutMs)` and a `try/catch` that
  logs and continues.

- [ ] **Step 1: Write the failing tests** in `AppShutdownTest`:

```kotlin
@Test fun `animateOut runs after sessions close and before install and exit`() // record order into a list: "closed" via session status check inside animateOut, then "install", then "exit"
@Test fun `a failing animateOut does not block exit`()                         // animateOut throws -> exit called once
@Test fun `a stuck animateOut is cut off after the timeout`()                  // animateOut = { delay(60_000) }, animateTimeoutMs = 100 -> run() returns within 2 s, exit called
@Test fun `an update still installs after the animation`()                    // install counter == 1 after run()
```

- [ ] **Step 2: Run to verify they fail** — `--tests '*AppShutdownTest*'`.
- [ ] **Step 3: Implement `animateOut` in `AppShutdown`.**
- [ ] **Step 4: Wire `Main.kt`.** Read the effect at quit time from the current settings (with `fullscreen =
  nucleusWindow.isFullscreen`). For `Overlay`: the main window stays visible while `ShuttingDown`; if sessions are
  open, show a scrim (`colorScheme.scrim` at 0.32 alpha) over the content that consumes pointer input, with the
  `CircularProgressIndicator` + "Finishing recording…" row centred when `finishingRecording`, and do not show
  `FinishingRecordingWindow`. `animateOut` = remove scrim, `withFrameNanos`, capture picture, open the overlay for
  `LaunchPhase.Quit`, hide the main window after the overlay's first frame, suspend until `onEnd`. For `Fade` and
  `None`: today's rule (`visible = shuttingDown == null`, `FinishingRecordingWindow`); `Fade` runs native alpha 1 → 0
  over 150 ms at request time before hiding. A quit while `LaunchHandoff.step != Done` calls `cancel()` first.
- [ ] **Step 5: Run all tests** — `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 6: Hand-check on macOS:** quit with no mirrors; quit with a mirror open; quit while recording (window
  dims, message shows, then the animation); Cmd+Q during the launch animation; Off keeps today's flow.
- [ ] **Step 7: Commit** — `git commit -m "feat(launch): animate the main window on quit"`

### Task 7: Platform switches and final checks

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/launch/LaunchPolicy.kt` (`OverlayEnabled`)
- Modify: `docs/superpowers/notes/2026-11-13-launch-overlay-spike.md` (results)

- [ ] **Step 1:** Add each platform the user confirmed in Task 0 Step 6 to `OverlayEnabled`. Platforms not yet
  confirmed stay on the fade.
- [ ] **Step 2: Run all tests** — `./gradlew :composeApp:jvmTest`. Expected: PASS (the policy test's default-set case
  updates with the set).
- [ ] **Step 3: Hand-check** on macOS: main window on a second display, on a Retina and a non-Retina display if
  available, maximized at quit, full screen at quit (fade).
- [ ] **Step 4: Commit** — `git commit -m "feat(launch): enable overlay on confirmed platforms"`
