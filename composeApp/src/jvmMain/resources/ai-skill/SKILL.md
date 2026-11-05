---
name: scrcpy-vwr-actions
description: Create or edit Scrcpy Vwr custom quick actions (toggles and run-once adb shell commands) and add them to the running app. Use when the user asks to make, add, write, change, or edit a Scrcpy Vwr action, quick action, toggle, or shell shortcut for the mirror window, such as "make a Scrcpy Vwr toggle for Bluetooth", "add a quick action", or "edit my Scrcpy Vwr action".
metadata:
  scrcpy-vwr-skill-version: {{SKILL_VERSION}}
---

# Create or edit a Scrcpy Vwr custom action

Custom actions are user-defined buttons in the Scrcpy Vwr mirror window's quick-actions sheet. Each runs
commands through `adb shell` on the mirrored device. Turn the user's request into valid action JSON, then
drop it in the app's inbox folder. The app picks it up within a few seconds.

Inbox folder: {{INBOX_DIR}}

Actions file (read only): {{ACTIONS_FILE}}

## 1. Pick the action type

| Type | `"type"` | Use when |
|------|----------|----------|
| Toggle | `"toggle"` | Something with an on and an off state (stay awake, animations, Wi-Fi). |
| Run once | `"once"` | A single command (launch app, grant permission, dump info, type text). |

## 2. Write the commands

Commands are device-shell commands. Never prefix them with `adb shell` or `adb -s ...`; the app adds that.
Chain several commands with `;` or newlines. Prefer commands that exit; a command that never ends
(`logcat`, `top`) only makes sense with `"output": "shell"`.

A nonzero exit code is a failure: the app shows the last output line and flips a toggle back. Some
commands work but still exit nonzero (`svc bluetooth enable` prints `Killed` and exits 137 on some
devices). Check `echo $?` while testing. When that happens, silence the command and check the result
instead: `svc bluetooth enable >/dev/null 2>&1; [ "$(settings get global bluetooth_on)" = 1 ]`.
Never end a command with `exit`: a command that ends the shell loses its exit code and always counts as success.

Verify a command before you put it in an action when a device is connected:
`adb devices`, then `adb -s <serial> shell '<command>'`. Ask the user which device when more than one is listed.
Do not run destructive commands (`pm clear`, `pm uninstall`, `rm`, `reboot`, `wipe`) to test them.

## 3. Fields

All actions:
- `id` (string, required): unique across the user's actions. For a new action use a short slug
  (`bluetooth-toggle`); the app keeps it. To edit an existing action, reuse its `id` (see section 6).
- `name` (string, required, non-blank): unique across the user's actions, ignoring case.
- `icon` (string, optional): one name from the list in section 5. Unknown names fall back to the default icon.

Toggle (`"type": "toggle"`), default icon `toggle_on`:
- `on` (required): command that turns it on.
- `off` (required): command that turns it off.
- `read` (optional): command whose output tells the current state. Without it, the app remembers the
  last state per device.
- `onWhen` (optional, only counts with `read`): `{ "mode": "equals" | "contains" | "regex", "value": "..." }`.
  The `read` output is trimmed, then matched. `regex` uses find (partial match), not full match.

Run once (`"type": "once"`), default icon `play_arrow`:
- `command` (required).
- `output`: `"toast"` (default, short result), `"shell"` (stream into the Shell tab, use for long or
  endless output), or `"none"`.
- `confirm` (default `false`): ask before running. Set `true` for destructive commands.
- `variables`: map of variable name to `{ "label", "kind", "default", "choices" }`.
  - `label` is required: the prompt the user sees when running the action.
  - `kind`: `"text"` (free text, default), `"list"` (pick from `choices`), `"app"` (pick an installed package).
  - Every `{name}` in `command` needs an entry; names match `[A-Za-z_][A-Za-z0-9_]*`.
  - Write a literal brace as `{{` or `}}` (for example `awk '{{print $1}}'`).
  - Values are inserted as-is, with no shell quoting. Wrap a text variable in quotes in the command when
    it may contain spaces: `am start -a android.intent.action.VIEW -d '{url}'`.
  - Variables work only in run-once actions, not toggles.

## 4. File format

```json
{
  "format": "scrcpy-viewer.actions",
  "version": 1,
  "author": "Claude",
  "actions": [
    {
      "type": "toggle",
      "id": "stay-awake",
      "name": "Stay awake",
      "icon": "battery_full",
      "on": "svc power stayon true",
      "off": "svc power stayon false",
      "read": "settings get global stay_on_while_plugged_in",
      "onWhen": { "mode": "equals", "value": "7" }
    },
    {
      "type": "once",
      "id": "clear-app-data",
      "name": "Clear app data",
      "icon": "cleaning_services",
      "command": "pm clear {package}",
      "output": "toast",
      "confirm": true,
      "variables": {
        "package": { "label": "App to clear", "kind": "app" }
      }
    }
  ]
}
```

`format` and `version` are required. The app rejects `version` greater than 1.
`author` is optional: the name of the tool writing the file (for example `Claude`, `Gemini`, `Codex`).
The app shows it in its message, such as "✦ Claude added Stay awake". Without it the app says "AI".

## 5. Icons

{{ICONS}}

## 6. Add or edit actions through the inbox

The app watches the inbox folder (the "Inbox folder" line at the top of this skill) while it runs.
Never write the actions file. The app owns it and overwrites it on every change.

1. Read the actions file (read only) for the user's current action names and ids. Its shape is
   `{ "actions": [ ... ] }` with the same action objects as section 4. It may not exist yet when the user
   has no actions.
2. To add an action, use an `id` that is not in the actions file. The app adds it under "Made by AI".
   If the `name` is taken, the app adds " (2)" (or the next free number) to it.
3. To edit an action, copy its full object from the actions file, change what the user asked for, and keep
   its `id`. The edit replaces the whole action and keeps its position and section. Do not rename it to a
   name another action already has; the app rejects that.
4. Write the JSON to `<slug>.json.tmp` in the inbox folder, then rename it to `<slug>.json`. The app only
   reads `*.json` files, so the rename keeps it from reading a half-written file. Pick a `<slug>` that is
   not already in the folder. Quote the folder path in shell commands; it may contain spaces. This works
   in bash, zsh, and PowerShell:
   `cd "{{INBOX_DIR}}"; mv <slug>.json.tmp <slug>.json`
5. Wait about 5 seconds, then check the result:
   - The file is gone: the app accepted it.
   - The file is in the `rejected` folder inside the inbox: read `rejected/<slug>.json.error.txt` for the
     reason, fix the JSON, and write a new file. The app rejects a whole file when any action in it has a
     blank `name`, `on`, `off`, or `command`, an invalid `onWhen` regex, or an edit that takes another
     action's name. It also rejects a file that is not valid JSON or lacks `format`, `version`, or `actions`.
   - The file is still there: wait once more (about 5 seconds) and check again. If it is still there, the
     app is not running. It adds the actions the next time the user opens it.

The inbox folder is outside the project, so your tool may ask the user to approve writing there. Tell the
user why you need it and ask them to approve it.

You cannot delete actions. When the user wants one removed, tell them to delete it in Scrcpy Vwr
(**Actions** screen).

## 7. Check before you finish

- Every required field is present and non-blank for its type.
- Every `{name}` in a run-once `command` has a `variables` entry, and no toggle uses `{name}`.
- `list` variables have non-empty `choices`.
- `icon` is from section 5.
- Destructive run-once actions have `"confirm": true`.
- Commands have no `adb shell` prefix.
- New actions use an `id` not in the actions file; edits reuse the existing `id`.
- You wrote only to the inbox folder, never to the actions file.
- The inbox file is gone (accepted), or you fixed the reason in its `.error.txt`.
- Tell the user what each action does and how you tested it (or that you did not).
