# Custom Quick Actions and ADB Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the mirror quick actions sheet into two animated tabs (tiles of built-in and custom actions, and a streaming `adb shell` console), with an Actions rail page to create, edit, import and export custom actions.

**Architecture:** A new `actions` package holds the model, JSON codec, template variables, icon catalog, import plan and an `ActionStore` singleton (`StateFlow`, persisted like `PresetStore`). `AdbClient` gains a streaming shell; a per-device `ShellRunner` wraps buffered (30 s) and streamed runs with the exit-code marker. The mirror window owns a `ShellConsole` and an extended `QuickActions`; the main window gets `TopLevelRoute.Actions` with an `ActionsViewModel`. Mirror → main navigation goes through an `ActionsNavigation` request flow.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.12.2 (desktop JVM), Material 3, `compose.materialIconsExtended`, kotlinx.serialization 1.11.0, coroutines 1.11.0, Koin 4.2.2 (annotations + compiler plugin), Navigation 3, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-04-custom-quick-actions-shell-design.md`. Mockups: https://claude.ai/artifact/8qsRit5mptX5svVhYiWPFg (version 4). Read both.

## Global Constraints

- A saved action's command is shown only in the Actions editor and the import preview. Never on tiles, tooltips, variable prompts, confirm dialogs, chips, or console headers (`▶ <action name>`).
- Typed console commands echo as `$ <command>`.
- Custom actions are global (no per-device data in the store). Built-ins are fixed, in today's order, never hideable or reorderable.
- Variables only in run-once commands; in toggle commands braces are literal.
- Sheet widths: closed 0 dp, Actions 280 dp, Shell 400 dp.
- Tab animation "Slide": 40 dp offset, incoming delayed ~60 ms; to Shell 260 ms emphasized-decelerate, to Actions 200 ms emphasized-accelerate (`quickActionsSpec(open)` easings).
- Buffered runs (toggles' commands, Toast/None run-once, Test run, Test read): 30 000 ms overall limit. Timeout message: `Timed out after 30 s; set Output to Shell tab for long commands`.
- Console: last 5 000 lines kept; history last 100 commands, app-lifetime, shared across devices.
- One console command at a time per device; a second is refused with `Stop the running command first`. Nothing queues.
- Store file: `actions.json` under `DataDir`. Export file suggestion: `scrcpy-actions.json`. File format `"format": "scrcpy-viewer.actions"`, `"version": 1`.
- 48 icons, six groups of 8, names exactly as in the spec's Icons section. Defaults: `toggle_on` (toggle), `play_arrow` (run once).
- Exit marker: commands are sent as `"$cmd\necho __scrcpyviewer_exit=$?"` (newline, not `;`, so a trailing `#comment` or `&` cannot swallow it).
- Tests: `./gradlew :composeApp:jvmTest --tests '<FQN>'` from the repo root. Full suite before each commit touching shared code: `./gradlew :composeApp:jvmTest`.

## Review Focus

1. **Read output with `\r\n` or trailing spaces** (`"7\r\n"`): equals/contains/regex match after `trim()`; a toggle must not read "off" because of a carriage return. Test in Task 1.
2. **Stream chunks split a line or the exit marker** (`"...__scrcpyvi"` then `"ewer_exit=0\n"`): marker still detected, never printed, partial last line held until newline or end. Test in Task 5.
3. **Import file that names the same action twice, has `version: 2`, or misses required fields:** duplicates within the file are planned as separate items (second gets "Keep both" rename on apply), version > 1 rejected with `This file is from a newer version of scrcpy-viewer`, missing fields reject the file naming the action index. Tests in Tasks 1 and 3.
4. **Action deleted or edited in the main window while its variable prompt or confirm is open in a mirror window:** the prompt closes; running it after deletion does nothing; no crash. Test in Task 7.
5. **Command with a trailing comment or background `&`** (`logcat -d # all`): exit code still reported (newline marker). Test in Task 4.

---

## File Structure

```
composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/
  actions/
    CustomAction.kt          model (sealed), OnWhen, Variable, OutputTarget
    ActionCodec.kt           file format encode/decode + errors
    CommandTemplate.kt       {var} parse/substitute
    ActionIcons.kt           48-icon catalog, groups, keywords, search, fallback
    ActionImportPlan.kt      plan/apply with ConflictChoice (reuses presets.ConflictChoice)
    ActionStore.kt           StateFlow + JsonFile(actions.json)
  adb/
    AdbClient.kt             + shellStream()
    ShellRunner.kt           buffered (30 s) + streamed runs with exit marker
  ui/mirror/
    QuickActions.kt          + custom toggles/run-once, tabs, prompts
    QuickActionsSheet.kt     tabs, Slide transition, width spec
    ActionTiles.kt           tile grid, divider, context menu, pop-in
    RunPrompts.kt            variable prompt + confirm dialogs
    ShellConsole.kt          console state (lines, filter, pause, history, run/stop)
    ShellTab.kt              Shell tab UI
    MirrorWindow.kt          3-width growth, wiring
  ui/actions/
    ActionsNavigation.kt     request flow mirror → main
    ActionsViewModel.kt      list, draft editor, test run/read, import/export
    ActionsScreen.kt         list-detail screen
    ActionEditor.kt          editor form
    IconPicker.kt            popover grid
    ActionImportDialog.kt    preview with commands
  ui/navigation/TopLevelNavigation.kt   + Actions route
  ui/App.kt                  + rail item, screen, navigation requests
  Main.kt                    toFront on request
  di/AppModule.kt            + ActionStore, ShellHistory, ActionsNavigation singletons
```

---

### Task 1: Action model and file codec

**Files:**
- Create: `actions/CustomAction.kt`, `actions/ActionCodec.kt`
- Test: `jvmTest/.../actions/ActionCodecTest.kt`, `jvmTest/.../actions/OnWhenTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable sealed interface CustomAction { val id: String; val name: String; val icon: String }
  @Serializable @SerialName("toggle") data class ToggleAction(
      override val id: String, override val name: String, override val icon: String = "toggle_on",
      val on: String, val off: String, val read: String? = null, val onWhen: OnWhen? = null) : CustomAction
  @Serializable @SerialName("once") data class RunOnceAction(
      override val id: String, override val name: String, override val icon: String = "play_arrow",
      val command: String, val output: OutputTarget = OutputTarget.Toast, val confirm: Boolean = false,
      val variables: Map<String, Variable> = emptyMap()) : CustomAction
  @Serializable enum class OutputTarget { @SerialName("toast") Toast, @SerialName("shell") Shell, @SerialName("none") None }
  @Serializable data class OnWhen(val mode: MatchMode, val value: String) { fun matches(output: String): Boolean }
  @Serializable enum class MatchMode { @SerialName("equals") Equals, @SerialName("contains") Contains, @SerialName("regex") Regex }
  @Serializable data class Variable(val label: String, val kind: VariableKind = VariableKind.Text,
      val default: String = "", val choices: List<String> = emptyList())
  @Serializable enum class VariableKind { @SerialName("text") Text, @SerialName("list") List, @SerialName("app") App }
  object ActionCodec {
      const val FORMAT = "scrcpy-viewer.actions"; const val VERSION = 1
      fun encode(actions: List<CustomAction>): String
      fun decode(text: String): List<CustomAction>   // throws ActionFileException(message)
  }
  class ActionFileException(message: String) : IllegalArgumentException(message)
  ```
  Json discriminator `"type"`; `ignoreUnknownKeys = true`; `prettyPrint = true`.

- [ ] **Step 1: Write failing tests**
  - `round trips both kinds` — encode the spec's two example actions (plus `icon`), decode, assert equal.
  - `decodes the spec example file` — paste the spec's JSON block (with `"onWhen": {"mode":"equals","value":"7"}`), assert `ToggleAction.read == "settings get global stay_on_while_plugged_in"` and `RunOnceAction.variables["package"]!!.kind == VariableKind.App`.
  - `rejects newer version` — `version: 2` → `ActionFileException` with message `This file is from a newer version of scrcpy-viewer`.
  - `rejects wrong format` — `"format": "x"` → message `Not a scrcpy-viewer actions file`.
  - `names the bad action` — second action missing `command` → message starts with `Action 2:`.
  - `ignores unknown fields`.
  - `OnWhenTest`: `Equals("7").matches("7\r\n")` true; `Contains("on").matches(" mode: on ")` true; `Regex("^[1-3]$").matches("2\n")` true; invalid regex `"("` returns false (no throw).
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests 'com.programmersbox.scrcpyviewer.actions.*'` — FAIL (unresolved).
- [ ] **Step 3: Implement** the types and codec. Decode the envelope as `JsonObject` first to check `format` and `version`, then decode each element of `actions` individually to number failures (`"Action ${i+1}: ${e.message}"`). `OnWhen.matches` trims output; regex via `runCatching { Regex(value).containsMatchIn(t) }.getOrDefault(false)` for `Regex` mode.
- [ ] **Step 4: Run** same command — PASS.
- [ ] **Step 5: Commit** `feat(actions): custom action model and file codec`.

### Task 2: Command template variables and icon catalog

**Files:**
- Create: `actions/CommandTemplate.kt`, `actions/ActionIcons.kt`
- Test: `jvmTest/.../actions/CommandTemplateTest.kt`, `jvmTest/.../actions/ActionIconsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object CommandTemplate {
      fun variables(command: String): List<String>               // distinct, in first-appearance order
      fun substitute(command: String, values: Map<String, String>): String
  }
  data class ActionIcon(val name: String, val vector: ImageVector, val keywords: List<String>)
  data class IconGroup(val title: String, val icons: List<ActionIcon>)
  object ActionIcons {
      val groups: List<IconGroup>                                  // 6 groups × 8, spec order and titles
      fun forName(name: String?, default: String): ImageVector    // unknown/null → default's vector
      fun search(query: String): List<IconGroup>                  // empty groups dropped; blank → groups
      const val TOGGLE_DEFAULT = "toggle_on"; const val RUN_ONCE_DEFAULT = "play_arrow"
  }
  ```
- [ ] **Step 1: Write failing tests**
  - `finds variables` — `variables("pm clear {package} && am start {package}/{activity}") == listOf("package", "activity")`.
  - `ignores non-names` — `variables("echo {1x} { a } {}")` empty.
  - `escapes braces` — `substitute("awk '{{print $1}}' {f}", mapOf("f" to "x")) == "awk '{print $1}' x"`; `variables` of it `== listOf("f")`.
  - `leaves unknown values` — `substitute("{a} {b}", mapOf("a" to "1")) == "1 {b}"`.
  - `ActionIconsTest`: 6 groups, each 8, titles `Connectivity, Device, Display and input, Apps, Debug, General`; all 48 names distinct; `forName("nope", "play_arrow") === forName("play_arrow", "play_arrow")`; `search("logs")` contains `terminal`; `search("bat")` names == `battery_full, battery_saver`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Name regex `[A-Za-z_][A-Za-z0-9_]*`. Scan left to right: `{{` → `{`, `}}` → `}`, `{name}` → value or left as-is. Icons map snake_case names to `Icons.Filled.*` (e.g. `install_mobile` → `Icons.Filled.InstallMobile`, `open_in_new` → `Icons.AutoMirrored.Filled.OpenInNew`, `list_alt` → `Icons.AutoMirrored.Filled.ListAlt`; use AutoMirrored where the non-mirrored one is deprecated). Keywords as in the mockup (e.g. terminal: `shell, logs, logcat`; delete: `clear, uninstall`).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(actions): command variables and icon catalog`.

### Task 3: Action store, import plan, DI

**Files:**
- Create: `actions/ActionStore.kt`, `actions/ActionImportPlan.kt`
- Modify: `di/AppModule.kt` (`StorageModule`)
- Test: `jvmTest/.../actions/ActionStoreTest.kt`, `jvmTest/.../actions/ActionImportPlanTest.kt`

**Interfaces:**
- Consumes: Task 1 types; `presets.ConflictChoice`; `state.JsonFile`.
- Produces:
  ```kotlin
  class ActionStore(file: Path) {
      val actions: StateFlow<List<CustomAction>>
      val persistenceError: StateFlow<String?>
      fun upsert(action: CustomAction)            // replace by id or append
      fun delete(id: String)
      fun duplicate(id: String): CustomAction     // new id, uniqueName
      fun move(id: String, toIndex: Int)
      fun replaceAll(actions: List<CustomAction>)
      fun isNameTaken(name: String, exceptId: String? = null): Boolean   // case-insensitive
      fun uniqueName(base: String): String        // "base (2)"…
  }
  data class ActionImportItem(val action: CustomAction, val conflictsWith: CustomAction?, val choice: ConflictChoice, val include: Boolean)
  object ActionImportPlan {
      fun plan(existing: List<CustomAction>, incoming: List<CustomAction>): List<ActionImportItem>  // clash default Replace
      fun apply(existing: List<CustomAction>, items: List<ActionImportItem>, newId: () -> String = { UUID.randomUUID().toString() }): List<CustomAction>
  }
  fun CustomAction.withId(id: String): CustomAction
  fun CustomAction.withName(name: String): CustomAction
  ```
  Koin: `@Singleton fun actionStore(dataDir: DataDir): ActionStore = ActionStore(dataDir.path.resolve("actions.json"))`.
- [ ] **Step 1: Write failing tests** (mirror `PresetStoreTest` / `ImportPlanTest`): persist and reload; `move` reorders; `duplicate` names `"Stay awake (2)"`; corrupt file sets `persistenceError` path via JsonFile. Import: added-as-new items get `newId()` ids; Replace keeps the existing id; Keep both renames; Skip and `include=false` drop; **same name twice in one file** → both added, second renamed `(2)`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** following `PresetStore` (synchronized mutate + save, StoreFile `@Serializable data class(val actions: List<CustomAction> = emptyList())`). `plan` checks clashes against `existing` plus earlier incoming items.
- [ ] **Step 4: Run** `--tests 'com.programmersbox.scrcpyviewer.actions.*'` then full suite (Koin graph test) — PASS.
- [ ] **Step 5: Commit** `feat(actions): action store and import plan`.

### Task 4: Streaming shell and ShellRunner

**Files:**
- Modify: `adb/AdbClient.kt`
- Create: `adb/ShellRunner.kt`
- Test: `jvmTest/.../adb/AdbClientStreamTest.kt`, `jvmTest/.../adb/ShellRunnerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // AdbClient
  open fun shellStream(serial: String, cmd: String): Flow<String>   // raw UTF-8 chunks, no read timeout, socket closed on cancel/completion, flowOn(IO)
  // ShellRunner.kt
  data class ShellResult(val output: String, val exitCode: Int?, val millis: Long)
  sealed interface ShellEvent { data class Output(val text: String) : ShellEvent; data class Exit(val code: Int?, val millis: Long) : ShellEvent }
  open class ShellRunner(private val adb: AdbClient, private val serial: String, private val clock: () -> Long = System::currentTimeMillis) {
      open suspend fun run(cmd: String, timeoutMs: Long = BUFFERED_TIMEOUT_MS): ShellResult   // throws ShellTimeoutException
      open fun stream(cmd: String): Flow<ShellEvent>
      companion object { const val BUFFERED_TIMEOUT_MS = 30_000L; const val EXIT_MARKER = "__scrcpyviewer_exit=" ; fun wrap(cmd: String) = "$cmd\necho $EXIT_MARKER\$?" }
  }
  class ShellTimeoutException : IOException("Timed out after 30 s; set Output to Shell tab for long commands")
  ```
- [ ] **Step 1: Write failing tests**
  - `AdbClientStreamTest`: a `ServerSocket(0)` fake adb server answers `OKAY` twice, then writes `"a\n"`, flushes, waits for a latch, writes `"b\n"`, closes. `AdbClient(adbPath = { error("") }, port = server.localPort).shellStream("s", "x").toList()` joined == `"a\nb\n"`; the first chunk is received before the latch opens (proves no buffering). Second test: cancel the collector after first chunk → server sees the socket closed (read returns -1) within 2 s.
  - `ShellRunnerTest` with a scripted `AdbClient` subclass: `wrap("logcat -d # all") == "logcat -d # all\necho __scrcpyviewer_exit=$?"` (Review Focus 5); `run` parses `"out\n__scrcpyviewer_exit=3\n"` → `ShellResult("out", 3, _)`; missing marker → `exitCode = null`; slow fake + `timeoutMs = 50` → `ShellTimeoutException` with the exact message.
- [ ] **Step 2: Run** `--tests 'com.programmersbox.scrcpyviewer.adb.*'` — FAIL.
- [ ] **Step 3: Implement.** `shellStream` uses `callbackFlow` or `flow { }` with `openSocket()`, `soTimeout = 0`, read loop into 8 KiB buffer, decode with a `CharsetDecoder` that keeps partial UTF-8 sequences between reads. `run` = `withTimeout(timeoutMs) { adb.shell(serial, wrap(cmd)) }` mapping `TimeoutCancellationException` to `ShellTimeoutException`. `stream` is Task 5.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(adb): streaming shell and shell runner`.

### Task 5: Marker parsing for streamed output

**Files:**
- Modify: `adb/ShellRunner.kt`
- Test: `jvmTest/.../adb/ShellRunnerStreamTest.kt`

**Interfaces:**
- Produces: `ShellRunner.stream(cmd)` emits `Output` chunks (marker text removed) then one `Exit`. If the flow ends without the marker (stopped/killed), `Exit(null, millis)`.
- [ ] **Step 1: Write failing tests** with a fake `shellStream` emitting given chunks:
  - plain: `["a\n", "b\n__scrcpyviewer_exit=0\n"]` → outputs joined `"a\nb\n"`, `Exit(0)`.
  - **split marker** (Review Focus 2): `["x\n__scrcpyvi", "ewer_exit=", "12\n"]` → outputs `"x\n"`, `Exit(12)`.
  - a chunk ending in `"__scr"` that turns out not to be the marker (`"__scrabble\n"`) is emitted intact.
  - no marker, flow completes → `Exit(null)`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** with a carry buffer: hold back the longest suffix of the pending text that is a prefix of `"\n" + EXIT_MARKER` (or of `EXIT_MARKER` at line start); emit the rest. When the marker line is complete, parse the code and emit `Exit`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(adb): exit codes for streamed shell commands`.

### Task 6: Shell console state

**Files:**
- Create: `ui/mirror/ShellConsole.kt`
- Modify: `di/AppModule.kt` (add `ShellHistory` singleton to `MirrorModule`)
- Test: `jvmTest/.../ui/mirror/ShellConsoleTest.kt`

**Interfaces:**
- Consumes: `ShellRunner`, `ShellEvent`.
- Produces:
  ```kotlin
  class ShellHistory(private val max: Int = 100) { val items: List<String>; fun add(cmd: String) }   // dedups consecutive, newest last
  sealed interface ConsoleLine { val text: String
      data class Header(override val text: String, val typed: Boolean) : ConsoleLine   // "$ cmd" or "▶ name"
      data class Out(override val text: String) : ConsoleLine
      data class Exit(val code: Int?, val millis: Long) : ConsoleLine { override val text get() = … } }  // "exit 0 · 48 ms" / "stopped"
  @Stable class ShellConsole(private val runner: ShellRunner?, private val history: ShellHistory, private val scope: CoroutineScope, private val notify: (String) -> Unit, private val maxLines: Int = 5_000) {
      val lines: List<ConsoleLine>; val running: Boolean; val lineCount: Int
      var filter: String; var paused: Boolean
      val visible: List<ConsoleLine>            // filter applied (case-insensitive substring on Out lines; headers/exits of matching runs kept)
      val matchCount: Int
      var input: String
      fun submit(): Boolean                     // runs input as typed; false + notify("Stop the running command first") when running
      fun runAction(name: String, command: String): Boolean   // header "▶ name"
      fun stop(); fun clear()
      fun historyUp(); fun historyDown()
  }
  ```
- [ ] **Step 1: Write failing tests**: submit echoes `Header("$ wm size", typed = true)`, appends `Out` lines split on `\n` (partial line kept until newline/exit), then `Exit`; history gets the command; second submit while running returns false and notifies the exact text; `runAction("Top activity", "dumpsys …")` header text is `"▶ Top activity"` and never contains the command; cap: 5 010 lines in → 5 000 kept (oldest dropped); filter `"PROC"` matches `"Start proc"`; `stop()` cancels the stream and ends with `Exit(null)`; history max 100; Up/Down walk history and restore the draft at the bottom.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** with snapshot state; the stream runs in a `Job` on `scope`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(mirror): shell console state`.

### Task 7: Quick actions logic for custom actions and tabs

**Files:**
- Modify: `ui/mirror/QuickActions.kt`
- Test: `jvmTest/.../ui/mirror/QuickActionsCustomTest.kt` (keep `QuickActionsTest` passing)

**Interfaces:**
- Consumes: `ActionStore.actions`, `ShellRunner`, `ShellConsole`, `CommandTemplate`, `ActionsNavigation` (Task 12 — pass as `onManage: (actionId: String?) -> Unit` lambda here).
- Produces:
  ```kotlin
  enum class SheetTab { Actions, Shell }
  data class PendingRun(val action: RunOnceAction, val needsValues: Boolean, val needsConfirm: Boolean, val values: Map<String, String>)
  // QuickActionsUiState gains:
  val tab: SheetTab, val custom: List<CustomAction>, val customState: Map<String, Boolean?>,
  val customPending: Set<String>, val prompt: PendingRun?, val consoleRunning: Boolean
  // QuickActionEvent gains:
  data class SelectTab(val tab: SheetTab); data class CustomToggle(val id: String, val on: Boolean)
  data class RunCustom(val id: String, val fromChip: Boolean = false)
  data class PromptValues(val values: Map<String, String>); data object PromptConfirm; data object PromptCancel
  data class Manage(val actionId: String?)           // + New (null), Edit…, Manage actions…
  data class Duplicate(val id: String); data class Delete(val id: String)
  ```
- Behavior to implement exactly as spec "Actions tab" and "What runs at once":
  - `DeviceQuickActions.read()` batches built-ins plus every custom toggle with a `read` command in one shell call (same separator); custom state = `onWhen.matches(output)`; read failure → null.
  - Toggle without `read`: state remembered in an app-lifetime map keyed `serial to id` (a `CustomToggleMemory` singleton or a companion map; pick the singleton, add to `MirrorModule`).
  - Toggle flip: optimistic, `ShellRunner.run(on|off)`, revert + notify on non-zero exit or timeout.
  - Run once: values prefilled from last values (app-lifetime, keyed `serial, id, var`), else `Variable.default`. Prompt → confirm → run. Output Toast: first non-blank line or `Done`; None: failures only; Shell (or `fromChip`): switch tab to Shell and `console.runAction(name, substituted)`; refused when console running.
  - Store changes while a prompt is open for an action that no longer exists (or changed) → `prompt = null` (Review Focus 4).
  - Closing the sheet calls `console.stop()`; Toast/None runs are not cancelled.
  - The last selected tab is remembered for the app's lifetime across windows (an app-lifetime holder, not persisted); the sheet opens on it.
- [ ] **Step 1: Write failing tests** (scripted adb + in-memory `ActionStore` on a temp dir + eager scope as in `QuickActionsTest`): batched read includes custom read commands and parses `"7\r\n"` as on; flip failure reverts and notifies; Toast shows `"Done"` for empty output; timeout notifies the exact timeout text; Shell-output run switches `tab` to Shell and console header is `▶ <name>`; run while console busy notifies `Stop the running command first`; deleting the action while `prompt != null` clears `prompt`; last values prefill a second prompt; close stops the console.
- [ ] **Step 2: Run** `--tests 'com.programmersbox.scrcpyviewer.ui.mirror.QuickActions*'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS (both test classes).
- [ ] **Step 5: Commit** `feat(mirror): custom quick actions and sheet tabs`.

### Task 8: Actions tab UI (tiles, divider, menu, prompts)

**Files:**
- Create: `ui/mirror/ActionTiles.kt`, `ui/mirror/RunPrompts.kt`
- Modify: `ui/mirror/QuickActionsSheet.kt` (replace rows with tiles for the Actions tab)
- Test: `jvmTest/.../ui/MirrorScreenshotTest.kt` (new cases), `jvmTest/.../ui/mirror/TileStateLineTest.kt`

**Interfaces:**
- Consumes: Task 7 state/events, `ActionIcons.forName`.
- Produces: `@Composable fun ActionsTabContent(state: QuickActionsUiState, onEvent: (QuickActionEvent) -> Unit)`; `fun tileStateLine(action: CustomAction, state: Boolean?): String?`.
- Spec details: two-column grid; label "Built in"; divider "Your actions"; dashed `+ New action` last (always shown); toggle tile filled with `primary` when on, state "On"/"Off"/none; run-once ▶ mark; spinner while pending; right-click (`onPointerEvent(PointerEventType.Press)` with secondary button) → `DropdownMenu` Edit… / Duplicate / Delete (Delete confirms); built-ins no menu; Reboot keeps its confirm and error color; pop-in (`scaleIn(0.85f) + fadeIn`, ~300 ms) for ids that appear while open; header ⋮ menu with "Manage actions…". Variable prompt: one form, Text field / List dropdown / App searchable list from `pm list packages` (`-3` unless "Show system apps"), Cancel / Run, no command text. Confirm dialog names the action only.
- [ ] **Step 1: Write failing tests**: `tileStateLine` → `"Asks for App to clear"` with a variable labelled "App to clear", `"Asks first"` with confirm and no variables, `null` otherwise; toggle `"On"`/`"Off"`/null. Screenshot cases `mirror-quick-actions-tiles(.png|-dark.png)` with two custom actions, and `mirror-quick-actions-prompt.png` with an App prompt open.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; open the PNGs and compare with mockup sections 2 and 5.
- [ ] **Step 5: Commit** `feat(mirror): action tiles and run prompts`.

### Task 9: Shell tab UI

**Files:**
- Create: `ui/mirror/ShellTab.kt`
- Test: `jvmTest/.../ui/MirrorScreenshotTest.kt` (new cases)

**Interfaces:**
- Consumes: `ShellConsole`, custom run-once actions (chips), `QuickActionEvent.RunCustom(id, fromChip = true)`.
- Produces: `@Composable fun ShellTabContent(console: ShellConsole, chips: List<RunOnceAction>, onEvent: (QuickActionEvent) -> Unit)`.
- Spec details, top to bottom: Filter field (placeholder `Filter output`, shows `"$matchCount of $lineCount"` and ✕ when non-empty, Esc clears); live bar while running (red dot, `Streaming · <n> lines`, Pause, Copy of visible lines); console (`SelectionContainer`, `LazyColumn`, monospace, matches highlighted, exit ≠ 0 in `error` color, auto-scroll unless paused or scrolled up, Copy and Clear in a corner); chips (names only); command field (`$` prefix, placeholder `Type an adb shell command`, Enter runs, Up/Down history, `Ctrl+C` stops when no selection, Run ↔ red `■ Stop`); field requests focus when the tab becomes Shell.
- [ ] **Step 1: Write failing tests**: screenshots `mirror-shell-finished.png` (three runs, one failing) and `mirror-shell-streaming.png` (running, filter "Start proc").
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; compare with mockup section 3.
- [ ] **Step 5: Commit** `feat(mirror): shell tab`.

### Task 10: Tabs, Slide transition, and three-width window growth

**Files:**
- Modify: `ui/mirror/QuickActionsSheet.kt`, `ui/mirror/MirrorWindow.kt:648-680, 795-810, 1150-1160`
- Test: `jvmTest/.../ui/mirror/SheetWidthTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  val QUICK_ACTIONS_SHELL_WIDTH = 400.dp                     // QUICK_ACTIONS_WIDTH stays 280.dp
  fun sheetWidth(open: Boolean, tab: SheetTab): Dp           // 0 / 280 / 400
  fun sheetWidthSpec(from: Dp, to: Dp): TweenSpec<Float>     // growing → 260 ms decel, shrinking → 200 ms accel
  fun slideTransform(toShell: Boolean): ContentTransform     // for AnimatedContent(targetState = tab)
  ```
- Behavior: tab control (two segments, highlight slides with `animateDpAsState` on the same spec); `AnimatedContent` with `slideInHorizontally { ±40.dp }` + `fadeIn(delay 60 ms)` togetherWith `slideOutHorizontally { ∓40.dp }` + `fadeOut`; the window-growth `LaunchedEffect` keys on `sheetWidth(open, tab)` and animates `sheetGrowth` to it with `sheetWidthSpec` (stage pinned as today; maximized/fullscreen take width from the stage). Red dot on the Shell segment while `consoleRunning`. Disconnect stops the console.
- [ ] **Step 1: Write failing tests**: `sheetWidth(false, Shell) == 0.dp`, `(true, Actions) == 280.dp`, `(true, Shell) == 400.dp`; `sheetWidthSpec(280.dp, 400.dp).durationMillis == 260`, `(400.dp, 280.dp) == 200`, `(0.dp, 400.dp) == 260`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** tests — PASS. Then run the app (`./gradlew :composeApp:run`) with a device and check the switch looks like the mockup's Slide demo: window grows smoothly, no stage resize, no jump on rapid toggling.
- [ ] **Step 5: Commit** `feat(mirror): animated sheet tabs`.

### Task 11: Actions rail route and mirror → main navigation

**Files:**
- Create: `ui/actions/ActionsNavigation.kt`
- Modify: `ui/navigation/TopLevelNavigation.kt`, `ui/App.kt`, `Main.kt`, `di/AppModule.kt`, `ui/mirror/MirrorWindow.kt` (pass `onManage`)
- Test: `jvmTest/.../ui/AppNavigationTest.kt` (extend)

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data object Actions : TopLevelRoute          // TopLevelRoute.all = Devices, Presets, Actions, Settings
  class ActionsNavigation { val requests: SharedFlow<ActionsRequest>; fun open(actionId: String?, new: Boolean = false) }
  data class ActionsRequest(val actionId: String?, val new: Boolean)
  ```
  Rail label "Actions", icon `Icons.Filled.Bolt`. App collects `requests`: `navigator.select(TopLevelRoute.Actions)` and forwards to `ActionsViewModel.handle(request)` (Task 12). Main.kt's `AppWindow` content collects the same flow and calls `window.toFront()` (un-minimizing when iconified).
- [ ] **Step 1: Write failing tests**: rail order `[Devices, Presets, Actions, Settings]`; emitting `open("id")` selects Actions.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** (temporary placeholder screen until Task 12).
- [ ] **Step 4: Run** full suite — PASS.
- [ ] **Step 5: Commit** `feat(actions): Actions rail item and navigation from the mirror window`.

### Task 12: Actions view model

**Files:**
- Create: `ui/actions/ActionsViewModel.kt`
- Test: `jvmTest/.../ui/actions/ActionsViewModelTest.kt`

**Interfaces:**
- Consumes: `ActionStore`, `ActionCodec`, `ActionImportPlan`, `DeviceTracker.devices` (connected serials for Test run), `AdbClient` → `ShellRunner`.
- Produces:
  ```kotlin
  data class ActionDraft(val id: String?, val type: ActionType, val name: String, val icon: String,
      val on: String, val off: String, val read: String, val matchMode: MatchMode, val matchValue: String,
      val command: String, val variables: Map<String, Variable>, val output: OutputTarget, val confirm: Boolean)
  enum class ActionType { Toggle, RunOnce }
  data class DraftErrors(val name: String?, val on: String?, val off: String?, val command: String?)
  data class TestResult(val running: Boolean, val output: String, val exitCode: Int?, val note: String?)   // note e.g. read verdict "Counts as on"
  data class ActionsUiState(val actions: List<CustomAction>, val selectedId: String?, val draft: ActionDraft?, val dirty: Boolean,
      val errors: DraftErrors, val devices: List<String>, val testDevice: String?, val test: TestResult?, val persistenceError: String?)
  @KoinViewModel class ActionsViewModel(store: ActionStore, tracker: DeviceTracker, adb: AdbClient, scope: CoroutineScope = defaultViewModelScope()) : ViewModel(scope) {
      val state: StateFlow<ActionsUiState>; val importState: StateFlow<ActionImportUiState?>; val messages: SharedFlow<String>
      fun handle(request: ActionsRequest); fun select(id: String): Boolean   // false when dirty (UI asks to discard)
      fun discardAndSelect(id: String?); fun new()
      fun edit(transform: (ActionDraft) -> ActionDraft)   // re-derives variables from command, keeping existing settings per name
      fun setType(type: ActionType)                        // keeps name; swaps default icon if still the other default
      fun save(): Boolean; fun delete(id: String); fun duplicate(id: String); fun move(id: String, toIndex: Int)
      fun testRun(values: Map<String, String>); fun testRead(); fun setTestDevice(serial: String)
      fun exportJson(): String; fun beginImport(text: String); fun setChoice(i: Int, c: ConflictChoice); fun setInclude(i: Int, b: Boolean); fun confirmImport(); fun cancelImport()
  }
  data class ActionImportUiState(val items: List<ActionImportItem>, val error: String?) { val importCount: Int }
  ```
- [ ] **Step 1: Write failing tests**: `new()` draft is Toggle with icon `toggle_on`; `setType(RunOnce)` keeps name and switches icon to `play_arrow`; editing command `"pm clear {package}"` adds variable `package` with label `package`, kind Text; renaming a variable label survives further command edits; save validates (name required + unique, on/off required, command required) with errors; save upserts and clears `dirty`; `select` while dirty returns false; `testRead` with fake output `"7\r\n"` and `equals 7` → note `Counts as on`; `testRun` of a Shell-output action stops after 30 s with note `Stopped after 30 s` (inject a short timeout constructor param); import of a `version: 2` file sets `error`; `confirmImport` applies the plan.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(actions): actions view model`.

### Task 13: Actions screen, editor, icon picker, import/export UI

**Files:**
- Create: `ui/actions/ActionsScreen.kt`, `ui/actions/ActionEditor.kt`, `ui/actions/IconPicker.kt`, `ui/actions/ActionImportDialog.kt`
- Modify: `ui/App.kt` (replace placeholder; `rememberListDetailPanes()` for actions), reuse `AwtPresetFileDialogs` (`chooseExportFile("scrcpy-actions.json")`)
- Test: `jvmTest/.../ui/ActionsScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 12 VM, `ActionIcons`, `ListDetailPanes`.
- Behavior per spec "Main window: Actions rail item", "Icons", "Import and export": list (name + type line `Toggle` / `Run once · 1 variable` / `Run once · to Shell`, drag handle reorder), + New / Import / Export under it, empty state; editor fields in spec order with the icon button left of Name; Output field hint `Long or streaming commands need Shell tab`; Test run on <device ▾>, Delete (confirm), Save; unsaved-changes discard dialog; icon picker popover (search, group labels, 8-column grid, accent fill on selected, arrow keys / Enter / Esc); import dialog with warning `These run shell commands on your devices. Check each one.`, rows with include checkbox, name, **commands**, clash Replace/Keep both/Skip, `Import n`.
- Show `persistenceError` as a banner above the list, as the Presets screen does.
- [ ] **Step 1: Write failing tests**: screenshots `actions-list-editor(.png|-dark.png)`, `actions-empty.png`, `actions-icon-picker.png`, `actions-import.png`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** full suite — PASS; compare PNGs with mockup sections 4 and 5.
- [ ] **Step 5: Commit** `feat(actions): actions screen, editor, icon picker and import`.

### Task 14: End-to-end check on a device

- [ ] **Step 1:** `adb devices -l`; if more than one device, ask the user which serial to use.
- [ ] **Step 2:** `./gradlew :composeApp:run`. Create "Stay awake" toggle (`svc power stayon true/false`, read `settings get global stay_on_while_plugged_in`, equals `7`) while a mirror window is open: tile pops in without reopening. Flip it; confirm with `adb -s <serial> shell settings get global stay_on_while_plugged_in`.
- [ ] **Step 3:** Create "Clear app data" (`pm clear {package}`, App variable, confirm on). Run on a test app; prompt shows no command.
- [ ] **Step 4:** Shell tab: `logcat -s ActivityManager` streams; filter works; Stop and `Ctrl+C` stop it; red dot shows on the tab from the Actions tab; switching tabs animates and the window grows to 400 dp.
- [ ] **Step 5:** Export, delete all, import the file: preview lists commands; actions return.
- [ ] **Step 6:** `./gradlew :composeApp:jvmTest` — all pass. Commit any fixes.
