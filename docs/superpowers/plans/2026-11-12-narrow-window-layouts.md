# Narrow Window Layouts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every screen stays readable at any window width through six shared, animated layout rules (panes, rows, action bars, segmented controls, size caps, mirror sheet), plus window minimums.

**Architecture:**
- **Shared pieces** go in `ui/common/`. Each is a composable backed by a pure decision function, and the decision function is unit tested:
  - `AdaptiveRow`
  - `AdaptiveSegmentedButtons`
  - `PriorityBar`
  - `AdaptiveListDetail` (with `ListStrip`, the overlay and the switcher chip)
  - `SelectionHighlight`
  - the item-switch transition
- `AdaptiveListDetail` replaces `ResizableListDetail`. It keeps `ListDetailNavigation` and `PaneWidthLimits`, but drops the M3 `ListDetailPaneScaffold`, so the list width can animate between pane, strip and full.
- Screens then migrate one at a time.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.12.2 (desktop/JVM), Material 3 + material3-adaptive, Nucleus Tao windows (2.5.18), kotlin.test with `ImageComposeScene` scene tests.

**Spec:** `docs/superpowers/specs/2026-11-12-narrow-window-layouts-design.md`
**Mockup:** https://claude.ai/artifact/UfUaFu8LacPbdangsuXvA3 ("With E" mode). Match its behavior and motion.
**Mockup addendum (mirror sheet tabs, Tasks 13, 16, 17):** https://claude.ai/artifact/6wsGe9iFtdpu4JJPkzcTi7.

All paths below are relative to `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/` (main) or `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/` (test).

**Test commands:**
- One test class: `./gradlew :composeApp:jvmTest --tests '<fqcn>'`.
- Full suite: `./gradlew :composeApp:jvmTest`.

**Scene tests:** use the existing helpers `SceneDriver` and `onEdt` from `ui/SceneTestSupport.kt` with `ImageComposeScene(w, h, Density(1f))`, so 1 px = 1 dp. See `ui/common/ResizableListDetailTest.kt` for the pattern.

## Global Constraints

- Every layout state change in this work is animated. Nothing snaps. The exception is reduced motion: when `LocalReduceMotion.current` is true, movement becomes a ≤150 ms fade or an instant change.
- Movement uses `LayoutSpring` (Task 1). Content swaps use `itemSwitchTransform` (Task 1). Both are defined once.
- No visual change above the new breakpoints, except that item switches now animate on Devices, Presets and Actions.
- Primary actions never fold into ⋮:
  - Mirror (Devices footer)
  - Apply (preset header)
  - Test run and Save (action editor)
  - Pair (pairing dialog)
  - Update now (update dialog)
- Two panes when the content width ≥ listMinWidth + 16 dp divider + **420 dp**. Below that, list view (`detailOpen == false`) or item view (`detailOpen == true`). In two-pane mode, `detailOpen` tracks "an item is selected".
- Strip: **76 dp** wide.
  - Each item is a 40 dp circular avatar plus an 11 sp one-line ellipsized name and a tooltip.
  - The selected avatar uses primary / onPrimary.
  - The top of the strip has `›` ("Show list"), which calls `backToList()`.
- Rail labels hide when the window is under **540 dp**.
- Row text minimums:
  - 160 dp: settings rows, field + button rows.
  - 140 dp: option rows.
  - 90 dp: preset flag cells.
  - 80 dp: device name next to its chip.
- Window minimums:
  - Main window: **360 × 480 dp**.
  - Mirror window: **344 × 360 dp**.
- Build on `main` ≥ `007f35a` (Tao merged). This branch already is.
- Commit messages are conventional (`feat(...)`, `test(...)`), ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Divider dragged wide, then the window shrinks.** The mode depends on the list *minimum*, not the dragged width, and the list snaps back (animated) so the detail keeps ≥ 420 dp. Test in Task 5.
2. **Overlay open while the window widens back to two panes.** The overlay closes and both panes show, with no stale scrim. Test in Task 6.
3. **The selected item disappears in item view** (device unplugged, preset or action deleted). The screen goes back to list view without crashing. Tests in Tasks 8, 9 and 10.
4. **Long names in the strip and chip** (e.g. `adb-R5CX12GDWPZ-x7Ka._adb-tls-connect._tcp`). The strip label and the chip (max 160 dp label) ellipsize, and nothing widens the strip. Test in Task 6.
5. **Settings search typed in list view.** It filters the categories and stays in list view. Picking a category opens item view. Test in Task 11.

---

### Task 1: Shared motion and reduced motion

**Files:**
- Create: `ui/common/AdaptiveMotion.kt`
- Modify:
  - `ui/mirror/MirrorWindowMotion.kt:285`: move `systemReducesMotion()` to `ui/common/AdaptiveMotion.kt` and update its imports.
  - `ui/App.kt` and `ui/window/AppWindow.kt`: provide `LocalReduceMotion`.
  - `ui/settings/SettingsScreen.kt:108-114`: use `itemSwitchTransform`.
- Test: `ui/common/AdaptiveMotionTest.kt`

**Interfaces:**
- Produces:
  - `val LocalReduceMotion: ProvidableCompositionLocal<Boolean>`, default `false`.
  - `fun systemReducesMotion(): Boolean`, moved and unchanged.
  - `fun <T> layoutSpring(): FiniteAnimationSpec<T>`: `spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)`.
  - `fun switchDirection(fromIndex: Int, toIndex: Int): Int`: returns 1 when `toIndex >= fromIndex`, else -1.
  - `fun AnimatedContentTransitionScope<*>.itemSwitchTransform(direction: Int, reduceMotion: Boolean): ContentTransform`:
    - normal: exactly the current Settings spec, `fadeIn(tween(220, 60)) + slideInVertically(tween(220)) { direction * it / 12 }` togetherWith `fadeOut(tween(120)) + slideOutVertically(tween(120)) { -direction * it / 24 }`.
    - reduced: `fadeIn(tween(120)) togetherWith fadeOut(tween(120))`.

- [ ] **Step 1: Write the failing tests**
  - `switchDirection(0, 2) == 1`, `switchDirection(3, 1) == -1`, `switchDirection(2, 2) == 1`.
  - `layoutSpring<Float>()` is a `SpringSpec` with `dampingRatio == 0.8f`.
- [ ] **Step 2: Run it.** Run `--tests 'com.programmersbox.scrcpyviewer.ui.common.AdaptiveMotionTest'`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement**
  - Implement `AdaptiveMotion.kt`.
  - Move `systemReducesMotion`.
  - Provide `LocalReduceMotion provides remember { systemReducesMotion() }` at the root of `App` and inside `AppWindow`'s Tao scene, the same place the theme is re-provided.
  - Swap the Settings `transitionSpec` to `itemSwitchTransform(switchDirection(from, to), LocalReduceMotion.current)`.
- [ ] **Step 4: Run it, then the full suite.** Run `AdaptiveMotionTest`, then `./gradlew :composeApp:jvmTest`. Expected: PASS. Existing Settings screenshot tests are unchanged.
- [ ] **Step 5: Commit.** `feat(motion): shared layout spring, item-switch transition, reduce-motion local`

---

### Task 2: AdaptiveRow

**Files:**
- Create: `ui/common/AdaptiveRow.kt`
- Test:
  - `ui/common/AdaptiveRowTest.kt` (pure function)
  - `ui/common/AdaptiveRowSceneTest.kt` (layout)

**Interfaces:**
- Consumes: `layoutSpring`, `LocalReduceMotion` (Task 1).
- Produces:
  - `enum class ControlSize { Full, Icons, Menu }`
  - `data class RowFit(val stacked: Boolean, val size: ControlSize)`
  - `fun rowFit(available: Int, textMin: Int, gap: Int, full: Int, icons: Int = full, menu: Int = icons): RowFit`:
    - Inline at `Full` when `available - full - gap >= textMin`.
    - Otherwise stacked, at `Full` if `available >= full`, else `Icons` if `available >= icons`, else `Menu`.
  - `@Composable fun AdaptiveRow(text: @Composable () -> Unit, control: @Composable (ControlSize) -> Unit, modifier: Modifier = Modifier, textMin: Dp = 160.dp, gap: Dp = 16.dp, fillControlWhenStacked: Boolean = false)`
    - Measures the control's max intrinsic width at each `ControlSize` with `SubcomposeLayout`.
    - Picks a fit with `rowFit`.
    - Lays out as a Row (text weighted) or a Column (control below the text, start-aligned or full width).
    - Position changes animate with `LookaheadScope` + `Modifier.animateBounds(lookaheadScope, boundsTransform = { _, _ -> layoutSpring() })` on both the text and the control slots.
    - Under reduced motion, no `animateBounds`.

- [ ] **Step 1: Write the failing pure tests** (`AdaptiveRowTest`)
  - `rowFit(400, 160, 16, full = 300) == RowFit(true, Full)` (400 - 300 - 16 = 84 < 160, so it stacks).
  - `rowFit(500, 160, 16, 300) == RowFit(false, Full)`.
  - `rowFit(250, 160, 16, full = 300, icons = 140) == RowFit(true, Icons)`.
  - `rowFit(120, 160, 16, full = 300, icons = 140, menu = 110) == RowFit(true, Menu)`.
- [ ] **Step 2: Write the failing scene test** (`AdaptiveRowSceneTest`)
  - Set up an `AdaptiveRow` with a text `Box` (records its width) and a 300×40 control `Box` (records its offset) in scenes 600 and 400 wide.
  - At 600: the text width is `600 - 300 - 16 = 284` and the control y is 0.
  - At 400: the text width is 400 and the control's top is ≥ the text's height.
  - Name: `text never gets less than its minimum`.
- [ ] **Step 3: Run both.** Expected: FAIL.
- [ ] **Step 4: Implement `AdaptiveRow.kt`.**
- [ ] **Step 5: Run both.** Expected: PASS.
- [ ] **Step 6: Commit.** `feat(layout): AdaptiveRow moves its control under the text when tight`

---

### Task 3: AdaptiveSegmentedButtons

**Files:**
- Create: `ui/common/AdaptiveSegmented.kt`
- Test: `ui/common/AdaptiveSegmentedTest.kt`

**Interfaces:**
- Consumes: `ControlSize` (Task 2), `layoutSpring`, `LocalReduceMotion`.
- Produces:
  - `data class SegmentOption<T>(val value: T, val label: String, val icon: ImageVector? = null)`
  - `@Composable fun <T> AdaptiveSegmentedButtons(options: List<SegmentOption<T>>, selected: T, onSelect: (T) -> Unit, size: ControlSize, modifier: Modifier = Modifier, fillWidth: Boolean = false, maxWidth: Dp = Dp.Unspecified)`
    - **Full:** M3 `SingleChoiceSegmentedButtonRow`, icon + label.
    - **Icons:** labels animate width to 0 (`AnimatedVisibility(expandHorizontally/shrinkHorizontally)`). Each segment gets a `TooltipArea` with its label. When no option has an icon, `Icons` renders as `Full`.
    - **Menu:** an `OutlinedButton`-style dropdown showing the selected option's icon and label with a caret, opening a `DropdownMenu` of the options.
    - Full/Icons ↔ Menu cross-fades through `AnimatedContent` keyed on `size == Menu`.
    - `fillWidth` stretches segments equally, capped by `maxWidth`.

- [ ] **Step 1: Write the failing scene tests**
  - Theme options System / Light / Dark, with icons.
  - At `Full`: the semantics tree has text nodes "System", "Light" and "Dark".
  - At `Icons`: those texts are not displayed, and there are 3 clickable nodes.
  - At `Menu`: one clickable node with text "System". Clicking it, then "Dark", calls `onSelect(Dark)`.
  - Use `runComposeUiTest` if the project already does (`grep -r runComposeUiTest jvmTest`). Otherwise use a `SceneDriver` with semantics lookups as in existing tests.
- [ ] **Step 2: Run it.** Expected: FAIL.
- [ ] **Step 3: Implement `AdaptiveSegmented.kt`.**
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(layout): segmented buttons with icons-only and menu sizes`

---

### Task 4: PriorityBar

**Files:**
- Create: `ui/common/PriorityBar.kt`
- Test: `ui/common/PriorityBarTest.kt`

**Interfaces:**
- Produces:
  - `fun foldBar(available: Int, widths: List<Int>, priorities: List<Int>, gap: Int, overflowWidth: Int): Set<Int>`
    - Returns the indices to fold.
    - Priority 0 never folds.
    - Folds the highest priority first (ties: the later index first) until `sum(visible widths) + gaps + (overflowWidth + gap if anything folded)` ≤ `available`.
  - `class BarItem(val key: String, val priority: Int, val menuLabel: String, val onMenuClick: (() -> Unit)?, val minWidth: Dp = Dp.Unspecified, val grow: Boolean = false, val content: @Composable () -> Unit)`
  - `object BarSpacer`, marking a weighted spacer slot: `PriorityBarScope.spacer()`.
  - `@Composable fun PriorityBar(items: List<Any /* BarItem or BarSpacer */>, modifier: Modifier = Modifier, gap: Dp = 8.dp)`
    - Measures each item's natural width once (`SubcomposeLayout`). A `grow` item uses `minWidth`.
    - Folded items animate out with `AnimatedVisibility(shrinkHorizontally + fadeOut)`.
    - A ⋮ `IconButton` (content description "More") appears with a scale pop (0.3 → 1.15 → 1) when anything is folded. Its `DropdownMenu` lists the folded items' `menuLabel`, in bar order, calling `onMenuClick`.
- Note: the mirror top strip (`MirrorWindow.kt:1170`) keeps its own thresholds. Do not migrate it.

- [ ] **Step 1: Write the failing pure tests**
  - `foldBar(400, listOf(100, 72, 260), listOf(2, 0, 1), 8, 36) == setOf(0)`.
  - Same widths at `available = 200` → `setOf(0, 2)`.
  - An all-zero priority list never folds, even when it overflows.
  - Devices footer case: `widths = [150, 60, 150, 0 /*spacer*/, 170, 100]`, `priorities = [3, 2, 5, 0, 1, 0]`, `available = 420` → folds index 2 then 0 → `setOf(2, 0)`.
- [ ] **Step 2: Write the failing scene test.** At 300 dp a bar of [Edit p2, Duplicate p5, Apply p0] shows "Apply" and "More" and not "Duplicate". Clicking More lists "Duplicate".
- [ ] **Step 3: Run them.** Expected: FAIL.
- [ ] **Step 4: Implement `PriorityBar.kt`.**
- [ ] **Step 5: Run them.** Expected: PASS.
- [ ] **Step 6: Commit.** `feat(layout): PriorityBar folds low-priority buttons into an overflow menu`

---

### Task 5: AdaptiveListDetail core (pane modes, animated widths, divider)

**Files:**
- Create: `ui/common/AdaptiveListDetail.kt`
- Modify: `ui/common/ListDetailPanes.kt`. Keep `ListDetailNavigation`, `PaneWidthLimits`, `ListDetailPanes` and `rememberListDetailPanes`. Add `ListDetailPanes.listWidth: Dp?` state for the dragged width, replacing `PaneExpansionState`. Delete `ResizableListDetail` and `DetailBackButton` in Task 11, after the last screen moves.
- Test:
  - `ui/common/PaneModeTest.kt`
  - `ui/common/AdaptiveListDetailTest.kt` (port of `ResizableListDetailTest`)

**Interfaces:**
- Consumes: `layoutSpring`, `LocalReduceMotion`.
- Produces:
  - `enum class PaneMode { TwoPane, ListView, ItemView }`
  - `fun paneMode(contentWidth: Dp, listMinWidth: Dp, detailOpen: Boolean, detailMinWidth: Dp = 420.dp, divider: Dp = 16.dp): PaneMode`
  - `enum class ListPresentation { Full, Strip }`
  - `val StripWidth = 76.dp`
  - `@Composable fun AdaptiveListDetail(panes: ListDetailPanes, listMinWidth: Dp, listPreferredWidth: Dp, hasSelection: Boolean, list: @Composable (ListPresentation) -> Unit, detail: @Composable (openSwitcher: (() -> Unit)?) -> Unit, modifier: Modifier = Modifier, resizable: Boolean = true)`
- Behavior:
  - The list width animates (`animateDpAsState` with `layoutSpring`) to:
    - `TwoPane`: the dragged width, else `listPreferredWidth`.
    - `ItemView`: `StripWidth`.
    - `ListView`: the full content width.
  - The detail is composed in TwoPane and ItemView only. It enters from the right (48 dp slide + fade) when going ListView → ItemView.
  - The divider is a 16 dp gap with a hairline. In TwoPane with `resizable`, it also holds the `VerticalDragHandle`, which uses `Modifier.draggable` through `PaneWidthLimits.consume` plus the existing Left/Right keyboard step of 24 dp. In ItemView it is an 8 dp hairline gap.
  - `LaunchedEffect(mode, hasSelection)`: in TwoPane, `if (hasSelection) openDetail() else backToList()`.
  - `list` receives `Strip` only in ItemView.
  - `openSwitcher` is non-null only in ItemView (Task 6 wires the overlay; this task passes a no-op placeholder that Task 6 replaces).

- [ ] **Step 1: Write the failing pure tests** (`PaneModeTest`). With `listMin = 240` and content 676 = 240 + 16 + 420:
  - `paneMode(676.dp, 240.dp, detailOpen = false) == TwoPane`.
  - `paneMode(675.dp, 240.dp, false) == ListView`.
  - `paneMode(675.dp, 240.dp, true) == ItemView`.
- [ ] **Step 2: Port the scene tests** to `AdaptiveListDetailTest`, with `listMin = 240` and `preferred = 300`:
  - The divider starts at 300, drags within [240, width - 16 - 420], and the detail keeps 420.
  - **Review Focus 1:** at 1200 drag the list to 760, then rebuild the scene at 800. After frames, the list is ≤ 800 - 16 - 420 = 364 and the mode is TwoPane (both panes laid out).
  - At 600 with `hasSelection = true` set while wide (start at 1200, then resize to 600): the list width settles to 76 and the detail width to 600 - 76 - 8.
  - At 600 with no selection: the list width is 600 and the detail is not composed.
- [ ] **Step 3: Run them.** Expected: FAIL.
- [ ] **Step 4: Implement** `AdaptiveListDetail.kt` and the `ListDetailPanes` changes. Keep `ResizableListDetail` compiling until Task 11. It can stay on its old code path.
- [ ] **Step 5: Run them, plus `ResizableListDetailTest` and `ListDetailPanesTest`.** Expected: PASS.
- [ ] **Step 6: Commit.** `feat(panes): AdaptiveListDetail with two-pane, list and item views`

---

### Task 6: ListStrip, list overlay, switcher chip

**Files:**
- Create: `ui/common/ListStrip.kt`
- Modify: `ui/common/AdaptiveListDetail.kt` (overlay and `openSwitcher`)
- Test: `ui/common/ListStripTest.kt`, plus additions to `AdaptiveListDetailTest.kt`

**Interfaces:**
- Consumes: `AdaptiveListDetail`, `StripWidth`, `ListPresentation` (Task 5).
- Produces:
  - `data class StripItem(val key: Any, val label: String, val avatar: @Composable () -> Unit, val badgeColor: Color? = null)`
  - `@Composable fun ListStrip(items: List<StripItem>, selectedKey: Any?, onSelect: (Any) -> Unit, onShowList: () -> Unit, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit = {})`
    - `›` at the top: an IconButton with content description "Show list".
    - Each item: a 40 dp circle avatar. The selected one uses primary / onPrimary, with the color animated via `animateColorAsState`.
    - An optional 12 dp badge dot at the bottom end, ringed with the surface color.
    - An 11 sp one-line ellipsized label, max width 64 dp, and a `TooltipArea` with the full label.
    - `actions` sits at the bottom.
  - `@Composable fun ListSwitcherChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier)`
    - Swap icon + label (max 160 dp, ellipsized) + caret.
    - Content description "Switch item".
  - The `AdaptiveListDetail` overlay:
    - `openSwitcher()` sets `overlayOpen`.
    - The list renders `Full` at `listPreferredWidth` in a `Box` above the detail. Its width animates from the strip (76) to the preferred width with `layoutSpring`.
    - A scrim (`scrim` color at 0.32 alpha) fades in. Clicking the scrim closes the overlay.
    - The overlay closes automatically when the mode leaves ItemView, or when `hasSelection` changes key (screens call `onSelect` and the overlay closes). Expose `fun ListDetailPanes.closeOverlay()` for screens to call after a pick.

- [ ] **Step 1: Write the failing tests**
  - `ListStripTest`:
    - 3 items. Clicking the second calls `onSelect(key2)`. Clicking "Show list" calls `onShowList`.
    - **Review Focus 4:** a 60-char label leaves the strip width at exactly 76.
  - `AdaptiveListDetailTest` additions:
    - In ItemView, calling `openSwitcher` makes the overlay list measure `listPreferredWidth` after frames.
    - **Review Focus 2:** with the overlay open, resizing the scene to 1200 closes the overlay. The scrim node is gone and both panes show.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(panes): icon strip, list overlay and switcher chip`

---

### Task 7: SelectionHighlight (sliding selection in lists)

**Files:**
- Create: `ui/common/SelectionHighlight.kt`
- Test: `ui/common/SelectionHighlightTest.kt`

**Interfaces:**
- Consumes: `layoutSpring`, `LocalReduceMotion`.
- Produces:
  - `@Stable class SelectionHighlightState`
  - `@Composable fun rememberSelectionHighlight(): SelectionHighlightState`
  - `fun Modifier.selectionHighlightTarget(state: SelectionHighlightState, selected: Boolean): Modifier`: records the selected row's bounds relative to the container via `onPlaced`.
  - `fun Modifier.selectionHighlight(state: SelectionHighlightState, color: Color, shape: Shape): Modifier`: on the list container, draws the highlight behind the content with `drawBehind`. Its top, height, left and width animate with `layoutSpring` and snap on the first placement.
- Rows that use it drop their own selected background.

- [ ] **Step 1: Write the failing scene test.**
  - Three 40 dp rows in a Column. Select row 0, then row 2.
  - After the animation frames, the state's current rect top equals row 2's top (80).
  - Right after the switch (1 frame), the top is strictly between 0 and 80.
- [ ] **Step 2: Run it.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(motion): sliding selection highlight for lists`

---

### Task 8: Devices screen

**Files:**
- Modify:
  - `ui/devices/DevicesScreen.kt`:
    - the list column (~300–330)
    - device rows (~460–490)
    - the advanced header (507–522)
    - option columns (534–536)
    - the footer (671–721)
    - the `ResizableListDetail` call
  - `ui/devices/SimpleDevicePane.kt` (the fact chips row and the header row at 150–157)
  - `ui/common/OptionRow.kt:77`
- Test: `ui/devices/DevicesNarrowTest.kt`, and update `ui/DevicesScreenshotTest.kt` goldens if any change above the breakpoints. There should be none except where noted.

**Interfaces:**
- Consumes:
  - `AdaptiveListDetail`, `ListStrip`, `StripItem`, `ListSwitcherChip`, `SelectionHighlight`
  - `AdaptiveRow`, `PriorityBar`, `BarItem`
  - `itemSwitchTransform`, `switchDirection`
- Produces: nothing new.

Changes:
- **List-detail:** `AdaptiveListDetail(listMinWidth = 240.dp, listPreferredWidth = 270.dp, hasSelection = selected != null)`.
  - The list renders `ListStrip` when `Strip`:
    - avatar: `Icons.Filled.PhoneAndroid`, or `TabletAndroid` when the model contains "Tablet".
    - `badgeColor`: the status dot color.
    - `actions`: the restart-adb icon and a 44 dp tonal "Pair over Wi-Fi" icon button.
  - Rows use `selectionHighlightTarget`, and the list Column uses `selectionHighlight`.
  - The detail header shows `ListSwitcherChip(deviceTitle)` when `openSwitcher != null`, replacing `DetailBackButton`.
  - The detail content is an `AnimatedContent` keyed on the selected serial with `itemSwitchTransform(switchDirection(oldIndex, newIndex), reduce)`.
- **Device row title + StatusChip:** `AdaptiveRow(textMin = 80.dp, gap = 6.dp)`. The summary header text gets `maxLines = 1` + ellipsis. The hint text gets `maxLines = 2`.
- **OptionRow:** `AdaptiveRow(textMin = 140.dp, gap = 8.dp, fillControlWhenStacked = true)`. The value field becomes `widthIn(max = 140.dp)` inline and full width when stacked.
- **Option columns:** two columns when the pane content width ≥ 560 dp, else one column. Animate with `LookaheadScope` + `animateBounds` on each option group.
- **Advanced header:** `PriorityBar`:
  - Simple view: p2.
  - Title "Options": p0, grow, minWidth 110.
  - Search field: p1, `widthIn(max = 260.dp)`, menu label "Search options". The menu action focuses a search field shown in a popup.
- **Footer:** `PriorityBar`:
  - Preset chip: p3.
  - Save: p2.
  - Save as new preset: p5.
  - Spacer.
  - Connect over Wi-Fi: p1.
  - Mirror: p0.
- **Simple pane:** the header row (icon + title/facts) stacks under 300 dp via `AdaptiveRow(textMin = 160.dp)` with the icon as the control. If that reads wrong, keep it a Row and only wrap the facts.
- **Review Focus 3:** when the selected serial leaves the device list while in ItemView, call `navigation.backToList()`.

- [ ] **Step 1: Write the failing tests** (`DevicesNarrowTest`, scenes with fake devices as in the existing Devices tests):
  - At 1000: both panes show and the list row texts are visible.
  - At 700 with a selected device: "Show list" exists, the "Switch item" chip is displayed, "Mirror" is displayed, and no `DetailBackButton` ("Back to list") exists.
  - At 700, clicking "Show list" displays all device names in full and removes the "Mirror" button.
  - At 360 in Advanced: "Mirror" and "More" are displayed. Every OptionRow label node's width is ≥ 140 or its value field sits below it.
  - Removing the selected device from the fake list at 700 shows list view.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement the changes above.**
- [ ] **Step 4: Run `DevicesNarrowTest`, `DevicesScreenshotTest` and the full suite.** Expected: PASS. Update goldens only where the item-switch or row change is intended, and say which in the commit body.
- [ ] **Step 5: Commit.** `feat(devices): strip, switcher chip, adaptive rows and bars for narrow windows`

---

### Task 9: Presets screen

**Files:**
- Modify: `ui/presets/PresetsScreen.kt`:
  - the pane call (203–207)
  - list rows
  - the view header (305–318)
  - the flag grid (351–366)
  - edit Name/Description (414–416)
- Test: `ui/presets/PresetsNarrowTest.kt`

**Interfaces:** consumes the same as Task 8.

Changes:
- **List-detail:** `AdaptiveListDetail(listMinWidth = 280.dp, listPreferredWidth = 390.dp, hasSelection = selected != null)`.
  - Strip avatar: two-letter initials (first letter of the first two words, else the first two letters, uppercased).
  - `badgeColor = primary` for the default preset.
  - Strip actions: a "+ New preset" icon button (content description "New preset"). Import and Export stay in the full list only.
  - Selection highlight, switcher chip and item switch as in Task 8.
- **View header:** `PriorityBar`:
  - Name: p0, grow, minWidth 110.
  - Default/Set as default chip: p1.
  - Edit: p2.
  - Duplicate: p5.
  - Export: p4.
  - Delete: p3 (error color).
  - Apply: p0.
- **Flag grid:** two cells per row when the content width ≥ 520 dp, else one. Each cell is `AdaptiveRow(textMin = 90.dp, gap = 10.dp)` with the flag as the control.
- **Edit mode Name + Description:** side by side ≥ 480 dp, stacked below. Animate with `animateBounds`.
- **Review Focus 3:** deleting the selected preset in ItemView goes to list view.

- [ ] **Step 1: Write the failing tests.**
  - At 1100: both panes show.
  - At 700 with a preset selected: "Apply" and "More" are displayed, and "Duplicate" is in the More menu.
  - At 700, "Show list" shows the toolbar's "Import".
  - At 400: flag cells are single-column (two cells share no row y).
  - Deleting the selected preset → list view.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them plus `PresetsScreenshotTest` and the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(presets): strip, switcher chip, adaptive header and flag grid`

---

### Task 10: Actions screen and editor

**Files:**
- Modify:
  - `ui/actions/ActionsScreen.kt`: the pane call (293–297), list rows, the selection header (428–430), and the empty-state buttons (309–313) → `FlowRow`.
  - `ui/actions/ActionEditor.kt`:
    - the header (~110)
    - the type segments
    - "On when output" (226)
    - Value + Test read (228–233)
    - the variable card (345–350)
    - the output segments
    - the test-output header (434–441)
    - the bottom bar (139–154)
- Test: `ui/actions/ActionsNarrowTest.kt`

**Interfaces:** consumes the same as Task 8, plus `AdaptiveSegmentedButtons`, `SegmentOption`, `ControlSize`.

Changes:
- **List-detail:** `AdaptiveListDetail(listMinWidth = 260.dp, listPreferredWidth = 320.dp, hasSelection = editing != null)`.
  - Strip avatar: the action's own icon.
  - Strip actions: "+ New" as an icon button (content description "New action") that opens the same New menu.
  - The selection highlight skips the drag handle.
  - Switcher chip and item switch keyed by action id.
- **Editor header:** `PriorityBar`:
  - Title: p0, grow, minWidth 90.
  - "Unsaved changes": p1.
- **Type segments (Toggle / Run once) and Output (Toast / Shell tab / None):** `AdaptiveSegmentedButtons`, with their size picked by `rowFit` against the pane width:
  - Wrap each in `AdaptiveRow` with an empty text slot.
  - Or call `rowFit` with `textMin = 0` inside a `BoxWithConstraints`. The builder chooses one and uses it for both.
- **"On when output":** `AdaptiveSegmentedButtons(fillWidth = true, maxWidth = 420.dp)`, sized the same way.
- **Value + Test read:** `AdaptiveRow(textMin = 160.dp, gap = 10.dp)`.
- **Variable card:** `{name}` `widthIn(max = 120.dp)`, Label (weight), Asks with `widthIn(max = 170.dp)`. Stacked (each full width) when the card content < 440 dp, animated.
- **Test-output header:** the note gets `weight(1f)` + `maxLines = 1`. "Showing last N lines" is p1 in a `PriorityBar`.
- **Bottom bar:** `PriorityBar`:
  - Test run: p0.
  - Device picker: p3, `widthIn(max = 260.dp)`, ellipsized label.
  - Spacer.
  - Delete: p2.
  - Save: p0.
- **Review Focus 3:** deleting the open action goes to list view.

- [ ] **Step 1: Write the failing tests.**
  - At 1000: both panes show.
  - At 700 editing a Toggle action: "Save" and "Test run" are displayed, and "Value" plus "Test read" are both displayed with Test read's top ≥ Value's bottom.
  - At 360: "On when output" shows a menu button with "equals".
  - The empty state at 400 has all three buttons displayed.
  - Deleting the open action → list view.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them plus `ActionsScreenshotTest` and the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(actions): strip, switcher chip, adaptive editor rows, segments and bars`

---

### Task 11: Settings (strip with category icons, rows, controls) and remove the old pane code

**Files:**
- Modify:
  - `ui/settings/SettingsScreen.kt` (`SettingsContent` Row → `AdaptiveListDetail`)
  - `ui/settings/SettingsNav.kt`, or wherever `SettingsNav` lives (add `ListDetailPanes`)
  - `ui/settings/SettingsListPane.kt` (49, 105): a category icon per row and `maxLines = 1` titles
  - `ui/settings/SettingsRegistry.kt` (`SettingsCategory` gets an `icon: ImageVector`)
  - `ui/settings/SettingsDetailPane.kt:70-91` (`SettingRow`)
  - `ui/settings/SettingsControls.kt`: Theme at 43, folder at 84, adb at 111–124, preset button at 136
  - `ui/settings/PalettePicker.kt`: header rows at 306/366, slider rows at 418, swatches at 270
  - `ui/settings/StylePicker.kt`: 91, 117, 220
  - `ui/App.kt`: hoist the Settings `ListDetailPanes` like the others
- Delete: `ResizableListDetail`, `DetailBackButton` and the `ListDetailPaneScaffold` use in `ui/common/ListDetailPanes.kt`.
- Delete or replace: `ui/common/ResizableListDetailTest.kt` (covered by `AdaptiveListDetailTest`).
- Test: `ui/settings/SettingsNarrowTest.kt`

**Interfaces:** consumes the same as Task 10.

Changes:
- **Category icons:**
  - Appearance = `Icons.Outlined.Palette`
  - Mirroring = `Icons.Outlined.CastConnected`
  - adb = `Icons.Outlined.Terminal`
  - AI = `Icons.Outlined.AutoAwesome`
  - Updates = `Icons.Outlined.SystemUpdateAlt`
  - Help = `Icons.Outlined.HelpOutline` (automirrored)
  - Shown leading in the full list and as strip avatars. The update badge moves onto the Updates avatar as `badgeColor`.
- **List-detail:** `AdaptiveListDetail(listMinWidth = 200.dp, listPreferredWidth = 200.dp, hasSelection = true, resizable = false)`.
  - The category `AnimatedContent` keeps `itemSwitchTransform`.
  - The search field shows only in `Full`.
  - Typing in list view filters and stays in list view (do not call `openDetail` from `onQuery`).
  - Picking a category calls `openDetail()`.
  - Switcher chip in the detail title row. Selection highlight on the categories, keeping the 3 dp accent bar on the highlight.
- **`SettingRow` Inline layout → `AdaptiveRow(textMin = 160.dp)`.** Entries whose content is a segmented control render `AdaptiveSegmentedButtons(size = it)` from the row's `ControlSize`. Change the entry content lambda to receive `ControlSize`. Other controls ignore it.
- **Field + button rows** (capture folder, adb path): `AdaptiveRow(textMin = 160.dp, fillControlWhenStacked = true)`.
- **Default preset button:** `widthIn(max = 220.dp)`, and its inner label gets `maxLines = 1` + ellipsis.
- **Palette:**
  - Header rows: title weight + `maxLines = 1`.
  - Slider rows: the label goes above the slider when the slider would get < 120 dp. Use `AdaptiveRow` with the label as `text` and `textMin` set so the slider keeps 120 dp, or a dedicated two-line layout (builder's call).
  - Role swatches: labels `maxLines = 1` + ellipsis.
- **StylePicker:** Retro colors row, Glass opacity row and blur row → `AdaptiveRow`.
- **Review Focus 5** is covered in the tests.

- [ ] **Step 1: Write the failing tests.**
  - At 1000: the category list and Appearance both show.
  - At 600 (item view): "Show list" exists, and the Theme row's "Theme" text width is ≥ 160 with the segmented control below it.
  - At 360: Theme shows the menu button "System".
  - In list view at 600, typing "dark" in search keeps list view and shows Appearance.
  - Clicking Appearance opens item view.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement, then delete the old pane code.**
- [ ] **Step 4: Run them, `SettingsScreenshotTest`, `StylesScreenshotTest`, `AppNavigationTest` and the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(settings): category icon strip, adaptive rows and controls; drop ResizableListDetail`

---

### Task 12: Rail labels and window minimums

**Files:**
- Modify:
  - `ui/navigation/TopLevelNavigation.kt` (rail items)
  - `ui/App.kt` (pass the window width)
  - `Main.kt:133` and `ui/window/AppWindow.kt:111` (main and Settings/other app windows: minimum)
  - The mirror window factory (`ui/mirror/MirrorWindow.kt` or where its `AppWindow` is created): minimum
- Test: `ui/navigation/RailLabelsTest.kt`

**Interfaces:**
- Produces:
  - `fun railShowsLabels(windowWidth: Dp): Boolean = windowWidth >= 540.dp`
  - `val MainWindowMinSize = DpSize(360.dp, 480.dp)`
  - `val MirrorWindowMinSize = DpSize(344.dp, 360.dp)`
  - An `AppWindow(minSize: DpSize? = null, …)` parameter.

Changes:
- **Rail:** labels in `AnimatedVisibility(fadeIn + expandVertically / fadeOut + shrinkVertically)`. The rail width animates from 80 to 60 dp with `layoutSpring`. The shared selection pill stays.
- **Minimum size:** apply it in `AppWindow` through the Tao window. Find the setter on `nucleusWindow.unsafe.taoWindow` or the Nucleus window API: the Nucleus jar has `dev/nucleusframework/window/internal/MinimumSizeSupportKt`. Look for a public `minimumSize` / `setMinInnerSize` on the window or `MaterialDecoratedWindow` and use the public one. If only AWT is exposed, use `window.minimumSize`. Record which API in a code comment.

- [ ] **Step 1: Write the failing tests.**
  - `railShowsLabels(539.dp) == false` and `railShowsLabels(540.dp) == true`.
  - A scene test of the rail at 500 wide: the "Devices" label text is not displayed, and the Devices icon is clickable.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement the rail change and window minimums.**
- [ ] **Step 4: Run them, `AppNavigationTest` and the full suite.** Expected: PASS.
- [ ] **Step 5: Manual check.** Run the app (`./gradlew :composeApp:run`) and drag the main window smaller. It stops at 360×480. Note the result in the commit body.
- [ ] **Step 6: Commit.** `feat(window): rail labels hide under 540 dp; main and mirror window minimums`

---

### Task 13: Mirror window (floating sheet and small fixes)

**Files:**
- Modify:
  - `ui/mirror/SheetWidths.kt`: add the placement function.
  - `ui/mirror/MirrorWindow.kt`: the content Row at 1095–1150, the toolbar at 1582, the Disconnected card at 1548.
  - `ui/mirror/QuickActionsSheet.kt:293`: the header subtitle.
  - `ui/mirror/ActionTiles.kt:318`: titles.
  - `ui/mirror/ShellTab.kt`: 145 and 256.
- Test: `ui/mirror/SheetPlacementTest.kt`, plus a scene test in `ui/mirror/MirrorNarrowTest.kt`.

**Interfaces:**
- Produces:
  - `enum class SheetPlacement { Docked, Floating, Full }`
  - `fun sheetPlacement(windowWidth: Dp, toolbarWidth: Dp, sheetWidth: Dp, sheetMin: Dp): SheetPlacement`:
    - `Docked` when `windowWidth >= toolbarWidth + STAGE_MIN_WIDTH + sheetWidth`.
    - Else `Full` when `windowWidth < toolbarWidth + sheetMin`.
    - Else `Floating`.

Changes:
- **Floating:**
  - The stage takes the full row (minus the toolbar). The toolbar moves to the window's end edge.
  - The sheet draws in a `Box` overlay anchored to the toolbar's start, with elevation shadow 8 dp, over a stage scrim (`scrim` at 0.32 alpha, fade 200 ms).
  - Placement changes animate the sheet's x offset and width with `layoutSpring`. The toolbar's position uses `animateBounds`.
- **Full:** the sheet width is `windowWidth - toolbarWidth`.
- **Window growth:** the existing sheet-open window growth (`SheetGrowth.kt`, `sheetFrameBounds`) keeps running first. Placement is computed from the resulting window width, so a window with screen room still grows and docks.
- **Disconnected card:** `widthIn(max = 260.dp)`.
- **Sheet header subtitle:** `maxLines = 1` + ellipsis.
- **Tile titles:** `maxLines = 2`.
- **ShellTab:**
  - LiveBar: the text gets `maxLines = 1`. "Pause" and "Copy" go into a `PriorityBar` (Pause p0, Copy p1), so Copy folds into ⋮ under about 360 dp.
  - Command row: `AdaptiveRow(textMin = 120.dp)` with Run/Stop as the control.
- **Per-tab placement (spec 6a, added 2026-11-13):** `sheetMin` is the current tab's minimum (`SheetWidths.default(tab)`: Actions 280, Apps 480, Shell 400, Files 440) and `sheetWidth` the tab's saved width. Switching tabs can change placement; that change animates like a resize.
- **Sheet tab control (spec 6a):** `SheetTabs` (`QuickActionsSheet.kt:348`) gets `fun sheetTabsCompact(width: Dp): Boolean = width < 340.dp`. Compact: each segment shows its icon (Actions `Bolt`, Apps `Apps`, Shell `Terminal`, Files `Folder`), the selected one also its label, and segment weights animate (selected 2.2, others 1) with `layoutSpring`. The highlight keeps following the selected segment's animated bounds. Shell's dot and the Files badge sit at the icon's top end. Each segment has a tooltip with its name.

- [ ] **Step 1: Write the failing tests.**
  - `sheetPlacement(600.dp, 64.dp, 280.dp, 280.dp) == Docked` (504 ≤ 600).
  - `sheetPlacement(503.dp, 64.dp, 280.dp, 280.dp) == Floating`.
  - `sheetPlacement(343.dp, 64.dp, 280.dp, 280.dp) == Full`.
  - Shell: `sheetPlacement(620.dp, 64.dp, 400.dp, 400.dp) == Floating`.
  - Files: `sheetPlacement(503.dp, 64.dp, 440.dp, 440.dp) == Full`; `sheetPlacement(663.dp, 64.dp, 440.dp, 440.dp) == Floating`.
  - Apps: `sheetPlacement(704.dp, 64.dp, 480.dp, 480.dp) == Docked`.
  - `sheetTabsCompact(339.dp)` is true, `sheetTabsCompact(340.dp)` false.
  - Scene test: the sheet at 280 dp on the Files tab with 2 transfers running: the "Files" label and the badge "2" are displayed and inside the sheet; at 440 dp all four labels are displayed.
  - Scene test (if `MirrorScreenshotTest`'s harness allows composing the content Row offscreen): at 450 wide with the sheet open, the sheet's right edge is ≤ 450 - 64 and the stage width is 450 - 64.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, `MirrorScreenshotTest`, the sheet-growth tests (`SheetGrowthTest` or equivalent) and the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mirror): quick actions sheet floats over the stage in narrow windows`

---

### Task 14: Dialogs

**Files:**
- Modify:
  - `ui/devices/WifiPairingDialog.kt`: 186, 243, 294, 331, 351, plus the tab row.
  - `ui/onboarding/OnboardingDialog.kt`: 60, 73, 82.
  - `ui/update/UpdateDialog.kt`: 105, 153.
- Test: `ui/DialogsNarrowTest.kt`

**Interfaces:**
- Produces: `@Composable fun Modifier.dialogWidth(designed: Dp): Modifier`, in `ui/common/AdaptiveMotion.kt` or a new `ui/common/DialogSizing.kt` (builder's call). It is `widthIn(max = designed)` + `fillMaxWidth()` inside a container padded 16 dp, with `heightIn(max = windowHeight - 32.dp)` + `verticalScroll` on the body.

Changes:
- **Pairing:**
  - The card uses `dialogWidth(560.dp)`. The body is no longer fixed at 500.
  - QR + steps stack when the body is < 420 dp. The QR shrinks to 150 dp when stacked.
  - The tab row is `PrimaryScrollableTabRow` when it doesn't fit, else unchanged.
  - The code field is `widthIn(max = 200.dp)`. The Port and Code fields are `widthIn(max = 140.dp)`.
- **Onboarding:**
  - `dialogWidth(560.dp)`. The page height is `heightIn(min = 370.dp)` when there is room, else it fits content and scrolls.
  - The footer is a `FlowRow`. Under 400 dp the page dots take a full line above the buttons.
- **Update:**
  - `dialogWidth(400.dp)`.
  - Under 340 dp the buttons stack full width, primary on top (`Column` order: Update now, Later, Release notes).
- **Motion:** stack changes use `animateBounds`.

- [ ] **Step 1: Write the failing tests** (render each dialog in a 360×640 scene):
  - Pairing: the card's width is ≤ 328, and the QR's bottom is ≤ the first step's top.
  - Onboarding: "Next" is displayed and inside 360.
  - Update: "Update now" is displayed, its width is ≥ 280, and it sits above "Later".
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, `OnboardingScreenshotTest`, `UpdateScreenshotTest` and the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(dialogs): dialogs shrink and stack in narrow windows`

---

### Task 16: Apps tab narrow (spec 6a, added 2026-11-13)

**Files:**
- Modify: `ui/mirror/AppsTab.kt`: `FilterRow` (328), the options sheet's info row (744) and `ActionButtons` (788).
- Test: `ui/mirror/AppsNarrowTest.kt`

**Interfaces:**
- Consumes: `layoutSpring`, `LocalReduceMotion` (Task 1). The sheet width reaches the tab as its own max width constraint (Task 13); read it with `BoxWithConstraints` or a layout modifier, not a new parameter.
- Produces: `fun appsFiltersCompact(width: Dp): Boolean = width < 400.dp`, `fun appOptionsCompact(width: Dp): Boolean = width < 360.dp`.

Changes:
- **Filters compact:** one `FilterChip` showing the current filter, its `Counter` and a drop-down arrow; it opens a `DropdownMenu` with All / User / System, each with its count and a check on the current one. Reload stays at the end. The three chips and the single chip cross-fade (`AnimatedContent`, fade 150 ms, size via `layoutSpring`).
- **Options compact:** the two `InfoBox`es stack (`Column`, 8 dp gap); `ActionButtons` becomes a 2 × 2 grid (Launch, Force stop / Copy, Uninstall) with each `SheetAction` laying its icon beside its label. Position changes use `animateBounds` with `layoutSpring`; none under reduced motion.
- Above 400 / 360 dp nothing changes.

- [ ] **Step 1: Write the failing tests.**
  - `appsFiltersCompact(399.dp)` true, `(400.dp)` false; `appOptionsCompact(359.dp)` true, `(360.dp)` false.
  - Scene, `AppsTabContent` in a 280 × 600 box with fake apps (reuse `AppExplorerTest`'s fakes): one node with text "All" is displayed, no "User" node is displayed until the chip is clicked, then "User" and "System" are displayed; Reload is inside 280.
  - Scene, options open at 280: "Launch" and "Uninstall" are displayed, "Copy" sits below "Launch", and "Version code" sits below "Version name".
  - Scene at 480: three filter chips displayed (unchanged).
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, `AppExplorerTest`, `AppsSearchFieldTest` and the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(apps): Apps tab reflows in a narrow sheet`

---

### Task 17: Files tab narrow (spec 6a, added 2026-11-13)

**Files:**
- Modify:
  - `ui/mirror/files/FilesTab.kt`: `Toolbar` (499), `Crumbs` (584), `FilterRow` (647), `FreeSpace` (702), `ColumnHeader` (718).
  - `ui/mirror/files/FileRow.kt`: `FileRow` (120).
  - `ui/mirror/files/SelectionBar.kt`: `Bar` (96).
  - `ui/mirror/files/TransfersTray.kt`: `TransferRow` (163).
  - `ui/mirror/files/FilesDialogs.kt`: `DialogCard` (177).
  - `ui/mirror/files/FilesDrop.kt`: the drop chip.
  - `ui/mirror/files/FilesMenus.kt`: folded items in the view menu and the selection menu.
- Test: `ui/mirror/files/FilesNarrowTest.kt`

**Interfaces:**
- Consumes: `PriorityBar`, `BarItem`, `BarSpacer` (Task 4); `layoutSpring`, `LocalReduceMotion` (Task 1).
- Produces, in `ui/mirror/files/FilesNarrow.kt`:
  - `data class FilesLayout(val foldRefreshAndNew: Boolean, val foldPush: Boolean, val spaceBelowFilter: Boolean, val modifiedBelow: Boolean, val sizeBelow: Boolean, val foldRenameDelete: Boolean, val trayStacked: Boolean, val dialogButtonsStacked: Boolean)`
  - `fun filesLayout(width: Dp): FilesLayout` with: fold Refresh + New folder `< 440`, fold Push `< 340`, space below filter `< 380`, Modified below `< 400`, Size below `< 330`, fold Rename + Delete `< 380`, tray stacked `< 400`, dialog buttons stacked `< 340`.

Changes:
- **Toolbar:** Back and Up, the crumbs (grow, `minWidth = 120.dp`), Refresh, New folder, Push and ⋮. Folded items leave the bar (fade) and show first in the ⋮ view menu, then a divider, then the existing items. Use the thresholds from `filesLayout`, not `PriorityBar` measuring, so the menu and the bar agree; `PriorityBar` may still host the bar if it takes a forced fold set, else a plain Row with `AnimatedVisibility` per item.
- **Free space below:** the filter field takes the full width; the meter fills a line under it with "41.2 GB free" at its end.
- **Rows:** a custom `Layout` (or two-row arrangement) keeps the same size and date `Text` nodes and moves them: Modified to a second line under the name, then Size before it ("4.1 MB · Nov 8, 2:32 PM"). Row height 40 → 52 dp, animated. Cells move with `animateBounds`. The header shows one sort chip (current key and direction) that opens the existing sort items.
- **Selection bar:** Rename and Delete fold into the bar's ⋮, first in the menu. Clear, the count and Pull stay.
- **Tray:** stacked transfer rows: name; bar; destination and progress text (may wrap to 2 lines); cancel stays at the end, centred.
- **Dialogs:** stacked buttons full width, the filled one on top.
- **Drop chip:** `widthIn(max = listWidth - 24.dp)`, folder name ellipsized.
- Above every threshold nothing changes. Keyboard shortcuts (F2, ⌫, ⇧⌘N, ⌘R) keep working when their buttons are folded.

- [ ] **Step 1: Write the failing tests.**
  - `filesLayout` at 439 / 440, 399 / 400, 379 / 380, 339 / 340, 329 / 330: each flag flips exactly at its threshold; at 440 every flag is false.
  - Scene, `FilesTab` at 280 × 640 with a fake listing (reuse `FilesTabTest`'s fakes): the crumb "Download" is displayed and ≥ 120 wide; "Refresh" is not displayed; opening ⋮ shows "Refresh", "New folder" and "Push files here…" before "Show hidden files".
  - Same scene: the row "IMG_20261108_143212.jpg" shows its name with width ≥ 160, and its date text's top is below the name's bottom.
  - With 2 rows selected at 280: "Pull" is displayed; "Rename" is not until the bar's ⋮ is opened.
  - With a running transfer at 280: its name, progress text and cancel button are all inside the sheet.
  - Delete dialog at 280: "Delete" sits above "Cancel", both ≥ 200 wide.
  - Scene at 440: the header shows "Size" and "Modified"; Refresh is displayed (unchanged).
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, every `ui/mirror/files` test class, `MirrorScreenshotTest` and the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(files): Files tab reflows in a narrow sheet`

---

### Task 15: Width-matrix tests and hand-check notes

Runs last, after Tasks 16 and 17.

**Files:**
- Create: `ui/NarrowWidthsTest.kt`
- Create: `docs/superpowers/notes/2026-11-12-narrow-layouts-hand-check.md`

- [ ] **Step 1: Write `NarrowWidthsTest`.**
  - For each of Devices (item view), Presets (item view), Actions (editor) and Settings (Appearance), at widths **360, 520, 700 and 1000**:
    - The screen's primary action node is displayed and its bounds are inside the scene width: Mirror / Apply / Save / the Theme control.
    - No `Text` node in the detail has a width < 40 px while its text is longer than 3 characters. This catches letter-per-line wrapping.
  - Reuse the fakes from Tasks 8–11.
  - The mirror sheet's Apps, Shell and Files tabs at sheet widths **280, 340, 400 and their minimum**: Pull (Files, with a selection), Run (Shell) and Reload (Apps) are displayed and inside the sheet, and the same short-text check holds.
- [ ] **Step 2: Run it.** Expected: PASS. If it fails, fix the owning task's code, not the test.
- [ ] **Step 3: Write the hand-check list.** For each screen, the transitions to watch on macOS:
  - two panes ↔ strip, strip ↔ list, the overlay, item switch
  - rows stacking, ⋮ folding, segments → icons → menu
  - mirror docked ↔ floating, dialog stacking
  - the window minimum
  - reduced motion on (System Settings › Accessibility › Display › Reduce motion): fades only
- [ ] **Step 4: Run the full suite.** Run `./gradlew :composeApp:jvmTest`. Expected: all pass.
- [ ] **Step 5: Commit.** `test: width matrix for every screen; narrow-layout hand-check list`
