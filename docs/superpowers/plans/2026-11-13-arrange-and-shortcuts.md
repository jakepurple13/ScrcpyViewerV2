# Arrange Windows and Keyboard Shortcuts Implementation Plan

> Update 2026-11-13: keyboard shortcuts were dropped by the user; only Arrange ships.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Arrange mirror windows into five layouts from a title-bar menu and the tray, and let the user record keyboard shortcuts for those layouts and multi-device follow.

**Architecture:** Pure layout math (`ui/mirror/arrange/Layouts.kt`) and pure shortcut model (`ui/shortcuts/`). A `MirrorWindows` registry plus `WindowArranger` singleton apply layouts with the existing `animateWindowBounds`. UI: Arrange title-bar slot + menu, tray submenu, a Settings › Keyboard shortcuts page with a recorder. A `ShortcutMatcher` runs in `MirrorKeys` and in the main/Logcat windows' `onPreviewKeyEvent`.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, Nucleus Tao, Koin, ComposeNativeTray, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-13-arrange-and-shortcuts-design.md`

## Global Constraints

- Defaults: layout As placed, Keep tiled off, no shortcuts. Work area = the main window's screen (`ScreenGeometry` + `screenFor`); Linux with no screens → main window bounds.
- Gaps 12 dp; Focus split 62%; Cascade 32% × 62%, offset 34 dp/30 dp; window animation 380 ms, 35 ms stagger, via `animateWindowBounds`; instant with reduced motion or `animateMirrorWindows` off.
- Skip windows that are not Floating, minimized or not showing. Pause each window's sizing/aspect effects while arranging (as the open animation does).
- Copy (exact): "Arrange mirror windows", "ARRANGE MIRROR WINDOWS", "As placed", "Grid", "Side by side", "Focus this window", "Cascade", "Keep tiled as windows open and close", "Your desktop doesn't let apps move windows", "Keyboard shortcuts", "Not set", "Press keys… Esc cancels", "Add ⌘, ⌥ or ⌃ so typing still reaches the device." (macOS) / "Add Ctrl or Alt so typing still reaches the device." (Windows/Linux), "<combo> belongs to the system. Pick another.", "<combo> is already used by <action>.", "Replace", "Cancel", "Clear all", "Search shortcuts", group labels "Multi-device input" / "Arrange mirror windows", action labels "Send input to other windows", "As placed", "Grid", "Side by side", "Focus the front window", "Cascade", footnote from the spec.
- Shortcut display: macOS ⌃⌥⇧⌘ + key; Windows/Linux "Ctrl+Alt+Shift+Key". Storage `AppSettings.shortcuts: Map<String, String>`; action ids `follow`, `arrange-free`, `arrange-grid`, `arrange-row`, `arrange-focus`, `arrange-cascade`.
- Motion table in the spec is binding; nothing snaps; size tweens FastOutSlowIn; reduced motion via `systemReducesMotion()`.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time.

## Review Focus

1. A window being arranged must not fight its own aspect/sizing effects (no bounce after the animation). Task 3.
2. Keep tiled must not loop: a re-tile it caused must not count as a manual move. Task 3.
3. A shortcut chord must never leak a key-up or typed character to the device (including when the shortcut is set while a key is held). Task 6.
4. Linux/Wayland where positioning doesn't stick: layouts disabled with the note, no exception. Task 3.
5. Recorder must consume every key while recording (Esc cancels, nothing triggers app shortcuts or reaches other handlers). Task 5.

---

### Task 1: Shortcut model (DROPPED 2026-11-13)

**Files:** Create `ui/shortcuts/Shortcut.kt`, `ui/shortcuts/ShortcutActions.kt`; Modify `state/AppSettings.kt`; Tests `ui/shortcuts/ShortcutTest.kt`, extend `AppSettingsTest`.
**Produces:** `data class Shortcut(keyCode: Int, ctrl, alt, shift, meta)` with `encode(): String`, `companion fun decode(String): Shortcut?`, `fun label(os: Os): String`; `enum class ShortcutAction(id, label, group)`; `fun validate(s: Shortcut, os: Os): ShortcutProblem?` (`NeedsModifier`, `Reserved`); `fun conflict(map: Map<String,String>, action, s): ShortcutAction?`; `Settings.shortcuts: Map<String, String> = emptyMap()`.
- [ ] Tests: round-trip encode/decode; labels per OS ("⌥⌘G", "Ctrl+Alt+G", modifier order); plain letter → NeedsModifier; Shift-only → NeedsModifier; ⌘Q/⌘V (mac) and Ctrl+V/Alt+F4 (win) → Reserved; conflict returns the owner; settings round-trip incl. old file without the field.
- [ ] Implement; run; commit `feat(shortcuts): shortcut model and settings`.

### Task 2: Layout math

**Files:** Create `ui/mirror/arrange/Layouts.kt`; Test `ui/mirror/arrange/LayoutsTest.kt`.
**Produces:** `enum class ArrangeLayout { AsPlaced, Grid, SideBySide, Focus, Cascade }`; `data class ArrangeWindow(serial, frame: IntSize, chromeW: Int, chromeH: Int)`; `fun layout(layout, windows: List<ArrangeWindow>, work: Rectangle, focus: String?, gapPx: Int, offsets...): Map<String, Rectangle>` (AsPlaced returns empty — handled by the arranger).
- [ ] Tests: 4 phones in a 1600×1000 area → 4 columns beats 2×2 when windows are tall; tablet+phones mix; side by side single row; focus 62% left and stacked right column; cascade offsets; every result keeps aspect (via outerHeight formula ±1 px) and stays inside the work area; gaps ≥ 12; one window → centred max fit; zero windows → empty.
- [ ] Implement; run; commit `feat(arrange): layout math`.

### Task 3: Registry and arranger

**Files:** Create `ui/mirror/arrange/MirrorWindows.kt` (registry), `ui/mirror/arrange/WindowArranger.kt`; Modify `MirrorWindow.kt` (register handle + `ArrangeWindow` info on first frame / unregister on dispose; pause sizing effects while `arranging`), DI; Test `ui/mirror/arrange/WindowArrangerTest.kt` with fake handles.
**Produces:** `class MirrorWindows { entries: StateFlow<Map<String, WindowEntry>>; register; unregister }` with `WindowEntry(handle: MirrorWindowHandle, info: () -> ArrangeWindow, floating: () -> Boolean, minimized, showing, arranging: MutableState<Boolean>)`; `class WindowArranger(windows, controller, screens, settings, scope)` with `state: StateFlow<ArrangeState(layout, keepTiled, movable: Boolean)>`, `apply(layout, focus: String?)`, `setKeepTiled(Boolean)`, `onUserMoved(serial)`.
- [ ] Tests: apply Grid moves floating windows only; As placed restores remembered bounds after Grid; Keep tiled re-applies when a window opens/closes and doesn't treat its own moves as manual; a manual bounds change after settle turns Keep tiled off; position that doesn't stick → `movable=false`, apply is a no-op; reduced motion → bounds set once.
- [ ] Implement; run `--tests '*Arrange*' --tests '*Mirror*'`; commit `feat(arrange): window registry and arranger`.

### Task 4: Arrange UI and tray entry

**Files:** Create `ui/mirror/arrange/ArrangeControls.kt`; Modify `MirrorWindow.kt` TopStrip/StripRoom/OverflowMenu (new slot, overflow item), `ui/tray/TrayMenuModel.kt` + `ScrcpyTray.kt` (submenu), tests `ArrangeControlsTest.kt`, `TrayMenuModelTest`, `StripRoomTest`, `MirrorScreenshotTest` captures.
**Produces:** `@Composable ArrangeButton(state, shortcuts, onPick, onKeepTiled, compact)`; tray `TrayAction.Arrange(layout)` / `KeepTiled`.
- [ ] Tests: button only with ≥2 windows; menu check on current layout; shortcut text shown when set; disabled entries + note when `movable=false`; strip collapse order keeps the multi-device rules and a lone window's layout unchanged; tray submenu only with ≥2 mirrors.
- [ ] Implement; screenshots (title bar with Arrange, open menu, disabled note); commit `feat(arrange): arrange menu in mirror windows and the menu bar`.

### Task 5: Keyboard shortcuts settings page (DROPPED 2026-11-13)

**Files:** Create `ui/settings/shortcuts/ShortcutsPage.kt`, `ShortcutRecorder.kt`; Modify `SettingsRegistry.kt` (category `Keyboard` after Mirroring), `SettingsViewModel`/actions; Tests `ShortcutRecorderTest.kt`, settings screenshots.
- [ ] Tests (Compose harness): recording consumes keys and Esc cancels; valid chord sets and persists; invalid → hint text; reserved → hint; conflict strip with Replace clears the other; Clear all; search folds rows; motion values per the spec table.
- [ ] Implement; screenshots idle/recording/conflict/invalid (light+dark); commit `feat(shortcuts): keyboard shortcuts settings page`.

### Task 6: Shortcut dispatch (DROPPED 2026-11-13)

**Files:** Create `ui/shortcuts/ShortcutMatcher.kt`, `ui/shortcuts/ShortcutCommands.kt`; Modify `MirrorKeys.kt` (matcher after sheet check, before device), `Main.kt` main window + `LogcatWindow.kt` (`onPreviewKeyEvent`), track last focused mirror window (registry); Tests `ShortcutMatcherTest.kt`, extend `MirrorKeysTest`, `ShortcutCommandsTest.kt`.
**Produces:** `ShortcutMatcher(shortcuts: () -> Map<String,String>)` with `handle(AwtKeyTuple): ShortcutAction?` consuming the full chord and `reset()`; `ShortcutCommands(following, arranger, windows, controls)` with `run(action, fromSerial: String?)`.
- [ ] Tests: matched chord → no InjectKeycode/InjectText/UHID sent for press, repeat, release or typed char; unmatched keys pass through; focus loss resets; follow toggles (lead with all controllable others / stop); from main window uses last focused mirror; fewer than 2 mirrors → nothing; arrange actions call the arranger.
- [ ] Implement; run `--tests '*Shortcut*' --tests '*MirrorKeysTest*' --tests '*Follow*'`; commit `feat(shortcuts): run shortcuts from any window`.
