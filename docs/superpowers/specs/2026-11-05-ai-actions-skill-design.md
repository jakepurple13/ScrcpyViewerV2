# AI skill for custom actions

Date: 2026-11-05
Status: design, mockup approved (`.superpowers/brainstorm/20137-1793889158/content/ai-skill-flow-v2.html`)

## Intent

Writing a custom quick action by hand means knowing the right `adb shell` commands, the toggle read/match rules and
the action JSON. An AI coding tool on the user's computer can do all of that from a sentence ("make a toggle for
Bluetooth"). This feature lets the user teach their AI tools how, with one button in Settings, and makes whatever the
AI creates appear in Scrcpy Vwr on its own, close to real time, with no Import step.

A first version of the skill exists at `.claude/skills/create-custom-action/SKILL.md` and was tested by making a
Bluetooth toggle on a real device (Android 12). That test found that some commands work but exit nonzero
(`svc bluetooth enable` prints `Killed`, exit 137), which the skill now covers. The skill text is the starting point
for the bundled skill below.

## Goals

- A Settings button that installs the skill into any of: Claude Code, Gemini CLI, Codex, Grok CLI, or a folder the
  user picks.
- Actions the AI writes show up in the app within about 2 seconds while it runs, in the Actions screen and in every
  open mirror window's quick-actions sheet. No restart, no Import dialog.
- The AI reads the user's current actions first, so it never picks a name that is already taken.
- AI-made actions are shown in their own "Made by AI" section, separate from "Your actions".

## Non-goals

- Web chat apps (claude.ai, ChatGPT, Grok web). They cannot write files on the user's computer.
- Calling an AI from inside Scrcpy Vwr. The user talks to their AI tool in its own terminal or app.
- Project-level installs (into a repo's `.claude/skills` and so on). User-level only, plus "Choose folder…".
- Auto-updating installed skills when the app updates. The app shows that an update is available; the user clicks.
- The AI deleting actions. Only add and edit. When asked to delete, the skill tells the AI to send the user to the
  app (tile menu or Actions screen).
- Avoiding the AI tool's write-permission prompt for the inbox folder. A one-time approval per tool is accepted
  (decided with the user). The skill tells the AI the folder is outside the project and to ask for access.

## Anti-goals

- The AI editing `actions.json` directly. The app owns that file and overwrites it on every change; the skill must
  forbid writing it and only allow reading it.
- An AI file silently replacing an action the user made. Replacing happens only by matching `id` (see Intake), which
  the AI can only know by reading `actions.json`, so it is a deliberate edit the user asked the AI for.
- A bad file from the AI failing silently. The user and the AI both get the reason.

## Parts

### 1. Bundled skill

- Lives in the app as a resource template, for example `composeApp/src/jvmMain/resources/ai-skill/SKILL.md`. It is
  the single source; the repo copy at `.claude/skills/create-custom-action/` is deleted (developers install it with
  the Settings button like anyone else).
- Skill folder name and frontmatter `name`: `scrcpy-vwr-actions`. Frontmatter has `name` and `description` (Codex
  requires both) and `metadata: { scrcpy-vwr-skill-version: <N> }`. No tool-specific frontmatter keys.
- At install, the app fills in placeholders:
  - the inbox folder (absolute path, see Intake);
  - the `actions.json` path (absolute);
  - the icon list, generated from `ActionIcons` so it can never go stale.
- After filling, no placeholder may remain (test this).
- Content changes from today's skill:
  - Section "Add it to the app" is replaced: read `actions.json` (read-only) to see current names and ids; write new
    actions to the inbox; never write `actions.json`.
  - Write to a temp name first (`<slug>.json.tmp`), then rename to `<slug>.json`, so the app never reads half a file.
  - To change an action that already exists, reuse its `id` from `actions.json`.
  - Optional root field `"author"` (for example `"Claude Code"`) for the arrival message.
  - After writing, wait about 3 seconds and check: file gone means accepted; a file in `rejected/` with a
    `.error.txt` next to it means rejected, with the reason inside. Fix and retry.
  - If the app is not running, the file waits and is added the next time the app starts. Tell the user.
  - Deleting is not possible from the AI; point the user to the app.

### 2. Intake (the inbox)

- Folder: `<dataDir>/actions-inbox/`, created at app start. `dataDir` honours `-Dscrcpyviewer.dataDir`.
- File format: the existing import format (`format`, `version`, `actions`) via `ActionCodec`, plus optional `author`
  (unknown root keys are already ignored by the codec; `author` must be read without breaking that).
- Only `*.json` files are processed; `*.tmp` and anything else is ignored.
- Latency target: an action appears within 2 seconds of the rename. On macOS the JDK `WatchService` polls slowly
  (about 10 s), so the builder picks the mechanism (a 1 s directory poll is acceptable).
- Files present at startup are processed right away.
- For each action in an accepted file:
  - `id` matches an existing action: replace its content in place. It keeps its position and its existing `source`,
    so a user action the AI edits stays in "Your actions". This is how the AI edits. If the edit renames it to a
    name another action already has (ignoring case), the whole file is rejected with that reason; no suffix.
  - Otherwise: add it at the end of "Made by AI", marked as made by AI, keeping the incoming `id` if no action has
    it, else a new UUID. A name clash (possible if the AI skipped reading) gets the existing `uniqueName` suffix:
    "Bluetooth (2)".
- Accepted file: deleted. Each added or changed action produces a message: "✦ Claude Code added Bluetooth" (or
  "✦ AI added Bluetooth" with no `author`; "updated" for replacements).
- Rejected file (bad JSON, wrong format, newer version, an action that fails to decode, a required field blank): the
  file moves to `actions-inbox/rejected/` and a `<file>.error.txt` holds the reason. The user sees "Couldn't add
  actions from <file>: <reason>". A file is all-or-nothing.
- Validation matches the editor's rules: name non-blank; toggle `on`/`off` non-blank; run-once `command` non-blank;
  an `onWhen` regex that does not compile is rejected.
- Messages show in open mirror windows and the main window. Where exactly is the builder's call, using existing
  notification patterns.
- Intake writes through `ActionStore`, so everything already watching the store (sheet, Actions screen) updates.

### 3. "Made by AI" marking

- Both action types gain a field for where they came from, serialized as `"source": "ai"`, default user. Existing
  files without it load as user actions.
- Export and import keep the field.
- Quick-actions sheet: a "Made by AI" divider and grid below "Your actions", shown only when it has tiles. AI tiles
  carry a small ✦ badge. Tile menu gains "Move to Your actions" (clears the mark).
- Actions screen list: the same two groups with the same headings. The editor can also clear the mark.
- Reordering stays within a group.

### 4. Settings

- New category "AI" between "adb" and "Updates", searchable (keywords: claude, gemini, codex, chatgpt, grok, skill,
  agent, llm).
- Entry "AI skill for custom actions", text as in the mockup, button "Add skill to an AI tool…".
- Under it, a status line per installed location: tool name plus "up to date" or "update available" (installed
  version older than the bundled one). Clicking "update available" reinstalls there.
- Dialog "Add AI skill": one checkbox row per tool, with the target folder shown.

  | Tool | Install to | "Found" when this exists |
  |------|-----------|--------------------------|
  | Claude Code | `~/.claude/skills/scrcpy-vwr-actions/` | `~/.claude` |
  | Gemini CLI | `~/.gemini/skills/scrcpy-vwr-actions/` | `~/.gemini` |
  | Codex | `~/.agents/skills/scrcpy-vwr-actions/` | `~/.codex` |
  | Grok CLI | `~/.grok/skills/scrcpy-vwr-actions/` | `~/.grok` |

  `~` is the user's home folder (`user.home`) on every OS. Found tools are pre-checked; not-found tools can still be
  checked. A last row "Choose folder…" opens a folder picker; the skill goes into `<picked>/scrcpy-vwr-actions/`.
  Picked folders are remembered in settings so their status shows.
- Install writes the filled skill, replacing that folder's `SKILL.md` if present (the folder is ours by name).
  Missing parent folders are created.
- The installed skill holds absolute paths, so a build with a different data folder (`-Dscrcpyviewer.dataDir`)
  would point it elsewhere. Status shows "update available" when the installed paths differ from this app's, as
  well as when the version is older.
- Done dialog: ✓ or ✗ (with the error) per target, the example prompt with a "Copy example prompt" button, and the
  note "Restart the tool if it was already open."

Folder sources (checked 2026-11-05): Claude Code docs (code.claude.com/docs/en/skills), Gemini CLI docs
(geminicli.com/docs/cli/skills), Codex docs (developers.openai.com/codex/skills; user path is `~/.agents/skills`),
xAI Grok Build docs (docs.x.ai/build/features/skills-plugins-marketplaces). Gemini and both Grok CLIs also read
`~/.agents/skills`, and Grok Build also reads `~/.claude/skills`. Installing to two of these can make a tool list
the skill twice. Same name and same content, so this is harmless; accepted.

## Constraints

- Kotlin, Compose Desktop, Koin, as the rest of the app. Follow existing patterns (`ActionStore`, `JsonFile`,
  `SettingsRegistry`, `PresetFileDialogs`-style dialogs).
- macOS, Windows and Linux paths all work.
- Release builds are ProGuard shrink-only; resources must survive packaging.
- Tests in `jvmTest`: intake (accept, update by id, name clash, reject with error file, tmp ignored, startup
  backlog), skill rendering (no placeholders left, icon list matches `ActionIcons`), installer (writes, replaces,
  status/version detection) and the new `source` field's serialization and default.

## Calls I made (not discussed)

- Inbox design (drop folder, delete on accept, `rejected/` plus `.error.txt`) and the 2 s target.
- Update by matching `id`; an update keeps the action's section and position; new inbox actions are marked AI-made.
- A rename by update that clashes rejects the file instead of adding a suffix.
- Path mismatch counts as "update available".
- `source` field name and default; export/import keep it.
- "Move to Your actions" in the tile menu and editor.
- Skill version in `metadata`; manual "update available" instead of auto-update.
- Removing the repo copy of the skill in favour of the bundled one.
- Optional `author` field for the arrival message.

## Left to the builder

- File-watching mechanism, as long as it meets the 2 s target.
- Where arrival and rejection messages appear, within existing patterns.
- Exact wording of the settings text and dialog, within the mockup.
