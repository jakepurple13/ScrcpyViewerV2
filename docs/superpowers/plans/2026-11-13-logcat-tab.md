# Logcat Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Logcat tab to the mirror window's quick actions sheet: Play/Pause-controlled `adb logcat` stream, level colors, folded stack traces, and Android Studio's filter language with autocomplete.

**Architecture:** Pure, tested parsing in `logcat/` (threadtime line parser, trace folding, query parser/matcher/suggestions, `ps` parser), a device source over `AdbClient.shellStream`, a `LogcatController` state holder per mirror window (no Compose inside, unit tested), Compose UI under `ui/mirror/logcat/`, plugged into the sheet as `SheetTab.Logcat`. `QuickActions` owns the controller so tab switches, sheet close and disconnects stop it, and the Apps tab's "Show in Logcat" can pick an app.

**Tech Stack:** Kotlin/JVM, Compose Multiplatform desktop (Nucleus Tao backend), kotlinx.coroutines, kotlin.test, the repo's `SceneDriver` Compose test harness (see `SheetTabsTest`), screenshot tests (`MirrorScreenshotTest`), adb.

**Spec:** `docs/superpowers/specs/2026-11-13-logcat-tab-design.md` (mockup: https://claude.ai/artifact/JJMzdujpjJwUbnTXq7Vxxv). Read both before any task. Every copy string, timing and threshold below comes from the spec; the spec wins on any conflict.

## Global Constraints

- Never start `adb logcat` without a Play press. Leaving the tab, closing the sheet, and disconnect stop it; while Logcat is in its own window only disconnect does.
- First start: `logcat -v threadtime -T 1000`. Resume: `logcat -v threadtime -T '<MM-DD HH:MM:SS.mmm of last line>'`.
- Buffer: 20,000 entries per window (`LOGCAT_MAX_ENTRIES = 20_000`).
- Nothing persisted except the tab width: `logcatWidth: Int = 560` in `AppSettings`; Logcat default and minimum width `QUICK_ACTIONS_LOGCAT_WIDTH = 560.dp`.
- `package:mine` = the app picked via "Show in Logcat"; no pick → matches nothing.
- Columns PID-TID and Package hide under 600 dp sheet width or in compact view.
- Copy exact as in the spec (card, banner, hints, toasts, chips, empty state, status bar).
- Motion per the spec's Motion table; honor `systemReducesMotion()` the way `MirrorWindowMotion.kt` does.
- Colors: level and tag colors are fixed sets (light/dark by `colorScheme.surface.luminance() < 0.5`); everything else from `MaterialTheme.colorScheme` and the sheet's styled controls, so every palette and style (Glass, Soft, Retro95) works.
- Test command: `./gradlew :composeApp:jvmTest --tests '<fully.qualified.TestClass>'`; the full `./gradlew :composeApp:jvmTest` stays green after every task.
- Run Gradle from one agent at a time (parallel Gradle runs exhaust memory on this machine).
- Device work only after `cdm acquire --android --device R5CX12GDWPZ`; `cdm release` when done.

## Review Focus

- **Log flood** (a device writing 2,000+ lines/s, e.g. a debug build with verbose OkHttp): UI stays responsive; lines are batched into the controller at most every 50 ms; the 20,000 cap holds. Test in Task 6.
- **Odd lines**: tags with spaces or colons (`Finsky: [123]`), empty messages, `--------- beginning of crash` markers, CRLF, a line split across socket chunks, non-ASCII. Parser keeps or skips them correctly. Tests in Task 1.
- **Resume duplicates**: `-T <time>` replays lines at the boundary timestamp; none show twice, and a different line with the same timestamp is not dropped. Test in Task 6.
- **Five segments at the 280 dp Actions width**: "Actions", "Logcat" etc. must not clip or wrap. Test in Task 7.
- **Closing the mirror window while the Logcat window is out, or the device disconnecting**: the Logcat window goes away with the mirror (no orphan window, no running `adb logcat`); a disconnect pauses it in place. Tests in Task 11.
- **Typing a half-finished query** (`tag~:(`, `level:`, `-`, a lone `"`): no exception, no flicker of the whole list to empty; incomplete terms are ignored. Tests in Task 3.

---

### Task 1: Line model and threadtime parser

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatLine.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatLineTest.kt`

**Interfaces:**
- Produces:
  - `enum class LogLevel(val letter: Char, val word: String) { Verbose('V',"verbose"), Debug('D',"debug"), Info('I',"info"), Warn('W',"warn"), Error('E',"error"), Assert('A',"assert") }` with `companion fun fromLetter(c: Char): LogLevel?` (`F` → `Assert`).
  - `data class LogcatLine(val date: String /* "MM-DD" */, val time: String /* "HH:MM:SS.mmm" */, val pid: Int, val tid: Int, val level: LogLevel, val tag: String, val message: String)` with `val timestamp: String get() = "$date $time"`.
  - `fun parseThreadtime(line: String): LogcatLine?` — null for non-log lines.
  - `class LineSplitter { fun feed(chunk: String): List<String>; fun flush(): String? }` — splits a chunk stream into lines, strips `\r`, holds a partial last line.

- [ ] **Step 1: Write failing tests** in `LogcatLineTest`:
  - `parses a threadtime line`: `"11-13 09:24:01.123  14211  14230 W OkHttp  : <-- 503 (2011ms)"` → date `11-13`, time `09:24:01.123`, pid 14211, tid 14230, Warn, tag `OkHttp`, message `<-- 503 (2011ms)`.
  - `tag with spaces and colon keeps the first separator`: `"11-13 09:24:01.123  1  2 I Finsky: [123]: hello: there"` → tag `Finsky`, message `[123]: hello: there`. And `"… I My Tag  : x"` → tag `My Tag`.
  - `empty message`: `"11-13 09:24:01.123  1  2 D Tag: "` → message `""`.
  - `F maps to Assert`.
  - `beginning-of markers and junk are null`: `"--------- beginning of crash"`, `""`, `"hello"`.
  - `splitter joins chunks and strips CR`: feed `"a\r\nb"`, `"c\nd"` → `["a"]`, `["bc"]`; `flush()` → `"d"`.
  - `non-ASCII survives`: message `"Größe 😀"`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests 'com.programmersbox.scrcpyviewer.logcat.LogcatLineTest'` — FAIL (unresolved).
- [ ] **Step 3:** Implement. Regex: `^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+) ([VDIWEAF]) (.*?)\s*: (.*)$` with a fallback for a trailing `": "` missing (`Tag:` at end of line → empty message).
- [ ] **Step 4:** Run the test — PASS.
- [ ] **Step 5:** Commit: `feat(logcat): threadtime line parser`

### Task 2: Entries and trace folding

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatEntry.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/logcat/TraceFolderTest.kt`

**Interfaces:**
- Consumes: `LogcatLine`, `LogLevel` (Task 1).
- Produces:
  - `data class LogcatEntry(val seq: Long, val line: LogcatLine, val receivedAtMillis: Long, val trace: List<String> = emptyList())` with `val isCrash: Boolean` (tag `AndroidRuntime`, level `Error`, message starts `FATAL EXCEPTION`) and `val hasTrace: Boolean`.
  - `class TraceFolder { fun add(line: LogcatLine, seq: Long, now: Long): FoldResult }` where `sealed interface FoldResult { data class New(val entry: LogcatEntry) ; data class Appended(val entry: LogcatEntry /* the updated entry, same seq */) }`.

- [ ] **Step 1: Write failing tests** in `TraceFolderTest` (same pid/tid/tag/level unless stated):
  - `at lines fold into the line above` → one entry, `trace.size == 2`.
  - `Caused by, ... N more, Suppressed fold`.
  - `exception class line folds`: `"Sync request failed"` then `"java.net.SocketTimeoutException: timeout"` then `"\tat okhttp3…"` → one entry, 2 trace lines.
  - `FATAL EXCEPTION takes Process and exception lines`: `FATAL EXCEPTION: main`, `Process: com.example.notes, PID: 14211`, `java.lang.IllegalStateException: Note 482 has no folder`, `\tat …` → one entry, `isCrash`, 3 trace lines.
  - `different tid does not fold`; `plain message after a trace starts a new entry`.
- [ ] **Step 2:** Run `--tests 'com.programmersbox.scrcpyviewer.logcat.TraceFolderTest'` — FAIL.
- [ ] **Step 3:** Implement. Continuation when same (pid, tid, tag, level) as the open entry and the message matches `^\s+at |^Caused by: |^\s*\.\.\. \d+ more|^\s*Suppressed: |^[\w$.]+(Exception|Error|Throwable)[\w$]*(: .*)?$`, or the open entry is a crash head with fewer than 2 trace lines.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit: `feat(logcat): fold stack traces into their line`

### Task 3: Query parser and matcher

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatQuery.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatQueryTest.kt`

**Interfaces:**
- Consumes: `LogcatEntry`, `LogLevel`.
- Produces:
  - `data class EntryInfo(val pkg: String?, val process: String?)` — resolved by the caller.
  - `enum class SpanKind { Key, Negation, Operator, Quoted, Error }`; `data class QuerySpan(val start: Int, val end: Int, val kind: SpanKind)`.
  - `class LogcatQuery private constructor(...) { val spans: List<QuerySpan>; val error: String?; val isEmpty: Boolean; val highlightWords: List<String>; fun matches(e: LogcatEntry, info: EntryInfo, mine: String?, nowMillis: Long): Boolean; companion object { fun parse(text: String): LogcatQuery; val KEYS: Map<String, String> } }`
  - `fun queryTerms(text: String): List<String>` and `fun withTerm(text: String, term: String, on: Boolean): String` (for chips/clicks; turning a `level:` term on removes other `level:` terms).

- [ ] **Step 1: Write failing tests** with a fixed entry set (OkHttp W from `com.example.notes`, a crash, a Choreographer I, a system_server D):
  - `empty query matches all`; `tag contains ignores case`; `-tag excludes`; `tag~ regex`; `tag= exact`.
  - `package:mine uses the picked app` and `matches nothing when none is picked`.
  - `level:warn keeps W E A`; `level:w prefix works`.
  - `age:5m` with `nowMillis` 6 min after one entry's `receivedAtMillis` excludes it.
  - `is:crash` and `is:stacktrace`.
  - `bare word matches tag or message`; `quoted text with spaces`.
  - `a b | c` = `(a AND b) OR c`.
  - Errors carry the spec's exact hint text: unknown key, `level:loud`, `age:5x`, `is:foo`, `tag~:(`. The bad term is ignored (query still matches by the other terms).
  - `half-typed terms are ignored without error`: `level:`, `tag:`, `-`, `"` → `error == null`, matches all.
  - `spans`: `-tag~:"a b"` → Negation [0,1), Key [1,6) (incl. `~:`), Quoted [6,11).
  - `withTerm toggles and level replaces level`: `withTerm("level:info tag:x", "level:warn", true) == "tag:x level:warn"`.
- [ ] **Step 2:** Run `--tests 'com.programmersbox.scrcpyviewer.logcat.LogcatQueryTest'` — FAIL.
- [ ] **Step 3:** Implement. Tokenizer as in the mockup: `(-?)([a-z]+)([~=]?):("(?:[^"\\]|\\.)*"?|\S*)|(\|)|("(?:[^"\\]|\\.)*"?|\S+)`. `message:` also searches trace lines. Regexes compile once at parse.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit: `feat(logcat): Android Studio style query language`

### Task 4: Suggestions

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/logcat/QuerySuggestions.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/logcat/QuerySuggestionsTest.kt`

**Interfaces:**
- Produces: `data class Suggestion(val label: String, val description: String, val replaceStart: Int, val replaceEnd: Int, val insert: String)`; `data class SuggestionContext(val tags: Map<String, Int> /* tag → line count */, val packages: Set<String>, val processes: Set<String>, val mine: String?)`; `fun suggest(text: String, caret: Int, ctx: SuggestionContext): List<Suggestion>` (max 7); `fun Suggestion.applyTo(text: String): Pair<String, Int /* new caret */>`.

- [ ] **Step 1: Write failing tests**:
  - `empty token lists all keys with descriptions` (7 keys, insert `tag:` etc.).
  - `prefix filters keys and keeps the minus`: `-ta` → `-tag:`.
  - `after level: lists levels`, value inserts end with a space.
  - `after package: lists mine first with "picked in Apps: <pkg>"`; with no pick, description "Pick an app in Apps".
  - `after tag: lists tags by line count`, quoting a tag with spaces.
  - `nothing for a full value or unknown key`.
  - `applyTo replaces only the caret token` (`"level:warn ta|g:x"` style case).
- [ ] **Step 2:** Run `--tests '…logcat.QuerySuggestionsTest'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit: `feat(logcat): filter autocomplete`

### Task 5: Device source and process names

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatSource.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatSourceTest.kt` (fake `AdbClient` overriding `shellStream`, as in `ShellRunnerStreamTest`)

**Interfaces:**
- Consumes: `AdbClient.shellStream(serial, cmd): Flow<String>`, `LineSplitter`, `parseThreadtime`.
- Produces:
  - `fun logcatCommand(since: String?): String` — `logcat -v threadtime -T 1000` or `logcat -v threadtime -T '<since>'`.
  - `fun parsePs(output: String): Map<Int, String>` (`ps -A -o PID,NAME`, header skipped) and `fun packageOf(process: String): String = process.substringBefore(':')`.
  - `open class LogcatSource(adb: AdbClient, serial: String) { open fun lines(since: String?): Flow<LogcatLine>; open suspend fun processes(): Map<Int, String> }` — `lines` is cancellable (cancelling closes the socket); `processes` runs `ps -A -o PID,NAME` via `adb.shell`.

- [ ] **Step 1: Write failing tests**: `first start asks for the last 1000 lines`; `resume quotes the timestamp`; `lines parse across chunk splits and skip markers`; `parsePs maps pids and skips the header`; `packageOf strips the process suffix` (`com.example.notes:remote` → `com.example.notes`, `system_server` unchanged).
- [ ] **Step 2:** Run `--tests '…logcat.LogcatSourceTest'` — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit: `feat(logcat): device logcat source and process names`

### Task 6: LogcatController

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/logcat/LogcatController.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/logcat/LogcatControllerTest.kt` (fake `LogcatSource`, `runTest` with a test dispatcher)

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces:
  - `enum class LogcatRun { NotStarted, Running, Paused, StoppedByLeaving }`
  - `sealed interface LogcatItem { val key: Long; data class Line(val entry: LogcatEntry, val pkg: String?) : LogcatItem; data class Gap(override val key: Long, val pausedAt: String, val resumedAt: String /* host HH:MM:SS */) : LogcatItem }`
  - `@Stable class LogcatController(source: LogcatSource?, scope: CoroutineScope, notify: (String) -> Unit, clock: () -> Long = System::currentTimeMillis, maxEntries: Int = LOGCAT_MAX_ENTRIES)` with snapshot-state properties `run: LogcatRun`, `pausedAt: String?` (host `HH:MM:SS`), `query: String` (setter reparses), `parsed: LogcatQuery`, `mine: String?`, `wrap: Boolean`, `compact: Boolean`, `items: List<LogcatItem>` (all), `visible: List<LogcatItem>` (filtered; gaps always kept), `totalLines: Int`, `levelCounts: Map<LogLevel, Int>` (of visible), `nudge: Int` (increments to ask the UI to pulse Play), and functions `play()`, `pause()`, `stopForLeaving()`, `clear()`, `pick(pkg: String)`, `suggestionContext(): SuggestionContext`.
  - `const val LOGCAT_MAX_ENTRIES = 20_000`

- [ ] **Step 1: Write failing tests**:
  - `nothing runs before play` (fake source records calls; none).
  - `play starts with since = null and lines appear` → `run == Running`.
  - `pause cancels the stream and records pausedAt`; `stopForLeaving gives StoppedByLeaving`; `stopForLeaving before any play stays NotStarted`.
  - `resume asks since the last line's timestamp, drops replayed lines, keeps a new line with the same timestamp, and puts a Gap before read-back lines`.
  - `buffer keeps the newest maxEntries` (maxEntries = 5, push 8).
  - `lines are batched`: 2,000 lines emitted at once produce one `items` update (count snapshot writes via a counter on the fake clock / a `snapshotFlow` count) — at most one state write per 50 ms window.
  - `unknown pid triggers one ps lookup per second and fills the package`.
  - `query filters visible and levelCounts follow`.
  - `clear empties items and toasts "Cleared. Older lines stay on the device buffer."`.
  - `stream failure (IOException) pauses and notifies "Logcat stopped: <message>"`.
- [ ] **Step 2:** Run `--tests '…ui.mirror.logcat.LogcatControllerTest'` — FAIL.
- [ ] **Step 3:** Implement. The stream collects on `Dispatchers.IO`, buffers into a channel, and a UI-scope loop drains it every 50 ms (append, fold via `TraceFolder`, trim). Dedup on resume: remember the set of `(timestamp, pid, tid, tag, message)` of lines at the last timestamp; skip replays equal to one of them, until a line with a later timestamp arrives.
- [ ] **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit: `feat(logcat): per-window logcat controller`

### Task 7: Sheet wiring, width, Apps "Show in Logcat"

**Files:**
- Modify: `ui/mirror/QuickActions.kt` (`SheetTab`, `QuickActions` ctor + `onEvent` + `close`, `rememberQuickActions`), `ui/mirror/SheetWidths.kt`, `ui/mirror/QuickActionsSheet.kt` (constant, header subtitle, `SheetContent` branch, `QuickActionsPanel` param), `ui/mirror/AppExplorer.kt` (`AppsEvent.ShowInLogcat`, picked state), `ui/mirror/AppsTab.kt` (menu item, options-sheet button, "Logcat" badge), `ui/mirror/MirrorWindow.kt` (pass `quickActions.logcat`), `state/AppSettings.kt` (`logcatWidth`)
- Test: extend `SheetTabsTest`, `SheetWidthTest`, `AppExplorerTest`, `QuickActionsCustomTest`

All paths under `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/`.

**Interfaces:**
- Consumes: `LogcatController` (Task 6).
- Produces: `enum class SheetTab { Actions, Apps, Shell, Files, Logcat }`; `SheetWidths.logcat`; `QuickActions.logcat: LogcatController?`; `AppsEvent.ShowInLogcat(val app: DeviceApp)`; `AppsUiState.logcatPackage: String?`; `QuickActionsPanel(..., logcat: LogcatController? = null)`; `QUICK_ACTIONS_LOGCAT_WIDTH = 560.dp`.

- [ ] **Step 1: Write failing tests**:
  - `SheetWidthTest`: `assertEquals(560.dp, sheetWidth(true, SheetTab.Logcat))`; `fromSettings` clamps `logcatWidth`; resize/save of Logcat saves `logcatWidth`.
  - `QuickActionsCustomTest` (or a new `QuickActionsLogcatTest`): `leaving the Logcat tab stops logcat` (`run` becomes `StoppedByLeaving`), `closing the sheet stops it`, `disconnect stops it`, `returning to Logcat while stopped bumps nudge`.
  - `AppExplorerTest`/`QuickActionsLogcatTest`: `ShowInLogcat picks the app, adds package:mine once, and selects Logcat without starting`.
  - `SheetTabsTest`: rename `four tabs…` to `five tabs, Logcat selects, and the header follows the tab` — subtitle "Live log from this device"; and `segment labels fit at the Actions width`: at 280 dp each segment label's text layout has no overflow (`TextLayoutResult.hasVisualOverflow == false`) and one line.
- [ ] **Step 2:** Run those test classes — FAIL.
- [ ] **Step 3:** Implement. If labels overflow at 280 dp, step the segment font down to `labelMedium` when a segment is narrower than 60 dp. The Logcat branch shows `AdbUnavailableState()` when `logcat == null`. `when` over `SheetTab` in `rememberQuickActions` saves `logcatWidth`.
- [ ] **Step 4:** Run the four classes, then the full suite — PASS.
- [ ] **Step 5:** Commit: `feat(logcat): Logcat sheet tab and Show in Logcat`

### Task 8: Toolbar — filter field, Play/Pause, chips, status bar

**Files:**
- Create: `ui/mirror/logcat/LogcatTab.kt` (tab scaffold: toolbar, list slot, status bar), `ui/mirror/logcat/LogcatFilterField.kt`, `ui/mirror/logcat/LogcatColors.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/logcat/LogcatToolbarTest.kt` (SceneDriver, like `SheetTabsTest`)

**Interfaces:**
- Consumes: `LogcatController`, `suggest`/`applyTo`, `QuerySpan`.
- Produces: `@Composable fun LogcatTab(controller: LogcatController, sheetWidth: Dp)`; `@Composable fun LogcatList(...)` slot filled in Task 9 (until then a plain `LazyColumn` of message text); `object LogcatColors { @Composable fun level(l: LogLevel): Pair<Color /*bg*/, Color /*fg*/>; @Composable fun message(l: LogLevel): Color; @Composable fun tag(tag: String): Color }` with the mockup's light/dark values.

- [ ] **Step 1: Write failing tests**:
  - `play button reads Play, then Pause, then Resume` and calls the controller.
  - `a nudge bump starts the pulse animation on the Play button` (two 900 ms rings; none under reduced motion).
  - `typing a query highlights spans` (AnnotatedString span styles at the parsed ranges) `and shows the hint for level:loud`.
  - `suggestions open on focus, Down then Tab accepts`.
  - `chips toggle their term and show checked` (Picked app / Warnings and up / Crashes).
  - `mine label shows the picked package or "Pick an app in Apps"`.
  - `status bar shows "Not running", "Live", "Paused" and "X of Y lines"`.
- [ ] **Step 2:** Run `--tests '…ui.mirror.logcat.LogcatToolbarTest'` — FAIL.
- [ ] **Step 3:** Implement with `BasicTextField` + `VisualTransformation` (or `OutputTransformation`) for colors; autocomplete in a `Popup` anchored under the field at the token's x offset (from the text layout); Play/Pause icon morph with `AnimatedContent`/`graphicsLayer` per the Motion table; level mix bar with animated weights.
- [ ] **Step 4:** Run — PASS, then the full suite.
- [ ] **Step 5:** Commit: `feat(logcat): filter field, play and pause, chips`

### Task 9: Log list — rows, traces, scrolling, banners

**Files:**
- Create: `ui/mirror/logcat/LogcatList.kt`, `ui/mirror/logcat/LogcatRow.kt`
- Modify: `ui/mirror/logcat/LogcatTab.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/logcat/LogcatListTest.kt`; add Logcat scenes to `ui/MirrorScreenshotTest.kt` (running with a crash unfolded, paused banner, start card, light + dark).

**Interfaces:**
- Consumes: `LogcatController.visible`, `LogcatColors`, `withTerm`.

- [ ] **Step 1: Write failing tests**:
  - `start card shows before play and leaves after`; copy exact.
  - `rows show time, pid-tid, tag, package, level, message`; `compact hides pid-tid and package and drops millis`; `under 600 dp hides pid-tid and package`.
  - `crash trace starts unfolded; other traces fold; clicking "N lines" toggles`.
  - `clicking a tag adds tag:x and toasts "Filtering to x"; alt-click adds -tag:x and toasts "Hiding x"`.
  - `own frame click copies File.kt:118`.
  - `pinned list follows new lines; scrolled up shows "3 new lines" pill; clicking it scrolls to the end`.
  - `paused banner text for Pause and for leaving`; `gap divider text`.
  - `empty filter result shows the empty message`.
- [ ] **Step 2:** Run `--tests '…ui.mirror.logcat.LogcatListTest'` — FAIL.
- [ ] **Step 3:** Implement. `LazyColumn` keyed by `LogcatItem.key`, `Modifier.animateItem(fadeInSpec, placementSpec, fadeOutSpec)` plus an enter expand for rows that arrived live (not for the initial 1,000 or a filter change's re-entries beyond the viewport); crash flash via `Animatable` color; trace via `AnimatedVisibility(expandVertically)`; monospace font for the log; `SelectionContainer` so text can be copied.
- [ ] **Step 4:** Run, record screenshots, run the full suite — PASS.
- [ ] **Step 5:** Commit: `feat(logcat): colored log list with traces and motion`

### Task 10: Keys, real-device check

**Files:**
- Modify: `ui/mirror/logcat/LogcatTab.kt`, `ui/mirror/MirrorKeys.kt` or `KeyRouting.kt` if Space/⌘F/⌘K need routing past the mirror's key forwarding
- Test: extend `LogcatToolbarTest` and `SheetKeyFocusTest`

- [ ] **Step 1: Write failing tests**: `Space toggles play when focus is in the list, not in the field`; `Cmd+F focuses the filter`; `Cmd+K clears`; `keys in the sheet don't reach the device` (follow `SheetKeyFocusTest`).
- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then the full suite — PASS.
- [ ] **Step 5:** Device check: `cdm acquire --android --device R5CX12GDWPZ`, run the app, open a mirror, Logcat tab: confirm nothing runs before Play (`adb shell ps -A | grep logcat` empty), Play shows lines with packages, filter `package:mine` after "Show in Logcat" on an app, Pause/Resume shows the divider without duplicates, switching tabs stops the device `logcat` process. `cdm release`.
- [ ] **Step 6:** Commit: `feat(logcat): keyboard shortcuts`

### Task 11: Logcat in its own window

**Files:**
- Create: `ui/mirror/logcat/LogcatWindow.kt` (the second `AppWindow`), `ui/mirror/logcat/LogcatPoppedCard.kt`
- Modify: `ui/mirror/logcat/LogcatController.kt`, `ui/mirror/logcat/LogcatTab.kt` (pop-out button), `ui/mirror/QuickActions.kt` (stop-on-leave only when not popped; dock opens the sheet on Logcat; Show in Logcat raises the window), `ui/mirror/QuickActionsSheet.kt` (segment window icon + live dot; Logcat branch shows the card while popped), `ui/mirror/MirrorWindow.kt` (compose `LogcatWindow` while popped)
- Test: extend `LogcatControllerTest`, `QuickActionsLogcatTest`, `SheetTabsTest`; new `LogcatWindowTest` for the card and title-bar pin (SceneDriver); a screenshot scene of the card.

**Interfaces:**
- Consumes: `AppWindow(onCloseRequest, title, theme, state, alwaysOnTop, onPreviewKeyEvent, titleBar, content)` from `ui/window/AppWindow.kt`; `LogcatTab` (Task 8).
- Produces on `LogcatController`: `popped: Boolean`, `alwaysOnTop: Boolean`, `raiseRequests: Int`, `fun popOut()`, `fun dock()`, `fun raise()`, `fun toggleAlwaysOnTop()`. `popOut()` resets `alwaysOnTop = false`. `dock()` never changes `run`.
- `@Composable fun LogcatWindow(controller: LogcatController, deviceName: String, onDock: () -> Unit)` — `rememberWindowState(size = DpSize(720.dp, 640.dp))` created fresh each time it is composed; `onCloseRequest = onDock`; title "Logcat · $deviceName"; pin `IconToggleButton` in the title bar slot; `LaunchedEffect(raiseRequests)` brings the window to front.

- [ ] **Step 1: Write failing tests**:
  - `popOut keeps lines, query and run state; dock keeps them too` (running stays running, paused stays paused).
  - `while popped, leaving the tab and closing the sheet don't stop logcat; disconnect still pauses it`.
  - `dock with the sheet closed opens it on the Logcat tab`.
  - `ShowInLogcat while popped raises the window and doesn't change the tab`.
  - `alwaysOnTop starts off on every popOut and toggles with toast "Logcat window stays on top" / "Logcat window no longer stays on top"`.
  - `Logcat tab shows the own-window card with Show window and Move back here while popped`; `segment shows the window icon, and the live dot only while running`.
  - `the pop-out button reads "Open in new window", then "Move back to the sheet"`.
- [ ] **Step 2:** Run those classes — FAIL.
- [ ] **Step 3:** Implement. Compose `LogcatWindow` from `MirrorWindow` only while `controller.popped`, inside the mirror's composition so it closes with the mirror window. Card swap uses `AnimatedContent` (fade + 12 dp rise). Keys in the Logcat window go through its own `onPreviewKeyEvent` (Space, ⌘F, ⌘K) and never reach the device.
- [ ] **Step 4:** Run, record the screenshot, run the full suite — PASS.
- [ ] **Step 5:** Device check with the held device: pop out while running, switch the sheet to Files (log keeps flowing), pin on top, close the Logcat window (back in the tab, still running), pop out again (default size, pin off), close the mirror (Logcat window closes; `adb shell ps -A | grep logcat` empty). `cdm release`.
- [ ] **Step 6:** Commit: `feat(logcat): open Logcat in its own window`
