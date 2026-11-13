# Narrow window layouts

Date: 2026-11-12
Status: Draft, awaiting user approval
Mockup (approved direction): https://claude.ai/artifact/UfUaFu8LacPbdangsuXvA3 (version 3, "With E" mode)
Earlier proposal comparison: https://claude.ai/artifact/9Eu48dUJwNEJXoKvhQjx1a

## Why

When the app window gets narrow, rows with text next to a wide control give the text almost no width.
The trigger was Settings › Appearance › Theme. At about 680 dp the segmented System / Light / Dark control
keeps its full ~300 dp, and Compose wraps "Theme" and its description one letter per line.
The same pattern shows up on every screen: text with `Modifier.weight(1f)` next to unweighted controls,
fixed widths (260 dp search, 420 dp segments, 140 dp value fields, 400–560 dp dialogs), button rows that
never wrap, and two-column grids that never collapse. No window sets a minimum size.

The user wants every screen to stay readable at any width, and animations are important to them:
every layout change in this work gets its own motion. Nothing may snap.

## Goals

- Every screen stays readable from its window minimum up: no text squeezed below its minimum width, no
  letter-per-line wrapping, and no buttons or panes clipped off the window edge.
- One set of shared layout rules (below) instead of per-screen patches, so new screens get them for free.
- Every state change in these rules is animated. The motion should match what the app already does,
  especially the rail's vertical shared-axis switch (3d11664) and the Settings category switch.
- Narrow list screens keep their list: the list alone, or an icon strip beside the open item.

## Non-goals

- Visual redesign of any screen at normal widths. Above the new breakpoints, screens look as they do today.
- Changing what any screen does, or its content.
- Phone or tablet form factors. This is about resizing desktop windows.

## Anti-goals (failure even if it "works")

- A layout that changes state with a jump or snap instead of motion.
- Hiding a primary action (Mirror, Apply, Save, Test run, Pair, Update now) behind ⋮.
- A narrow list screen that makes the list unreachable without going back first. The strip must stay visible
  next to an open item.
- A window minimum so large it stops the app sitting beside a mirror window. The old proposal D needed ~840 dp
  and was rejected for this reason.

## The rules

Each rule is a shared component or modifier in `ui/common/` (names are suggestions), used by every screen.
dp values are the starting values from the mockup. The builder may tune them after looking at the real app,
but has to keep the order of the steps.

### 1. Panes: list alone, or strip beside the item

Applies to Devices, Presets, Actions and Settings.

- **Two panes** when the content area fits the list's **minimum** width + divider + **420 dp** of detail.
  This is a fixed threshold per screen: it uses the list minimum, not the dragged width, so dragging the
  divider never flips modes. The existing `PaneWidthLimits` clamp keeps the detail ≥ 420 dp while dragging
  (*my call*). Today the detail minimums are 640 / 600 / 560 dp. Rules 2–5 make narrow detail panes work, so
  the threshold comes down. The mockup's approximate window widths were:
  - Devices: two panes from ~786 dp of window.
  - Presets: ~816 dp.
  - Actions: ~836 dp.
  - Settings: ~700 dp.
- **Crossing into narrow keeps what you're looking at** (*my call*). In two-pane mode, selecting an item
  counts as opening it (`detailOpen = true`), so shrinking past the threshold lands in item view, with the
  list animating to the strip. If nothing is selected, it lands in list view.
- **Settings joins with its own state, not the draggable scaffold** (*my call*). Settings keeps its fixed
  200 dp list and no divider drag (no visible change at normal widths). It gets the same `detailOpen` flag
  and the same three presentations (two panes, list view, item view) through the shared strip and overlay
  components. Typing in the list-view search filters the list and stays in list view. Picking a category
  opens it.
- **Below that, two views.** This keeps the existing `ListDetailNavigation.detailOpen` model in
  `ui/common/ListDetailPanes.kt`.
  - **List view** (`detailOpen == false`): the list alone at full width, same as today's single pane.
  - **Item view** (`detailOpen == true`): the detail, with the list shrunk to a **76 dp icon strip** on its
    left. This replaces today's back-arrow-only single pane.
- **Strip contents**, per screen. Each item is a 40 dp circular avatar with an 11 sp name under it, one line,
  ellipsized, plus a tooltip with the full name.
  - **Devices:** phone or tablet icon, with the status dot (ok / needs attention) as a badge.
  - **Presets:** two-letter initials of the preset name. The default preset's badge is the builder's call.
  - **Actions:** the action's own icon.
  - **Settings:** one icon per category: Appearance = palette, Mirroring = cast/screen, adb = terminal,
    AI = sparkle, Updates = download, Help = help. Settings has to get these icons; it has none today.
  - **Selection:** the selected item's avatar uses primary / onPrimary.
  - **Top:** a `›` button ("Show list") at the top of the strip goes back to list view.
  - **List actions:** list-level buttons become icon-only in the strip ("Pair over Wi-Fi", "+ New preset",
    "+ New"). The rest move off the strip:
    - Import and Export appear only in the full list.
    - Devices' refresh stays as an icon.
    - The Settings search field is hidden in the strip and shows in list view.
  - Clicking a strip item switches the detail to it (rule 1 motion, below).
- **Switcher chip:** in item view a chip in the detail header ("⇅ Pixel 8 ▾") opens the full list as an
  overlay over the detail, with a scrim. Picking an item switches to it and closes the overlay.
  Clicking the scrim closes it. This replaces today's back arrow at the top of the detail.
- **Rail:** the navigation rail drops its labels below a **540 dp** window. The icons and the selection pill
  stay.
- **Motion:**
  - **List view to item view:** the list's width animates down to the strip. Rows morph into avatars
    (text fades, rows reflow), and the detail slides in from the right (~48 dp, fade).
  - **Item view to list view:** the reverse.
  - **Overlay:** opens by animating the list's width out from the strip (or from 0), and closes the same way
    in reverse.
  - **Rail labels:** fade and collapse.
  - **Crossing the two-pane threshold:** the list width animates between its pane width and the strip. It
    never jumps.
  - **Switching items (all four screens):** the selection highlight slides from the old row to the new one.
    In the strip, the avatars swap colour. The detail does a vertical shared-axis transition: it moves up and
    fades when the new item is lower in the list, and down when it is higher. Use the same spec as
    `SettingsContent`'s category `AnimatedContent` (`ui/settings/SettingsScreen.kt`):
    `fadeIn(220, delay 60) + slideInVertically(it/12)` with `fadeOut(120) + slideOutVertically(-it/24)`.
    Devices, Presets and Actions do not animate item switches today. They get it in all layouts, wide
    included.

### 2. Rows: text keeps its room

A shared adaptive row: text content, then a trailing control.

- The text gets at least **160 dp** (140 dp for option rows, 90 dp for preset flag cells, 80 dp for a device
  name next to its status chip).
- When the control's intrinsic width would leave the text less than that, the control moves **under** the
  text: start-aligned, or full width for text fields.
- Motion: the control and text glide to their new slots and the row height springs. Use `LookaheadScope` with
  `Modifier.animateBounds` or an equivalent. The mockup used FLIP with a slight-overshoot spring, ~420 ms.
- Applies to:
  - Settings rows: `SettingRow` in `SettingsDetailPane.kt:83`, the Inline layout.
  - `OptionRow` (`ui/common/OptionRow.kt:77`): Devices advanced and preset edit.
  - Device list title + status chip (`DevicesScreen.kt:472`).
  - Preset flag cells (`PresetsScreen.kt:359`).
  - Action editor Value + Test read (`ActionEditor.kt:228`).
  - Capture folder field + Choose…, and adb path field + Browse… + Use (`SettingsControls.kt:84, 111`).
  - Default preset dropdown (`SettingsControls.kt:136`).
  - Palette slider rows (`PalettePicker.kt:418`): the label moves above the slider when the slider would get
    less than 120 dp.
  - Glass/blur option rows (`StylePicker.kt:117, 220`).
  - Retro colors row (`StylePicker.kt:91`).
  - Shell LiveBar and command row (`ShellTab.kt:145, 256`).

### 3. Action bars: the primary stays, the rest folds into ⋮

A shared bar where each item has a rank. When the items don't fit, the lowest-ranked one slides shut
(width and fade) and shows up in a ⋮ overflow menu that appears with a small pop. Then the next one, and so on.
This is how the mirror top strip already behaves (`MirrorWindow.kt:1170`). That code can be generalized or
reused.

| Bar | Never folds | Folds first → last |
|---|---|---|
| Devices advanced header (`DevicesScreen.kt:507`) | Title (min 110 dp) | Simple view, then Search (search opens from ⋮) |
| Devices footer (`DevicesScreen.kt:671`) | Mirror | Save as new preset, Preset chip, Save, Connect over Wi-Fi |
| Preset header (`PresetsScreen.kt:305`) | Name (min 110 dp), Apply | Duplicate, Export, Delete, Edit, Default chip |
| Action editor bottom (`ActionEditor.kt:139`) | Test run, Save | Device picker (ellipsizes first), Delete |
| Action editor header | Title | "Unsaved changes" |

### 4. Controls: segments shrink, then become a menu

Segmented button rows get three sizes, used in order, after rule 2 has stacked the row:

- **full:** icon + label.
- **icons:** labels slide shut. Icons stay, with a tooltip for each.
- **menu:** a dropdown showing the selected option's icon and label.

Motion: labels animate width to zero, and the swap to the menu cross-fades. Segments with no icons go
straight from full to menu. Applies to:
- Theme, Window animation and other Settings segmented rows.
- The action editor's "On when output" (`ActionEditor.kt:226`). It becomes fill-width with a 420 dp maximum
  instead of fixed 420 dp.
- Toggle / Run once, and Output.
- The quick actions sheet's Actions / Shell tabs, which are already fill-width and only need the menu step if
  they ever overflow.

### 5. Sizes: fixed widths become maximums, pairs stack

- **Fixed widths become maximums that shrink with the space** (`widthIn(max = …)` plus fill):
  - Devices search 260 dp.
  - Option value fields 140 dp.
  - Default preset button 220 dp.
  - Wi-Fi pairing code field 200 dp, and Port / Code fields 140 dp.
  - Action variable card's `{name}` 120 dp and "Asks with" 170 dp.
- **Two-column grids go to one column:**
  - Devices option columns, under 560 dp of pane.
  - Preset flag grid, under 520 dp.
  - Palette role swatches, 5 columns, wrap or shrink labels (builder's call).
- **Tile pickers wrap, and the wrap animates** (added 2026-11-13, after main gained the Launch & quit picker):
  - Launch & quit animation (`LaunchQuitPicker.kt`): 3 tiles, 200 dp each, in a `FlowRow`. 200 dp becomes a
    maximum: a tile is `min(200 dp, row width)` wide, so a single column never clips.
  - Style (`StylePicker.kt:71`): 124 dp tiles in a `FlowRow`, same wrap motion (*my call*, for consistency).
  - When the window narrows or widens and a tile changes row, it glides to its new slot and the picker's
    height springs, both ways. Same spring and `LookaheadScope` + `animateBounds` as rule 2. The tile's
    looping preview keeps playing while it moves. Reduced motion: tiles jump.
- **Side-by-side pairs stack:**
  - Preset edit Name + Description, under 480 dp.
  - Action variable card, under 440 dp.
  - Wi-Fi pairing QR + steps, under 420 dp of dialog.
- **Dialogs:** width = `min(designed width, window − 32 dp)`, and height follows content and scrolls inside
  `window − 32 dp`.
  - Wi-Fi pairing (`WifiPairingDialog.kt:186`): 560 dp design width, body no longer fixed at 500.
  - Onboarding (`OnboardingDialog.kt:60`): 560 dp, page height no longer fixed at 370. Under 400 dp the page
    dots move onto their own line above the buttons.
  - Update (`UpdateDialog.kt:105`): 400 dp. Under 340 dp the buttons stack full width with the primary on top.
  - The pairing dialog's tab row scrolls horizontally if needed.
- **Motion:** stacking and column changes animate positions and heights like rule 2. The dialog size follows
  the window live.

### 6. Mirror window: the sheet floats instead of pushing

- Today the 64 dp toolbar, the 160 dp stage minimum and the sheet (280 dp Actions / 400 dp Shell) can need
  more than the window width, and the sheet runs off the edge (`MirrorWindow.kt:1588`, `SheetWidths.kt:46`).
- **When toolbar + 160 dp + sheet width does not fit:** the sheet becomes an overlay. It slides over the
  stage, anchored next to the toolbar, with a shadow and a scrim on the stage. The toolbar moves to the window
  edge.
- **When the window is under toolbar + sheet minimum:** the sheet covers the full width beside the toolbar.
- **Motion:** the sheet slides between docked and floating. The scrim fades.
- **Sheet header** (`QuickActionsSheet.kt:293`): the subtitle is one line with an ellipsis.
- **Tile titles** (`ActionTiles.kt:224`): up to two lines.
- **Disconnected card** (`MirrorWindow.kt:1548`): fixed 260 dp becomes a maximum.
- **Unchanged:** the top strip and status line already drop items by width.
- **Interaction with existing sheet code:** this has to keep working with the existing sheet-open
  window-growth logic (`SheetGrowth.kt`, `sheetFrameBounds`). The window still grows on open when there is
  screen room. Floating only applies when the window is already too narrow.

### 6a. Mirror sheet tabs below their minimums (added 2026-11-13)

Main (v1.17) gave the sheet four tabs: Actions (280 dp minimum), Apps (480), Shell (400) and Files (440).
Approved through the addendum mockup https://claude.ai/artifact/6wsGe9iFtdpu4JJPkzcTi7.

- **Placement** uses each tab's own minimum: docked while toolbar + 160 dp + that tab's width fit, floating
  below that, full width under toolbar + that tab's minimum. When full, the sheet can be as narrow as 280 dp
  (344 dp window), so every tab's inside reflows below its old minimum. Above the tab's minimum nothing
  changes. The docked minimums and the saved widths stay as they are.
- **Tab control** (`QuickActionsSheet.kt` `SheetTabs`): under 340 dp sheet width the segments show icons
  (Actions = bolt, Apps = grid, Shell = terminal, Files = folder). The selected segment keeps its label and
  takes more of the width; the change animates. Shell's red dot and the Files transfer badge sit on the icon.
  Tooltips give the names.
- **Shell:** under 360 dp Copy folds into ⋮. Pause, Run and Stop never fold.
- **Apps:**
  - Under 400 dp the All / User / System chips become one chip showing the current filter and its count,
    opening a menu with all three. Reload stays.
  - The card grid already reflows (2 columns at 280 dp); cards keep animating to new places.
  - Options sheet under 360 dp: the version boxes stack, and the four actions become a 2 × 2 grid with the
    icon beside the label. Launch stays first.
- **Files:**
  - Toolbar: Refresh and New folder fold into ⋮ under 440 dp, Push under 340 dp. Folded items show first
    in the menu. The path keeps at least 120 dp and still fades at its start.
  - Free space: under 380 dp it moves under the filter field as a full-width meter with the text at its end.
  - Rows: under 400 dp Modified moves to a second line under the name (rows grow from 40 to 52 dp); under
    330 dp Size joins that line ("4.1 MB · Nov 8, 2:32 PM"). The column header becomes one sort chip
    opening the sort menu. Cells glide to their new place.
  - Selection bar: under 380 dp Rename and Delete fold into the bar's ⋮ (first in the menu). Pull never folds.
  - Transfers tray: under 400 dp each transfer stacks: name, bar, then destination and progress.
  - Dialogs (delete, name clash): under 340 dp the buttons stack full width with the filled one on top.
  - The drop chip keeps a 12 dp margin and ellipsizes the folder name.
- **Motion:** as everywhere else: moves use `layoutSpring`, folds fade, reduced motion fades or snaps.

### Window minimums

A floor, not the fix:
- **Main window:** 360 × 480 dp.
- **Mirror window:** 344 dp wide (toolbar + Actions sheet minimum) × 360 dp.

Set them through the Tao/Nucleus window API (min inner size). Build on `main` at or after `007f35a`, where the
Tao window backend is merged. This branch has been fast-forwarded there.

## Constraints

- Compose Multiplatform 1.12.2, Kotlin 2.3.21, Material 3 adaptive (`ListDetailPaneScaffold` is already used
  by `ResizableListDetail`). Windows run on Nucleus Tao (`MaterialDecoratedWindow`) since v1.16.0.
- **Keep the existing list–detail model:** `ListDetailNavigation.detailOpen`, the draggable divider and
  `PaneWidthLimits` (min / preferred list widths). The strip is a third presentation of the list pane.
  Divider dragging only applies in two-pane mode.
- **Reduced motion** (*my call*): every new transition reads one shared reduce-motion source. Hoist the OS
  check that `MirrorWindowMotion.kt:285` already does into a shared helper. When it's on, movement becomes a
  short fade or an instant change. `animateMirrorWindows` stays specific to mirror window open/close.
- macOS, Windows and Linux. The hand check is on macOS. Windows and Linux are CI-verified.

## Testing (decided)

- Unit tests for the pure decisions:
  - Row stack/level choice from widths.
  - Bar fold order.
  - Pane mode (two panes / list view / item view) from window width and `detailOpen`.
  - Mirror sheet docked / floating / full, per tab minimum.
  - Sheet tab breakpoints (tab control, Apps, Files, Shell) from the sheet width.
- Compose UI tests (existing jvmTest setup) at **360, 520, 700 and 1000 dp** for each top-level screen.
  Assert that the primary actions are displayed and not clipped, and that no row's text gets less than its
  minimum width.
- Manual macOS hand check: resize each screen through the breakpoints and watch every transition.

## Left to the builder

- Exact component APIs, names and file split in `ui/common/`.
- Whether the adaptive row is a custom `Layout`, `SubcomposeLayout` or `BoxWithConstraints`.
- Animation specs, within "spring with slight overshoot for movement, ~400 ms; quick fades for content
  swaps". Reuse the existing motion files' specs where they fit.
- Whether to build `animateBounds` on `LookaheadScope` or a custom approach, if `animateBounds` misbehaves
  on desktop.
- Tuning the dp thresholds by eye in the real app, keeping the step order.
- The default-preset marker in the strip, and how palette role swatches handle narrow widths.
- Task order and how to split the work into PRs.
