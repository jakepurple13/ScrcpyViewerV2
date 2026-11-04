# Custom quick actions and adb shell in the mirror window

Date: 2026-11-04
Status: design, approved through the mockups at https://claude.ai/artifact/8qsRit5mptX5svVhYiWPFg (version 3)

## Intent

The mirror window's quick actions sheet (PR #18, #19) only offers a fixed set of toggles and actions. Users testing
devices want a quick way to run things: one-off `adb shell` commands with their output, and their own saved actions
they fire over and over. Saved actions must be shareable between people, so they export to and import from a file.

## Goals

- Run any `adb shell` command on the mirrored device from the sheet and see its output, including commands that
  stream (`logcat`, `top`, `getevent`).
- Let users create their own quick actions of two kinds: **toggle** and **run once**.
- Run-once commands may contain variables (`{package}`) that ask for a value when run.
- Export custom actions to a JSON file and import them from one.
- Edits made while a device is mirroring show up in the open mirror window immediately.

## Non-goals

- Hiding or reordering the built-in toggles and actions. They stay fixed, in today's order, above the custom ones.
- Per-device or per-model actions. Every custom action applies to every device.
- Variables in toggles.
- Running host-side `adb` commands (`adb install`, `adb reboot` typed by the user). The shell runs `adb shell` only.
- Running more than one console command at a time per device (see "What runs at once").

## Anti-goals

- Showing a saved action's command anywhere except the editor and the import preview. Tiles, tooltips, variable
  prompts, and console output name the action, never its command.
- Import running anything. Import only adds actions.
- The sheet's tab switch jumping or resizing without animation.

## Mirror window: the sheet

The sheet keeps its place (docked right of the side toolbar), header, and close button. It gains a two-segment tab
control under the header: **Actions** and **Shell**. The sheet remembers the last tab for the life of the app
(not persisted).

### Actions tab

- Tiles, two per row, in the style of Android quick settings. A tile shows an icon, the name, and a short state line.
  It never shows a command, and hovering adds nothing.
- Toggle tiles fill with the accent color when on, and show "On" / "Off" (or no state line while unknown).
- Run-once tiles show a ▶ mark. The state line hints at what happens on tap: "Asks for <first variable's label>" when
  it has variables, else "Asks first" when confirm is on, else nothing. Built-ins keep "Open" and "Asks first".
- A tile whose command is running shows a spinner in place of its icon and ignores taps.
- Order: section label "Built in", the built-in tiles (today's five toggles, Developer options, Reboot), then a divider
  labelled "Your actions", the custom tiles in the user's order, then a dashed **+ New action** tile last. When there
  are no custom actions, the divider and the + New action tile still show.
- Reboot keeps its confirm dialog and error color.
- Right-click (secondary click) on a custom tile opens a menu: **Edit…**, **Duplicate**, **Delete**. Built-in tiles have
  no menu. Delete asks for confirmation.
- **+ New action** and **Edit…** bring the main window to the front, select the Actions rail item, and open the editor
  (blank, or on that action).
- The header's overflow ⋮ has **Manage actions…**, which does the same with no action selected.
- A tile added or changed while the sheet is open appears with a short scale-and-fade pop-in.

Toggle behavior matches today's built-ins: the tile flips at once, runs the on or off command, and flips back with a
message when the command fails (exit code non-zero). Custom toggles with a read command are read in the same batched
shell call as the built-ins whenever the sheet opens. A toggle without a read command shows the state it was last set
to (remembered per device, per action, for the life of the app; unknown before the first tap).

Tapping a run-once tile:

1. If it has variables, a small dialog over the sheet asks for each one (see Variables). Cancel aborts.
2. If "Confirm before running" is on, a confirm dialog names the action. It does not show the command.
3. It runs. Output goes where the action says:
   - **Toast**: the first non-blank line of output (or "Done" when empty) in the window's existing notify message.
     A failure shows the error the same way the built-ins do.
   - **Shell tab**: the sheet switches to the Shell tab and the console prints `▶ <action name>` followed by the output.
   - **None**: only failures are shown, as a toast.

### Shell tab

The sheet widens from 280 dp to 400 dp on this tab. The mirror window grows by the extra 120 dp the same way it grows
for the sheet today (stage width pinned, window right edge animates out, moves left when it would leave the screen;
maximized and fullscreen windows take the width from the stage instead).

Top to bottom:

1. **Filter field**: placeholder "Filter output". Hides console lines that don't contain the text (case-insensitive,
   plain substring) and highlights the matches. Shows "n of total" and a clear ✕ while non-empty. Esc clears it when
   focused. Applies to finished and live output.
2. **Live bar** (only while a command streams): red dot, "Streaming · <n> lines", **Pause** (stops auto-scroll; output
   keeps arriving), **Copy** (copies the visible, filtered lines).
3. **Console**: monospace scrollback. Each run starts with a header line: `$ <command>` for typed commands,
   `▶ <action name>` for saved actions, and when finished `exit <code> · <ms> ms`. Non-zero exits are colored as errors.
   Auto-scrolls to the bottom unless the user scrolled up or paused. Keeps the last 5,000 lines; older lines drop.
   Text is selectable. A Copy and a Clear control live in the console's corner.
4. **Chips**: one per custom run-once action, labelled with its name. Clicking runs that action exactly as tapping its
   tile would (variables, confirm), with its output forced to the console.
5. **Command field**: `$` prefix, placeholder "Type an adb shell command", **Run** button. Enter runs. Up / Down
   recall history (last 100 commands, in memory for the life of the app, shared across devices). The field takes focus
   when the user switches to the Shell tab.

While a command runs, Run becomes a red **■ Stop** and `Ctrl+C` in the field (when no text is selected) stops it. New
commands cannot start until it ends. The Shell tab label shows a red dot while a command is still running, so the user
notices from the Actions tab. Closing the sheet, the window, or losing the connection stops it.

The console content and running command belong to the mirror window (one per device) and survive switching tabs and
closing/reopening the sheet, but a running command is stopped when the sheet closes.

### What runs at once (builder's-check call)

- The one-at-a-time limit covers the **console** only: typed commands, chips, and run-once actions whose output is
  Shell tab. Starting one of these while a console command runs is refused with a message ("Stop the running
  command first"); nothing queues.
- Tiles with Toast or None output, toggles, and the batched state read stay independent of the console and of each
  other, as built-in toggles are today. They keep working while `logcat` streams.
- Toast / None actions use the buffered `AdbClient.shell` path with a fixed 30 s overall limit (in addition to its
  idle read timeout). On timeout the tile shows "Timed out after 30 s; set Output to Shell tab for long commands".
  So the tile spinner always ends. They are not cancelled when the sheet closes; they finish or time out.
- A long or streaming command belongs in an action with Output set to Shell tab, where Stop exists. The editor shows
  that hint under the Output field.
- Editor "Test run" and "Test read" use the same buffered path with the same 30 s limit and no streaming; a Test run
  of a Shell-tab action shows the first 30 s of output, then stops it and says so.

### Tab animation: Slide

- Tab highlight glides to the selected segment.
- Outgoing content moves 40 dp toward the side it leaves from (left when going to Shell, right when going back) while
  fading out; incoming content enters from the other side, fading in, slightly delayed (~60 ms).
- Sheet and window width animate 280 ↔ 400 dp at the same time.
- Timing: to Shell 260 ms with the existing emphasized-decelerate easing, back to Actions 200 ms with
  emphasized-accelerate (`quickActionsSpec(open)` in `QuickActionsSheet.kt`).
- Reference implementation of the feel: the "Slide" demo in the mockup page.

## Main window: Actions rail item

A new `TopLevelRoute.Actions`, labelled "Actions", placed between Presets and Settings in the rail.

Layout: list on the left, editor on the right, following the existing Presets screen's patterns.

- **List**: each custom action's name and a type line ("Toggle", "Run once · 1 variable", "Run once · to Shell").
  A drag handle reorders; the mirror sheet uses the same order. Under the list: **+ New**, **Import**, **Export**.
  A **?** button in the list header ("How actions work") opens the intro dialog; Export switches the list to
  selection mode (see Import and export).
- **Empty state**: a short explanation and + New / Import.
- **Editor** fields:
  - Type segment: **Toggle** / **Run once**. Switching type keeps the name.
  - **Icon** button left of the Name field, opening the icon picker (see Icons).
  - Name (required, unique case-insensitive).
  - Toggle: **Turn on** command, **Turn off** command (both required), **Read state** command (optional), and when a
    read command is set, **On when output** with a mode (equals / contains / matches regex) and a value. Output is
    trimmed before matching. "Test read" runs the read command on the chosen device and shows the raw output and
    whether it counts as on or off.
  - Run once: **Command** (required), **Variables found in the command** (one row per distinct `{name}`, see below),
    **Output**: Toast / Shell tab / None, **Confirm before running** checkbox.
  - Bottom row: **Test run on <device> ▾** (connected devices; disabled with none connected), **Delete**, **Save**.
    Test run shows output under the editor and prompts for variables like a real run.
- Save is explicit. Leaving with unsaved changes asks to discard.
- **Intro dialog**: a four-page dialog in the onboarding dialog's style (Your own quick actions; Toggle or run once;
  Variables; Test, then share). It opens the first time the Actions page is shown; `actionsIntroSeen` in settings
  records it (false for new installs and for settings files without the key, so existing users see it once;
  finishing or closing sets it). The **?** button reopens it.
- **Animations** (260 ms emphasized-decelerate in, 200 ms emphasized-accelerate out, as in the sheet): the "Variables
  found in the command" section expands and fades in when the command gains its first `{name}` and collapses when the
  last one goes, and each variable row animates in or out as its variable is added or removed. The Test output panel
  expands and fades in when Test run / Test read first produces a result and collapses when the result clears.
- Saving updates every open mirror window at once (the store is a shared singleton exposed as a `StateFlow`).

## Icons

- A fixed set of 48 Material icons (`compose.materialIconsExtended`, already a dependency), referenced in code from a
  name-keyed list so the shrink-only release build keeps exactly these. No reflection, no full catalog.
- Groups (8 each), names in snake_case as stored:
  - Connectivity: wifi, wifi_off, bluetooth, signal_cellular_alt, airplanemode_active, nfc, vpn_key, network_check
  - Device: battery_full, battery_saver, brightness_6, volume_up, volume_off, screen_rotation, lock, power_settings_new
  - Display and input: dark_mode, light_mode, touch_app, visibility, screenshot, keyboard, text_fields, smartphone
  - Apps: apps, android, install_mobile, delete, cleaning_services, open_in_new, restart_alt, download
  - Debug: bug_report, terminal, code, speed, memory, developer_mode, analytics, list_alt
  - General: toggle_on, play_arrow, bolt, refresh, schedule, location_on, notifications, photo_camera
- Picker: popover from the editor's icon button, with a search field (matches names and a few keywords per icon,
  e.g. "logs" finds terminal), group labels, an 8-column grid, the selected icon filled with the accent color.
  Arrow keys move, Enter picks, Esc closes. The tile preview in the mockup updates live.
- Defaults: toggle_on for new toggles, play_arrow for new run-once actions.
- Stored as `"icon": "<name>"` in `actions.json` and the export file. Unknown or missing names fall back to the type's
  default.
- Mockup: section 5 of the mockup page.

## Variables

- Syntax: `{name}` where name is `[A-Za-z_][A-Za-z0-9_]*`. `{{` and `}}` produce literal braces. Anything else in
  braces is left as-is.
- Only run-once commands. In a toggle's commands, braces are literal text.
- The editor detects variables live and shows one row per distinct name, with **Label** (defaults to the name) and
  **Asks with**:
  - **Text**: free text, optional default.
  - **List**: choices the user types, comma-separated.
  - **Installed app**: searchable list from `pm list packages` on the target device, third-party only by default with
    a "Show system apps" switch.
- At run time a small dialog asks for every variable in one form, prefilled with the last value used for that action
  and variable on that device (in memory for the life of the app), else the default. It does not show the command.
- Values are substituted as-is. The builder must quote nothing automatically; it is the user's command.

## Import and export

- **Export** uses selection in the list. Clicking Export puts the list in selection mode: every row shows a checkbox,
  all ticked, and a row click toggles its checkbox instead of opening the editor; drag reorder is off. The list header
  shows "n selected" with **Select all** and **None**. The footer swaps New / Import / Export for **Export n**
  (disabled at 0) and **Cancel**. Export n writes only the ticked actions, in list order, to one file the user picks
  (`scrcpy-actions.json` suggested), through the same AWT file dialog pattern as presets (`PresetFileDialogs`), then
  leaves selection mode. Cancel leaves it. Unsaved editor changes are not touched.
- **Import** reads a file and shows a preview dialog before changing anything:
  - A warning: "These run shell commands on your devices. Check each one."
  - One row per incoming action with an include checkbox, its name, and **its commands**. This is the one place besides
    the editor that shows commands, by the user's decision, so they can check what they are adding.
  - Name clashes are flagged; per-row choice of Replace / Keep both / Skip, as in the preset `ImportPlan`.
  - Import never runs anything.
- Malformed files show an error naming what was wrong; unknown fields are ignored; unknown `version` > supported is
  rejected with a message.

File format:

```json
{
  "format": "scrcpy-viewer.actions",
  "version": 1,
  "actions": [
    { "id": "…", "name": "Stay awake", "type": "toggle", "icon": "battery_full",
      "on": "svc power stayon true", "off": "svc power stayon false",
      "read": "settings get global stay_on_while_plugged_in",
      "onWhen": { "mode": "equals", "value": "7" } },
    { "id": "…", "name": "Clear app data", "type": "once",
      "command": "pm clear {package}", "output": "toast", "confirm": true,
      "variables": { "package": { "label": "App to clear", "kind": "app" } } }
  ]
}
```

Imported actions get new ids when they are added as new (not when replacing).

## Constraints from the existing code

- `AdbClient.shell` buffers all output and has a 10 s socket read timeout. Streaming needs a new API that emits output
  as it arrives (for example a `Flow` of lines or chunks) with no read timeout and that closes the socket on cancel.
- The legacy shell service drops the exit code; keep the existing `echo __scrcpyviewer_exit=$?` marker approach for
  finished commands. For streamed commands, show the exit code when the marker arrives, or none when stopped.
- Storage follows the `PresetStore` / `JsonFile` pattern: a `@Singleton` store in `StorageModule` (`di/AppModule.kt`), persisted to
  `actions.json` under `DataDir`, synchronous small writes, `persistenceError` surfaced.
- The window-growth logic in `MirrorWindow.kt` assumes one width (`QUICK_ACTIONS_WIDTH`); it must animate between
  three widths (closed 0, Actions 280, Shell 400).
- The mirror window needs a way to bring the main window forward and select the Actions route with an action id.
- Existing built-in behavior (`QuickToggle`, `DeviceQuickActions`, Reboot confirm, Developer options) stays as is,
  restyled as tiles.

## Left to the builder

- Exact copy for empty states, errors, and state lines, in the app's existing voice.
- Internal structure: models, store, view models, and how the shell session is owned per mirror window.
- Test strategy, following the repo's existing jvmTest patterns (fake `AdbClient`, screenshot tests).
