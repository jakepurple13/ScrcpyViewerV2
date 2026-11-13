# Link launcher

Date: 2026-11-13
Status: approved (mockup https://claude.ai/artifact/4Qo5e7z3kTrNAsNpzAf9MQ, "10 · Link launcher")

## Goal

A **Links** tab in the mirror window's quick-actions sheet that opens a web link, custom-scheme link or intent URI on
the device, shows exactly what happened, and keeps a history.

## Decisions already made with the user

- A new sheet tab. History is per device; starred links are shared across all devices.
- No App Links "Verify" button for now.
- Every state change animates; nothing snaps.

## Placement

- `SheetTab` order becomes Actions, Apps, Shell, Files, **Links**, Logcat (Links right after Files).
- Width: its own `linksWidth` setting (default 400 dp, same as Shell), saved like the other tabs.
- Six tabs no longer fit as words at the narrowest sheet width. When a segment would be narrower than 56 dp, the tab
  bar shows icons only (each with a tooltip of its name), crossfading words ↔ icons (200 ms) as the sheet is resized.
  Icons: Actions bolt, Apps grid, Shell terminal, Files folder, Links link, Logcat list (existing Material icons). The
  Shell red dot, Files badge and Logcat live dot / pop-out stay on their segment in both modes.

## Tab content (top to bottom)

1. **Link or intent** field (monospace). A chip at its right shows the detected kind, popping (scale .7→1, 350 ms)
   when it changes: "Web link" (http/https), "<scheme>: link" (custom scheme), "Intent URI" (`intent:`), "Not a link".
   Enter opens it.
2. **Open with** dropdown: "Let Android choose" plus the device's apps (labels from the Apps tab's list; loads it if
   needed, with a small progress line while loading).
3. **Before opening** dropdown: "Nothing", "Force-stop the app (cold start)" (only when an app is chosen), "Clear the
   task (--activity-clear-task)".
4. **Intent details** expander (chevron rotates 90°, body expands 400 ms): Action (default
   `android.intent.action.VIEW`), Category (default `android.intent.category.BROWSABLE`), one extra (type
   string/int/bool, key, value). Hidden fields aren't used.
5. **Command preview**: the exact `adb -s <serial> shell …` text, monospace, selectable, with the app's CopyButton.
   It updates as fields change.
6. **Open on device** button (spinner while running) and **Save as quick action**.
7. **Result** strip (expands 400 ms, colour by outcome):
   - Success: "Opened in <app label> · <cold|warm> start <n> ms" plus the raw `Status / LaunchState / TotalTime`
     line in small monospace.
   - Failure: "No app on this device opens this link" (+ " with <app>" when one was chosen) plus the raw `am` error
     line; the strip shakes once (400 ms).
8. **History**: starred links first (with a divider), then recent links for this device, newest first, capped at 50.
   Each row: outcome dot, the link (monospace, one line), "<app label or No app found> · <time ago>", a hover "Open
   again" button, and a star toggle. Clicking a row fills the field (the field flashes). New rows expand in; starring
   moves a row with a FLIP slide. "Clear" removes non-starred rows (they fold away).

## Command

`am start -W [-a <action>] [-c <category>] -d '<link>' [--es|--ei|--ez <key> <value>] [--activity-clear-task]
[<package>]`, prefixed with `am force-stop <package> && ` for a cold start. Intent URIs (`intent:`) are passed with
`-d` and no `-a`/`-c` (the URI carries them). Values are single-quoted with `DeviceApps.quote`. Runs through
`ShellRunner.run` (30 s timeout).

Parsing `am start -W` output: `Status: ok`, `LaunchState: COLD|WARM|HOT`, `TotalTime: n`, the activity's package
(from `Activity: <pkg>/<cls>`) mapped to its label; errors from lines starting `Error:`.

## Save as quick action

Opens the Actions editor with a new run-once action prefilled: name "Open <host or scheme>", icon "link", the command
above (without `adb -s … shell`), output Toast. Uses `ActionsRequest(null, new = true, template)` with an
`ActionTemplate`; the editor must not show the built-in-template banner for it.

## Storage

`LinkStore` (`links.json` via `JsonFile`): `history: Map<serial, List<LinkEntry>>` and `starred: List<LinkEntry>`.
`LinkEntry(link, packageName?, appLabel?, ok, detail, at)`. Dedupe by link + package; re-opening moves it to the top.

## Motion

| Change | Motion |
|---|---|
| Kind chip changes | pop scale .7→1, 350 ms |
| Intent details | chevron 90° turn + expand/shrink 400 ms |
| Opening | button content crossfades to spinner + "Opening…" |
| Result strip | expand 400 ms; failure shakes once 400 ms |
| History row added | expand + fade 420 ms |
| Star / unstar | row slides to its new place (FLIP 420 ms) |
| Row clicked | field border flash 600 ms |
| Clear | rows fold away (height + fade 300 ms) |
| Tab bar words ↔ icons | crossfade 200 ms |
| Reduced motion | instant or 150 ms fade, no shake |

## Tests

Kind detection; command building (each option, quoting, intent URI, force-stop); `am start -W` parsing (ok cold/warm,
error, unknown); LinkStore (per-device history, global stars, cap, dedupe, corrupt file); view model (open success /
failure, history update, star move, clear keeps stars); tab bar icon mode at narrow widths; screenshots of the Links
tab idle, success, failure, details open, light/dark; existing SheetTabs tests updated for six tabs.

## Out of scope

App Links verification, several extras, saving per-link "Before opening" choices.
