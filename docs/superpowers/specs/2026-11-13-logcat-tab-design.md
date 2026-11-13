# Logcat Tab Design

Mockup (approved 2026-11-13): https://claude.ai/artifact/JJMzdujpjJwUbnTXq7Vxxv

## Goal

A fifth tab in the mirror window's quick actions sheet, after Files, that reads `adb logcat` for the window's device,
colors each level, and filters with Android Studio's Logcat query language. Today the only way to see logcat is the
Shell tab's raw text or the "Logcat for app" template.

## Behavior

### Running

- Opening the tab never starts `adb logcat`. The tab first shows a "Logcat isn't running" card with a large Play
  button: "Press Play to read this device's log. It stops when you leave this tab."
- **Play** (first time): starts `logcat -v threadtime -T 1000` (the last 1,000 lines, then live). The card fades and
  scales away.
- **Pause**: stops the device command. A banner drops in: "Paused at HH:MM:SS".
- **Resume**: starts `logcat -v threadtime -T '<timestamp of the last line received>'`, drops lines already shown, and
  puts a dashed divider before the read-back lines: "Paused HH:MM:SS – HH:MM:SS".
- **Leaving the tab** (another tab, closing the sheet, device disconnect) stops the command, same as Pause, with the
  banner "Stopped when you left the tab · Resume to catch up". Coming back pulses the Resume button twice.
- Window closing ends everything. Nothing about Logcat is saved: not the lines, not the filter, not the picked app.
- Buffer: the last **20,000** lines per window; older ones drop off.
- **Clear** empties the window's list only (toast: "Cleared. Older lines stay on the device buffer.").

### Play/Pause button

Next to the filter field, 104 dp wide. Filled primary while stopped, tonal (surfaceContainerHighest) while running.
Label: Play (never started), Pause (running), Resume (paused). The icon morphs play ⇄ pause (rotate + scale + fade,
280 ms). Space toggles it when focus is not in a text field.

### Columns

Time (`HH:MM:SS.mmm`), PID-TID, Tag, Package, Level badge, Message. Compact view (toggle) drops PID-TID and Package and
shows time without milliseconds. When the sheet is narrower than 600 dp, PID-TID and Package hide regardless.

### Colors

- Level badge (letter on a rounded square) and message text color per level V, D, I, W, E, A (F shows as A). Fixed
  light and dark sets, picked from the scheme's surface luminance; values in the mockup's `--lv-*` and `--m-*` tokens.
- Each tag gets one of 8 fixed hues by a stable hash of the tag name (mockup `--t0`…`--t7`).
- Crash rows (`is:crash`) get a red-tinted background and flash once when they arrive.
- Filter words (`message:` and bare text terms) are highlighted in messages.

### Stack traces

Consecutive lines with the same pid, tid, tag and level that continue a trace fold into the line above: `at …`,
`Caused by: …`, `... N more`, `Suppressed: …`, an exception class line (`java.net.SocketTimeoutException: timeout`), and
everything after an `AndroidRuntime` `FATAL EXCEPTION:` line (the `Process:` line and the exception line). The row shows
"N lines" with a chevron; crashes start unfolded, others folded. Frames inside the picked app's package are links;
clicking one copies `File.kt:118` (toast "Copied File.kt:118").

### Filter

One text field; the query text is the only filter state. Chips and clicks edit the text.

| Term | Meaning |
|---|---|
| `tag:x`, `package:x`, `process:x`, `message:x` | field contains x (ignore case) |
| `key~:re` | regex match (ignore case) |
| `key=:x` | exact match |
| `-term` | excludes |
| `package:mine` | the app picked in the Apps tab; matches nothing when none is picked |
| `level:warn` | this level and above: verbose, debug, info, warn, error, assert (prefixes OK) |
| `age:30s` | newer than N s, m, h or d |
| `is:crash`, `is:stacktrace` | app crash; any line with a folded trace |
| bare word or `"quoted text"` | tag or message contains it |
| `a b` / `a \| b` | AND / OR (AND binds tighter) |

- Syntax colors in the field: keys primary, `-` error, `~`/`=`/`|` orange, quoted values blue.
- Invalid term (unknown key, bad level/age/is value, regex that doesn't compile): wavy error underline, red field
  border, a one-line hint under the field, and the term is ignored. Hints:
  - `Unknown key “x:”. Try tag:, package:, message:, level:, age: or is:.`
  - `level: expects verbose, debug, info, warn, error or assert.`
  - `age: expects a number and s, m, h or d, like 5m.`
  - `is: expects crash or stacktrace.`
  - `That regular expression doesn't parse yet.`
- Autocomplete under the caret's token: keys (with descriptions) when no `:` yet; after `level:` the levels, after
  `package:` `mine` plus packages seen, after `process:` processes seen, after `tag:` tags seen (most lines first), after
  `is:` crash/stacktrace, after `age:` 30s 5m 1h 1d. Up/Down move, Tab or Enter accepts, Esc closes. At most 7 items.
  The key list ends with a footer: "`-` excludes · `~` regex · `=` exact · `|` or".
- Clear (×) button appears in the field when it has text.
- Chips: **Picked app** (`package:mine`), **Warnings and up** (`level:warn`, replaces any other `level:`), **Crashes**
  (`is:crash`). A chip shows checked when its term is in the query. Right of the chips: "mine = `<package>`", or "Pick
  an app in Apps" when none.
- Click a tag or package cell: adds `tag:<x>` / `package:<x>` (toast "Filtering to x"). Alt-click adds `-tag:<x>`
  (toast "Hiding x").
- No saved filters.

### Apps tab

- New action "Show in Logcat" in the app card's right-click menu and in the options sheet's buttons. It makes that app
  the picked app, adds `package:mine` to the query if missing, and switches to the Logcat tab. It does not start
  logcat; the Play button pulses.
- The picked app's card shows a small "Logcat" badge that scales in.

### List

- Newest at the bottom. When scrolled to the bottom the list follows new lines. Scrolled up, it stays put and a pill
  rises from the bottom: "N new lines" (only lines that pass the filter count); clicking scrolls to the end.
- Empty filter result: "No lines match this filter yet. New lines that match appear here as they arrive."
- Soft wrap toggle (off: one line, ellipsis).
- Status bar: Live dot ("Live", blinking green while running; "Paused"; "Not running" before the first Play), clear,
  soft wrap, compact, "X of Y lines", and a stacked bar of shown lines by level.

### Keys

⌘F / Ctrl+F focuses the filter, Space plays or pauses, ⌘K / Ctrl+K clears, Tab accepts a suggestion.

### Package names

`threadtime` has no package. The window reads `ps -A -o PID,NAME` when a line arrives from a pid it doesn't know (at
most once per second) and maps pid → process name; the package is the process name before any `:`. Lines from
unknown pids show an empty package until the lookup answers.

## Motion

| Change | Motion |
|---|---|
| New line | grows from 0 height and fades in, 300 ms EmphasizedDecelerate |
| Filter change | rows that stop matching collapse and fade, rows that start matching expand, 240 ms; the rest slide (animateItem) |
| Tab switch | existing sheet slide; segment highlight glides |
| Play ⇄ Pause | icon rotate/scale/fade 280 ms; button color 250 ms |
| Start card | fades and scales to 0.97, 300 ms |
| Paused banner | slides down from above the list and fades, 320 ms |
| New lines pill | rises 16 dp and scales from 0.8, 300 ms |
| Crash row | background flashes red, 1.2 s fade |
| Trace fold | height expands/collapses 260 ms; chevron rotates 90° |
| Chip on/off | check icon width grows 0 → 16 dp, 250 ms; container color fades |
| Autocomplete | scales from 0.94 and fades, 180 ms |
| Filter error hint | expands vertically, 220 ms |
| Resume button pulse | two 900 ms rings when returning to a stopped tab |
| Apps "Logcat" badge | scales in |

Honor the app's reduced-motion handling (see `MirrorWindowMotion.kt`).

## Sizes

Logcat tab default and minimum sheet width: **560 dp**, saved as `logcatWidth` in `AppSettings` like the other tabs.
