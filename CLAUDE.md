# Scrcpy Vwr: project notes for Claude Code

## UI must work at every window size

Every screen, tab, sheet and dialog must stay readable and animated from its window's minimum size up to full
screen. The design that set this up is `docs/superpowers/specs/2026-11-12-narrow-window-layouts-design.md`; read
it before building a new screen. Section 6c holds the user's decisions from hand testing.

When you add or change UI, check it at a 360 dp window, at 520, at 700, and at full size. Only the narrow layouts
may change; wide layouts stay as they are.

### Pick the screen pattern

- **Task screens** (Devices, Presets, Actions: the user does one thing at a time). Use `AdaptiveListDetail`
  (`ui/common/AdaptiveListDetail.kt`). It shows two panes only from an 840 dp window (`TwoPaneMinWindowWidth`).
  Narrower, it shows the list first, even with a remembered selection. Clicking an item calls `panes.push()`, and
  the detail slides over the list. Back is a "Back to list" arrow (`BackArrowRow`), Esc, or ⌘[ (Ctrl+[ on Windows
  and Linux).
- **Browse screens** (Settings: the user should see what can be changed while changing it). Keep the sidebar at
  every width. It narrows, then turns into an icon column with tooltips (`ui/settings/SettingsScreen.kt`).

### Use the shared building blocks (`ui/common`)

- `AdaptiveRow`: a label beside its control, stacking the label above when the label would get less than
  `textMin`. Use it for setting rows and field + button rows.
- `AdaptiveSegmentedButtons` with `ControlSize` (`Full`, `Icons`, `Menu`): segmented choices that drop their
  labels, then become one menu button.
- `PriorityBar` with `BarItem(priority = …)`: toolbars that fold low-priority actions into a ⋮ menu. It composes
  each item twice; the measuring copy has `LocalBarProbe.current == true`. Never put effects, focus requesters,
  menus or position callbacks inside a bar item; keep menus outside the bar.
- `SelectionHighlight`: the sliding Material selection behind list rows. Other theme styles keep their own
  selected-row look.
- `glideOnFlip` / `LayoutFlips` (`FlipGlide.kt`): children glide only when a layout switches shape (columns,
  stacked or inline).

### Motion rules

- Every size or position change uses `layoutSpring()` (`ui/common/AdaptiveMotion.kt`). Fades and color changes
  keep short tweens.
- Read reduce motion from `LocalReduceMotion.current`. Never call `systemReducesMotion()` outside a window root.
  Every window root (`AppWindow`) provides `LocalReduceMotion`. Under reduce motion, changes snap or fade.
- Anything sized by the window follows a drag directly, in the same frame. Only discrete layout switches animate.
  Plain `animateBounds` or `animateContentSize` on window-sized content lags the drag; avoid it. Use
  `layoutFraction` or `layoutDp` (which snap in the same frame when not gliding), a custom `Layout` driven by a
  switch fraction (see `MorphRow` in `ui/mirror/links/LinksTab.kt`, or `AdaptiveRow`), and `resizing(width)` or
  `flipGliding(shape)` to turn lazy-list placement springs off while a drag resizes rows.
- Springs overshoot: clamp any animated size or padding to at least 0 (`coerceAtLeast(0)`).
- When content reflows, animate the container's height too, not only the children.

### Desktop gotchas

- Don't use the AWT move cursor (`Cursor.MOVE_CURSOR`). Nucleus Tao maps it to a private macOS selector that
  current macOS no longer has, and hovering it crashes the app. Use `PointerIcon.Hand`.
- Window minimum sizes are set through `AppWindow(minSize = …)`: main 360×480, mirror 344×360, logcat 360×360, database 360×360.

### Tests

- Add narrow scene tests for new UI at 360, 520, 700 and 1000 dp. Add a new screen to `ui/NarrowWidthsTest.kt`:
  its primary action is shown and inside the window, and no text is squeezed under 40 px.
- `ImageComposeScene` doesn't update `LocalWindowInfo.containerSize` when the scene is resized. Build a new scene
  per width, or provide a test `WindowInfo` (see `SettingsNarrowTest`).
- CI on macOS and Windows reports reduce motion on. Always pass `motion` or `LocalReduceMotion` explicitly in tests.
  For animations, assert "strictly between old and new, then settled", not fixed tween fractions.
- Screenshot tests (`-Pscreenshots`) render about 3 frames, so animated layouts must start at their target. Compare
  wide renders before and after a change; they should match.

## Releasing

Follow the "Releasing" section of `README.md`.
