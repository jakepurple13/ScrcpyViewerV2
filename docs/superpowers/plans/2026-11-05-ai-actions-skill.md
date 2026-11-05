# AI Skill for Custom Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Settings button installs a bundled skill into local AI tools; actions those tools drop into an inbox folder appear live in a "Made by AI" section.

**Architecture:** Actions gain a `source` field. An `ActionInbox` polls `<dataDir>/actions-inbox/` every second, validates each `*.json` file and writes accepted actions through `ActionStore`, so the sheet and Actions screen update through their existing flows; it emits messages that the main window and mirror windows show as snackbars. An `AiSkill` renders the bundled `SKILL.md` template with this app's paths and icons, and an `AiSkillInstaller` writes it into each chosen tool's skills folder and reports status.

**Tech Stack:** Kotlin, Compose Desktop (Material 3), kotlinx.serialization, kotlinx.coroutines, Koin (annotations), kotlin.test in `jvmTest`.

**Spec:** `docs/superpowers/specs/2026-11-05-ai-actions-skill-design.md`

## Global Constraints

- Inbox folder: `<dataDir>/actions-inbox/`; rejected files go to `<dataDir>/actions-inbox/rejected/` with `<file>.error.txt` beside them. `dataDir` is `AppDataDir.root()` (honours `-Dscrcpyviewer.dataDir`).
- Only `*.json` files are processed; anything else (`*.json.tmp`) is ignored.
- Latency: an action appears within 2 seconds of the file landing.
- Inbox file format = the import format (`format: "scrcpy-viewer.actions"`, `version: 1`, `actions`) plus optional root `"author"`.
- Serialized source field: `"source": "ai"`; absent means user.
- Skill folder name and frontmatter `name`: `scrcpy-vwr-actions`. Frontmatter: `name`, `description`, `metadata: { scrcpy-vwr-skill-version: <N> }` only.
- Install targets (`~` = `System.getProperty("user.home")`):
  Claude Code `~/.claude/skills/scrcpy-vwr-actions/` (found: `~/.claude`), Gemini CLI `~/.gemini/skills/scrcpy-vwr-actions/` (found: `~/.gemini`), Codex `~/.agents/skills/scrcpy-vwr-actions/` (found: `~/.codex`), Grok CLI `~/.grok/skills/scrcpy-vwr-actions/` (found: `~/.grok`); "Choose folder…" installs into `<picked>/scrcpy-vwr-actions/`.
- Messages: `✦ <author> added <name>` / `✦ <author> updated <name>`; author defaults to `AI`. Rejection: `Couldn't add actions from <file>: <reason>`.
- Settings category "AI" sits between "adb" and "Updates"; keywords: claude, gemini, codex, chatgpt, grok, skill, agent, llm.
- The AI may add and edit, never delete. The skill forbids writing `actions.json`.
- Run tests with `./gradlew :composeApp:jvmTest`. Single class: `./gradlew :composeApp:jvmTest --tests '<fqcn>'`.

## Review Focus

1. **AI writes the `.json` directly, without the tmp-then-rename step, and the poll catches it half-written.** Expect: no rejection while the file is still changing. A file that fails to parse and was modified less than 2 s ago is left for the next poll; only then is it rejected. (Task 3 test `half-written fresh file is retried, not rejected`.)
2. **The same file name is rejected twice.** Expect: the second rejection replaces `rejected/<file>` and its `.error.txt`, and does not crash. (Task 3 test `second rejection of same name overwrites`.)
3. **One file holds two new actions with the same `id`.** Expect: both are added; the second gets a fresh UUID. (Task 3 test `duplicate ids within one file both added`.)
4. **Home folder or data folder path with spaces (Windows `C:\Users\Jo Smith`).** Expect: the rendered skill holds the path verbatim, and the shell examples quote it. (Task 6 test `paths with spaces are quoted in commands`.)
5. **Several files waiting at startup.** Expect: processed oldest-first by modified time, so a later edit wins over an earlier add. (Task 3 test `backlog processed oldest first`.)

---

### Task 1: `source` field on actions

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/actions/CustomAction.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/actions/ActionImportPlan.kt` (beside `withId`/`withName`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/actions/ActionsViewModel.kt:53-70, 578-601` (`ActionDraft`, `toDraft`, `toAction`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/actions/ActionCodecTest.kt`, `.../ui/actions/ActionsViewModelTest.kt` (existing file; add to it)

**Interfaces:**
- Produces:
  - `@Serializable enum class ActionSource { @SerialName("user") User, @SerialName("ai") Ai }`
  - `val source: ActionSource` on `CustomAction`; `ToggleAction` and `RunOnceAction` get `override val source: ActionSource = ActionSource.User` as their last constructor parameter.
  - `fun CustomAction.withSource(source: ActionSource): CustomAction`
  - `ActionDraft.source: ActionSource = ActionSource.User`, carried by `toDraft()` and `toAction()`.

- [ ] **Step 1: Write failing tests**

In `ActionCodecTest`:
```kotlin
@Test fun `source defaults to user when absent`() {
    assertEquals(ActionSource.User, ActionCodec.decode(example)[0].source)
}
@Test fun `source ai round trips`() {
    val a = RunOnceAction("x", "X", command = "echo", source = ActionSource.Ai)
    assertEquals(listOf(a), ActionCodec.decode(ActionCodec.encode(listOf(a))))
    assertTrue("\"source\": \"ai\"" in ActionCodec.encode(listOf(a)))
}
```
In `ActionsViewModelTest`: `saving an AI action keeps its source` — seed the store with a `ToggleAction(..., source = ActionSource.Ai)`, open it, change the name, save; assert the stored action's `source == ActionSource.Ai`.

- [ ] **Step 2: Run, expect compile failure** (`ActionSource` unresolved).

- [ ] **Step 3: Implement** the interfaces above. `ActionCodec`'s `Json` has no `encodeDefaults`, so a user action does not write `source`; that is intended.

- [ ] **Step 4: Run `./gradlew :composeApp:jvmTest`; expect all pass.**

- [ ] **Step 5: Commit** `feat(actions): add source field marking AI-made actions`

---

### Task 2: Store operations for groups

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/actions/ActionStore.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/actions/ActionStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 `ActionSource`, `withSource`.
- Produces:
  - `fun ActionStore.moveInGroup(id: String, toGroupIndex: Int)` — `toGroupIndex` counts only actions with the same `source`; the action lands just before the group's `toGroupIndex`-th member (or after its last member when past the end). Other actions keep their order.
  - `fun ActionStore.markAsUser(id: String)` — sets `source = User`, same list position.
  - `fun ActionStore.applyInbox(incoming: List<CustomAction>): List<InboxChange>` — single locked mutation, one save. For each action: id exists ⇒ replace content, keep that action's position and existing `source` (`InboxChange.Updated`); otherwise append with `source = Ai`, keeping the id unless any action (including earlier ones in this call) has it, else `UUID.randomUUID()`, and name `uniqueName(name)` (`InboxChange.Added`).
  - `sealed interface InboxChange { val name: String; data class Added(override val name: String) : InboxChange; data class Updated(override val name: String) : InboxChange }`

- [ ] **Step 1: Write failing tests** in `ActionStoreTest`:
  - `moveInGroup only counts same-source actions`: list `[u1, a1, u2, a2]` (u = user, a = ai); `moveInGroup("u2", 0)` ⇒ ids `[u2, u1, a1, a2]`; `moveInGroup("a1", 5)` ⇒ `a1` after `a2`.
  - `markAsUser keeps position`.
  - `applyInbox adds new as ai at end`: result `Added("Bluetooth")`, last action `source == Ai`, id `"bluetooth"` kept.
  - `applyInbox update keeps position and source`: user action `u1` at index 0 updated by incoming `id = "u1"` with source Ai and new command ⇒ index 0, `source == User`, command changed, result `Updated`.
  - `applyInbox name clash on new gets suffix`: existing "Bluetooth" ⇒ added as "Bluetooth (2)".
  - `applyInbox duplicate ids within one call` ⇒ both added, second id ≠ `"dup"`.

- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run tests; expect pass.**
- [ ] **Step 5: Commit** `feat(actions): group-aware store operations and inbox apply`

---

### Task 3: `ActionInbox` intake

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/actions/ActionInbox.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/actions/ActionCodec.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/actions/ActionInboxTest.kt`, `ActionCodecTest.kt`

**Interfaces:**
- Consumes: Task 2 `applyInbox`, `InboxChange`; `ActionCodec.decode`.
- Produces:
  - `data class InboxFile(val author: String?, val actions: List<CustomAction>)`; `fun ActionCodec.decodeInbox(text: String): InboxFile` (same checks as `decode`, plus reads optional string `author`).
  - `fun validateInbox(file: InboxFile, existing: List<CustomAction>): String?` — the reason or null. Rules, in this order, reason text exact:
    - name blank: `Action <n>: name is required`
    - toggle `on` blank: `Action <n>: on is required`; `off` blank: `Action <n>: off is required`
    - run-once `command` blank: `Action <n>: command is required`
    - `onWhen.mode == regex` and the value does not compile: `Action <n>: onWhen regex is invalid`
    - update (id exists) whose name, ignoring case, belongs to a different existing action: `Action <n>: another action is already named <name>`
  - `class ActionInbox(dir: Path, store: ActionStore, clock: () -> Long = System::currentTimeMillis)` with:
    - `val messages: SharedFlow<String>` (replay 0, extraBufferCapacity 16)
    - `fun processOnce()` — one scan; files sorted by last-modified ascending.
    - `fun start(scope: CoroutineScope)` — creates the folders, then `processOnce()` every 1000 ms on `Dispatchers.IO` until the scope ends.
    - Per file: decode + validate. Accepted ⇒ `applyInbox`, delete file, emit one message per change. Rejected ⇒ unless (parse failed and `clock() - lastModified < 2000`) move to `rejected/` (replace existing), write `rejected/<file>.error.txt` with the reason, emit `Couldn't add actions from <file>: <reason>`.

- [ ] **Step 1: Write failing tests** in `ActionInboxTest` (use `Files.createTempDirectory`, a real `ActionStore` on a temp file, collect `messages` with `backgroundScope` / `UnconfinedTestDispatcher`):
  - `accepted file is applied, deleted and announced`: author `"Claude Code"` ⇒ message `✦ Claude Code added Bluetooth`; file gone.
  - `no author says AI`: ⇒ `✦ AI added Bluetooth`.
  - `update message`: existing id ⇒ `✦ AI updated Bluetooth`.
  - `tmp files are ignored`: `x.json.tmp` still present, store unchanged.
  - `invalid file is moved to rejected with reason`: blank `command` ⇒ `rejected/x.json` exists, `rejected/x.json.error.txt` contains `Action 1: command is required`, message `Couldn't add actions from x.json: Action 1: command is required`, store unchanged.
  - `rename clash rejects whole file`: two actions in file, second is a rename clash ⇒ neither applied.
  - `half-written fresh file is retried, not rejected`: content `{"format": "scr`, clock = lastModified + 500 ⇒ file still in inbox; clock = lastModified + 2500 ⇒ rejected.
  - `second rejection of same name overwrites`.
  - `duplicate ids within one file both added`.
  - `backlog processed oldest first`: file A (older) adds id `b`, file B (newer) updates id `b` name to "Bluetooth 2" ⇒ final name "Bluetooth 2".
  In `ActionCodecTest`: `decodeInbox reads author` and `decodeInbox without author is null`.

- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run tests; expect pass.**
- [ ] **Step 5: Commit** `feat(actions): inbox folder that adds AI-written actions live`

---

### Task 4: Wire the inbox and its messages

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/di/AppModule.kt` (`StorageModule`, beside `actionStore`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/Main.kt` (after `startAppKoin()`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/App.kt:101-110` (collect into `snackbar`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/MirrorWindow.kt:339-346` (collect into `notify`)

**Interfaces:**
- Consumes: Task 3 `ActionInbox`.
- Produces: `@Singleton fun actionInbox(dataDir: DataDir, store: ActionStore): ActionInbox = ActionInbox(dataDir.path.resolve("actions-inbox"), store)`; `Main.kt` calls `koin.get<ActionInbox>().start(koin.get<AppScope>())`.

- [ ] **Step 1:** Add the provider and start call. In `App` add `LaunchedEffect(inbox) { inbox.messages.collect { snackbar.showSnackbar(it) } }` with `inbox: ActionInbox = koinInject()`; in `MirrorWindow` the same into `notify`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest`; expect pass (Koin verification tests cover the new definition).
- [ ] **Step 3: Manual check:** run the app (`./gradlew :composeApp:run`), copy `bluetooth.scrcpy-actions.json` to `~/Library/Application Support/ScrcpyViewer/actions-inbox/` and confirm the snackbar and the new action within 2 s.
- [ ] **Step 4: Commit** `feat(actions): start the inbox and show its messages`

---

### Task 5: "Made by AI" in the sheet and the Actions screen

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/ActionTiles.kt:130-140, 240-250`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/QuickActions.kt` (`QuickActionEvent`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/actions/ActionsScreen.kt:360-380, 431-` (`ReorderableActions`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/actions/ActionsViewModel.kt` (`move`, new `markAsUser`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/actions/ActionEditor.kt` (mark-clear control)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/QuickActionsCustomTest.kt`, `.../ui/actions/ActionsScreenTest.kt` (create if absent, Compose UI test like the existing ones)

**Interfaces:**
- Consumes: Task 2 `moveInGroup`, `markAsUser`.
- Produces: `QuickActionEvent.MoveToYours(id: String)`; `ActionsEvent.MarkAsUser(id: String)`; `ActionsViewModel.move` now calls `store.moveInGroup`.

UI copy: divider label `Made by AI`; badge `✦` top-right of AI tiles (content description `Made by AI`); tile menu item `Move to Your actions`; Actions screen group headers `Your actions` and `Made by AI`; editor: when the draft's source is Ai, a line "Made by AI" with a text button `Move to Your actions`. The AI section and its header show only when it has actions. Dragging stays inside a group.

- [ ] **Step 1: Write failing tests:**
  - `QuickActionsCustomTest`: `ai actions show under Made by AI` (node with text `Made by AI` exists; the AI tile is below it); `no AI section without AI actions`; `Move to Your actions clears the mark` (click menu item ⇒ store action `source == User`).
  - `ActionsScreenTest`: `list groups AI actions under their header`; `drag stays in group` (via `ActionsViewModel.move` ⇒ `moveInGroup`, assert order).
- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run tests; expect pass.**
- [ ] **Step 5: Commit** `feat(actions): Made by AI section in the sheet and Actions screen`

---

### Task 6: Bundled skill and renderer

**Files:**
- Create: `composeApp/src/jvmMain/resources/ai-skill/SKILL.md` (template, from `.claude/skills/create-custom-action/SKILL.md`)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/actions/AiSkill.kt`
- Delete: `.claude/skills/create-custom-action/` and `bluetooth.scrcpy-actions.json` (repo root, untracked)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/actions/AiSkillTest.kt`

**Interfaces:**
- Produces:
  - `object AiSkill { const val FOLDER = "scrcpy-vwr-actions"; const val VERSION = 1; fun render(inbox: Path, actionsFile: Path): String; fun installedVersion(text: String): Int?; fun installedInbox(text: String): Path? }`
  - Template placeholders: `{{SKILL_VERSION}}`, `{{INBOX_DIR}}`, `{{ACTIONS_FILE}}`, `{{ICONS}}` (comma-separated `ActionIcons` names in group order). `installedInbox` reads the path back from a fixed marker line `Inbox folder: <path>`.

Template content changes from the repo skill (spec "Bundled skill"): frontmatter per Global Constraints; section 6 replaced by: read `{{ACTIONS_FILE}}` read-only for names and ids; write `<slug>.json.tmp` in `{{INBOX_DIR}}` then rename to `<slug>.json`; reuse an `id` to edit; optional `"author"`; after ~3 s check the file is gone or read `rejected/<file>.error.txt`; app not running ⇒ added at next start; folder is outside the project, ask the user to approve write access; never write `actions.json`; deleting is not possible, send the user to the app. Shell examples quote paths (`"{{INBOX_DIR}}"`). Keep sections 1-3 and 7, including the nonzero-exit guidance.

- [ ] **Step 1: Write failing tests:**
  - `render leaves no placeholders`: `"{{" !in render(...)`.
  - `render lists every icon`: each `ActionIcons` name appears.
  - `frontmatter has name description and version`: starts with `---\nname: scrcpy-vwr-actions\n`, contains `scrcpy-vwr-skill-version: 1`.
  - `installedVersion and installedInbox read back what render wrote`.
  - `paths with spaces are quoted in commands`: inbox `/tmp/Jo Smith/actions-inbox` ⇒ contains `"/tmp/Jo Smith/actions-inbox"`.
  - `template loads from classpath` (guards packaging).
- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement**; template read with `AiSkill::class.java.getResource("/ai-skill/SKILL.md")`.
- [ ] **Step 4: Run tests; expect pass.** Also run `./gradlew :composeApp:createReleaseDistributable` (or the project's release task) and confirm `ai-skill/SKILL.md` is in the jar: `unzip -l <app jar> | grep ai-skill`.
- [ ] **Step 5: Commit** `feat(actions): bundle the AI skill and render it with this app's paths`

---

### Task 7: Installer and status

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/actions/AiSkillInstaller.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/state/AppSettings.kt` (`Settings`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/di/AppModule.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/actions/AiSkillInstallerTest.kt`

**Interfaces:**
- Consumes: Task 6 `AiSkill`.
- Produces:
  - `enum class AiTool(val label: String, val skillsDir: String, val markerDir: String) { Claude("Claude Code", ".claude/skills", ".claude"), Gemini("Gemini CLI", ".gemini/skills", ".gemini"), Codex("Codex", ".agents/skills", ".codex"), Grok("Grok CLI", ".grok/skills", ".grok") }`
  - `sealed interface SkillTarget { val label: String; val skillDir: Path; data class Tool(val tool: AiTool, ...) ; data class Folder(val dir: Path, ...) }` — `skillDir` = `<skills dir>/scrcpy-vwr-actions`; Folder label is the folder's file name.
  - `enum class SkillStatus { NotInstalled, UpToDate, UpdateAvailable }`
  - `class AiSkillInstaller(home: Path, inbox: Path, actionsFile: Path)` with `fun targets(customFolders: List<String>): List<SkillTarget>`, `fun isFound(tool: AiTool): Boolean`, `fun status(target: SkillTarget): SkillStatus` (UpdateAvailable when installed version < `AiSkill.VERSION` or `installedInbox != inbox`), `fun install(target: SkillTarget): Result<Unit>` (creates dirs, writes `SKILL.md`).
  - `Settings.aiSkillFolders: List<String> = emptyList()` (custom folders, absolute paths, no duplicates); `AppSettings.addAiSkillFolder(path: String)` following the existing update pattern.
  - Koin: `@Singleton fun aiSkillInstaller(dataDir: DataDir) = AiSkillInstaller(Path.of(System.getProperty("user.home")), dataDir.path.resolve("actions-inbox"), dataDir.path.resolve("actions.json"))`.

- [ ] **Step 1: Write failing tests** (temp dir as `home`):
  - `found only when marker dir exists`: create `.gemini` ⇒ `isFound(Gemini)`, not `isFound(Codex)`.
  - `codex installs to agents skills`: `install(Tool(Codex))` writes `home/.agents/skills/scrcpy-vwr-actions/SKILL.md`.
  - `status not installed then up to date`.
  - `older version is update available`: write a file with `scrcpy-vwr-skill-version: 0` ⇒ `UpdateAvailable`.
  - `different inbox path is update available`.
  - `install replaces existing SKILL md`.
  - `folder target installs into subfolder`.
  - `install into unwritable folder returns failure` (read-only dir; skip on Windows via `assumeTrue`-style early return).
  - `AppSettingsTest`: `aiSkillFolders round trips and dedupes`.
- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run tests; expect pass.**
- [ ] **Step 5: Commit** `feat(settings): install the AI skill into AI tool folders`

---

### Task 8: Settings "AI" category and dialogs

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsRegistry.kt` (`SettingsCategoryId`, `SettingsActions`, `settingsCategories`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsViewModel.kt` (`SettingsUiState`, new methods)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsScreen.kt` (wire actions, show dialogs)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsFileDialogs.kt` (`chooseFolder(title: String): Path?`)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/AiSkillDialogs.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/AiSkillSettingsTest.kt`, existing `SettingsSearch` test file

**Interfaces:**
- Consumes: Task 7 installer types, `Settings.aiSkillFolders`.
- Produces:
  - `SettingsCategoryId.Ai` placed between `Adb` and `Updates`.
  - `data class AiSkillRow(val target: SkillTarget, val found: Boolean, val status: SkillStatus)`; `SettingsUiState.aiSkill: List<AiSkillRow>`; `SettingsUiState.aiInstallResult: List<Pair<String, String?>>?` (label to error, null error = ok; non-null list = show Done dialog).
  - `SettingsViewModel.installAiSkill(targets: List<SkillTarget>)` (on IO; refreshes rows; sets result), `addAiSkillFolder(path: Path)`, `dismissAiInstallResult()`.
  - `SettingsActions.onOpenAiSkill`, `onInstallAiSkill(List<SkillTarget>)`, `onChooseAiSkillFolder`, `onDismissAiInstall`.

UI copy (from the mockup):
- Entry title `AI skill for custom actions`; description `Teach an AI tool on this computer to write quick actions. They show up here and in the mirror window as soon as the AI makes them.`; layout Block; button `Add skill to an AI tool…`.
- Under it, one line per row with status ≠ NotInstalled: `<label>` plus pill `up to date` or a text button `update available` (reinstalls that target).
- Dialog title `Add AI skill`, subtitle `Copies the skill into each tool's skills folder.` Rows: checkbox, label, `~`-abbreviated `skillDir`, pill `Found` / `Not found`; found tools pre-checked; last row `Choose folder…` / `For any other AI tool` (adds the folder to the list, checked). Buttons `Cancel`, `Add to <n> tools` (`Add to 1 tool` singular; disabled at 0).
- Done dialog title `Skill added` (any failure: `Some installs failed`); `✓ <label>` or `✗ <label>: <error>`; text `Try it: open the tool and ask "make a Scrcpy Vwr toggle for Bluetooth". Restart the tool if it was already open.`; buttons `Copy example prompt` (copies `make a Scrcpy Vwr toggle for Bluetooth`), `Done`.

- [ ] **Step 1: Write failing tests:**
  - `AI category sits between adb and Updates` (order of `settingsCategories` ids).
  - search test: query `gemini` finds `AI skill for custom actions`.
  - `AiSkillSettingsTest` (fake installer with temp home): `found tools are pre-checked`; `add button counts checked tools` (`Add to 2 tools`); `install shows Done dialog with results`; `update available reinstalls`.
- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `./gradlew :composeApp:jvmTest`; expect all pass.**
- [ ] **Step 5: Manual check:** run the app, install into Claude Code, open Claude Code elsewhere, ask for a Bluetooth toggle, confirm it appears under "Made by AI" with a ✦ snackbar.
- [ ] **Step 6: Commit** `feat(settings): AI category with Add AI skill dialog`
