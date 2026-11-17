# Narrow layouts: hand-check list (macOS)

Drag each window's edge slowly, then quickly. Everything should glide, never jump or clip. Spec: `docs/superpowers/specs/2026-11-12-narrow-window-layouts-design.md`. The automated width matrix is `NarrowWidthsTest`.

## Main window (all screens)
- Minimum size is 360 x 480 dp: drag to it, nothing goes under it.
- Nav rail: text labels at 540 dp and wider, icons only below; the width glides.
- Main's screens move with the shared spring: info cards, follow strip, sheet tabs, markup toolbar.
- Reduced motion on (System Settings > Accessibility > Display > Reduce motion): fades only, no slides.

## Devices
- Default 800 dp window opens on the list alone, even with a device selected from last time.
- 840 dp and wider: two panes. Narrower: list first.
- Click a device: its detail slides in from the end edge, the list shifts about 22% and dims.
- Back arrow, Esc and Cmd+[ all slide it out. The selection stays; widening shows both panes.
- Narrowing with a device open keeps it open; narrowing with the list shown stays on the list.
- Rows stack their texts; the toolbar folds into the more menu; segments become icons, then a menu.
- Search folds into the menu; its popup closes on widening.
- Info cards: animateHeight rides on the children's glides, no extra lag.
- Deleting or losing the open device returns to the list.

## Presets
- Same push, Back, Esc, Cmd+[ and launch-on-list behavior as Devices.
- Flag grid drops to one column at phone width.
- Footer and header fold their buttons; Apply never folds.
- Deleting the open preset returns to the list.

## Actions
- Same push, Back, Esc, Cmd+[ and launch-on-list behavior.
- Reorder handle shows the hand cursor. The move cursor crashed Tao on this macOS, so it must not appear.
- Editor tabs become a menu when narrow; Save stays visible.
- Deleting the open action returns to the list.

## Settings
- No push. The sidebar is always beside the page.
- 840 dp and wider: 200 dp. From 840 down to 480: it shrinks smoothly from 200 to 136 dp, names ellipsize, search stays a field.
- Under 480 dp: it turns into a 64 dp column of icons with name tooltips; the swap springs. Selected icon keeps its highlight and bar; the update badge sits on Updates.
- Search button in the icon column opens the field as a popup; typing filters, Esc closes.
- Appearance: the Theme segments sit beside the text when wide, under it when narrower, a menu button at phone width.
- Launch and quit tiles shrink to the row and glide when they wrap.

## Mirror window
- Minimum width 344 dp (toolbar plus Actions sheet minimum), height 360 dp: drag to it.
- Sheet docked to floating and back as the window narrows and widens.
- Dialogs stack their buttons when narrow.
- Sheet tabs: segments show labels only when wide; the selected one stays wider.
- Apps tab: filters collapse to one chip with a menu; options sheet stacks boxes and actions.
- Shell tab: the live bar folds Copy into the menu; Pause stays.
- Files tab: breadcrumb keeps room, buttons fold into the view menu, Pull stays with a selection.
- Links tab: dropdowns stack under 300 dp; Open on device stays.
- Follow strip and markup toolbar glide with the spring.

## Logcat window
- Minimum size 360 x 360 dp: drag to it.
- Rows go full, then no IDs, then stacked as it narrows (600 and 420 dp).
- Toolbar goes compact under 420 dp: Play is an icon, Pause stays; the status bar folds.

## GIF export window
- Minimum 600 x 500: drag to it with a landscape clip; Export stays inside.
- The preview is capped so the controls keep 340 dp.

## Markup window
- Minimum 360 x 420: drag to it; toolbar glides and wraps.

## Cursors
- Pane resize cursors (list and detail divider, sheet drag bar) appear and do not crash Tao.
