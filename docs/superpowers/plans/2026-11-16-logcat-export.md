# Logcat Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export every Logcat line in memory to an Android Studio `.logcat` file (with the current filter saved in it) or a plain `threadtime` `.txt` file, from a toolbar button with ring progress, cancel, and a "Show in Finder" toast.

**Architecture:** Pure writers and device/timestamp helpers live in `logcat/LogcatExport.kt`. `LogcatController` owns the export state machine (snapshot lines → save dialog → write on `io` to a temp file → atomic move), exposes it as snapshot state, and supports cancel. The UI (button, menu, ring, list bar, status text, in-panel toast) lives in a new `ui/mirror/logcat/LogcatExportUi.kt` and is placed by `LogcatTab`, `LogcatList` and `LogcatStatusBar`.

**Tech Stack:** Kotlin/JVM, Compose Multiplatform (Material 3), kotlinx.coroutines, kotlinx.serialization-json (already a dependency), JUnit via `kotlin.test`, `ImageComposeScene` UI tests.

**Spec:** Approved mockup https://claude.ai/artifact/UAeSTXYVDcqjLrNe8REXhj (version 3, `project/Main.dc.html`) and the decisions below. The `.logcat` schema is copied from real Studio exports on this machine (`~/Desktop/samsung-SM-A156U1-Android-16_2026-04-22_110600.logcat`).

## Global Constraints

- Export every line in `LogcatController.items` (all `LogcatItem.Line`, `Gap`s skipped). The filter never limits lines.
- `.logcat` writes the current filter text (`controller.query`) to `metadata.filter`, and `projectApplicationIds` = `listOfNotNull(controller.mine)`.
- `.txt` = `logcat -v threadtime` lines, no filter, no header.
- Lines that arrive during an export are not in it (snapshot at the moment the format is picked).
- Default file name, both formats (Studio's own pattern): `<manufacturer>-<model>-Android-<release>_<yyyy-MM-dd_HHmmss>.<ext>`, host local time. Missing manufacturer/model/release parts become `device` / `unknown`.
- Save dialog: `nativeFileDialogs(...).save("Export logs", suggestedName, ext)` via `onPickerThread { }`. A null result (Cancel) returns to idle with no toast.
- Write to `<target>.part` in the same folder, then `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` (fall back to non-atomic move on `AtomicMoveNotSupportedException`). Cancel or failure deletes the `.part` file and leaves an existing target untouched.
- Every state change animates (`motion` flag, like the rest of the tab); with `motion == false` everything snaps. Copy strings exactly as in this plan.
- Test command: `./gradlew :composeApp:jvmTest --tests '<fully.qualified.TestClass>'`. One Gradle run at a time (parallel Gradle OOMs this machine).

### `.logcat` JSON shape (copy exactly; 2-space pretty print not required, Studio reads any whitespace)

```json
{
  "metadata": {
    "device": {
      "physicalDevice": {
        "serialNumber": "R5CX12GDWPZ",
        "isOnline": true,
        "release": "16",
        "apiLevel": { "majorVersion": 36, "minorVersion": 0 },
        "featureLevel": 36,
        "manufacturer": "samsung",
        "model": "SM-A156U1",
        "type": "HANDHELD"
      }
    },
    "filter": "package:mine level:info",
    "projectApplicationIds": ["com.example.app"]
  },
  "logcatMessages": [
    { "header": { "logLevel": "INFO", "pid": 1091, "tid": 28978, "applicationId": "mnld", "processName": "mnld",
                  "tag": "MTK_SF_LIB", "timestamp": { "seconds": 1776877422, "nanos": 66000000 } },
      "message": "line one\nline two" }
  ]
}
```

- `logLevel`: `VERBOSE DEBUG INFO WARN ERROR ASSERT` (`LogLevel.name.uppercase()`).
- `applicationId` = `packageOf(process)`, `processName` = process; unknown pid → both `"pid-<pid>"`.
- `message` = `entry.line.message`, plus `"\n" + entry.trace.joinToString("\n")` when the entry has a folded trace.
- `nanos` = millis × 1,000,000 (threadtime has ms precision).
- `type`: `"WEAR"` when `ro.build.characteristics` contains `watch`, `"TV"` when it contains `tv`, else `"HANDHELD"`. Emulators also use the `physicalDevice` key (only seen key; metadata only).
- `apiLevel.majorVersion` and `featureLevel` = `ro.build.version.sdk` (0 if unknown).

## Review Focus

1. **Year rollover:** a line stamped `12-31 23:59:59.000` exported when the device date is `2027-01-01` must get year 2026, not 2027 (test in Task 1).
2. **Cancel mid-write:** no `.part` and no target file remain; a pre-existing file at the target path is unchanged (test in Task 3).
3. **Odd message text:** quotes, backslashes, tabs, newlines, emoji and an empty message round-trip through the JSON exactly (test in Task 2).
4. **Empty log:** with zero lines the Export button is disabled and its tooltip says `No lines to export` (test in Task 4).
5. **Write failure** (target folder not writable): toast `Could not export: <reason>`, state back to idle, no `.part` left (test in Task 3).

---

### Task 1: Device snapshot and timestamps

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatExport.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatSource.kt` (add `exportDevice()`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatExportDeviceTest.kt`

**Interfaces:**
- Produces:
  - `data class ExportDevice(val serial: String, val manufacturer: String, val model: String, val release: String, val sdk: Int, val type: String, val today: LocalDate, val offset: ZoneOffset)`
  - `const val EXPORT_DEVICE_COMMAND: String` — `for p in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.build.characteristics; do echo "$p=$(getprop $p)"; done; echo "date=$(date +%Y-%m-%d%z)"`
  - `fun parseExportDevice(serial: String, output: String, hostNow: ZonedDateTime): ExportDevice` — missing/blank values: manufacturer/model/release `""`, sdk `0`; bad or missing `date=` line → `hostNow.toLocalDate()` and `hostNow.offset`. `date=2026-11-16-0700` parses to `LocalDate 2026-11-16`, `ZoneOffset -07:00`.
  - `fun epochOf(line: LogcatLine, today: LocalDate, offset: ZoneOffset): Instant` — year = `today.year`, minus 1 when the line's `MM-DD` is after `today`'s `MM-DD`. `02-29` in a non-leap year → `02-28` same time.
  - `fun exportFileName(device: ExportDevice, hostNow: LocalDateTime, ext: String): String` — e.g. `samsung-SM-A156U1-Android-16_2026-11-16_064600.logcat`; blank manufacturer → `device`, blank model/release → `unknown`; characters outside `[A-Za-z0-9._-]` in each part → `_`.
  - `open suspend fun LogcatSource.exportDevice(hostNow: ZonedDateTime = ZonedDateTime.now()): ExportDevice` — runs `EXPORT_DEVICE_COMMAND` with `adb.shell(serial, ...)`; any non-cancellation exception → `parseExportDevice(serial, "", hostNow)`.

- [ ] **Step 1: Write failing tests** in `LogcatExportDeviceTest`:
  - `parses full output` — output lines `ro.product.manufacturer=samsung`, `ro.product.model=SM-A156U1`, `ro.build.version.release=16`, `ro.build.version.sdk=36`, `ro.build.characteristics=phone`, `date=2026-11-16-0700` → `ExportDevice("R5CX12GDWPZ","samsung","SM-A156U1","16",36,"HANDHELD",LocalDate.of(2026,11,16),ZoneOffset.ofHours(-7))`.
  - `watch and tv characteristics` — `nosdcard,watch` → `"WEAR"`; `tv` → `"TV"`.
  - `empty output falls back to host date` — `parseExportDevice("X", "", ZonedDateTime.of(2026,1,2,3,4,5,0, ZoneOffset.UTC))` → manufacturer `""`, sdk `0`, today `2026-01-02`, offset `UTC`.
  - `epoch same year` — line `11-16 06:46:01.112`, today `2026-11-16`, offset `-07:00` → `Instant.parse("2026-11-16T13:46:01.112Z")`.
  - `epoch year rollover` — line `12-31 23:59:59.000`, today `2027-01-01`, offset UTC → `2026-12-31T23:59:59Z`.
  - `epoch feb 29 in non-leap year` — line `02-29 10:00:00.000`, today `2027-03-01` → `2027-02-28T10:00:00Z` (offset UTC).
  - `file name` — device above, `LocalDateTime.of(2026,11,16,6,46,0)`, `"logcat"` → `samsung-SM-A156U1-Android-16_2026-11-16_064600.logcat`; blank fields → `device-unknown-Android-unknown_2026-11-16_064600.txt`; model `Pixel 9/Pro` → `Pixel_9_Pro`.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests 'com.programmersbox.scrcpyviewer.logcat.LogcatExportDeviceTest'` — FAIL (unresolved).
- [ ] **Step 3:** Implement the Produces list in `LogcatExport.kt` and `exportDevice()` in `LogcatSource.kt`.
- [ ] **Step 4:** Run the same test — PASS.
- [ ] **Step 5:** Commit `feat(logcat): device snapshot and timestamps for export`.

### Task 2: Writers

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatExport.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/logcat/LogcatExportWriterTest.kt`

**Interfaces:**
- Consumes: `ExportDevice`, `epochOf` (Task 1); `LogcatEntry`, `LogLevel`, `packageOf`.
- Produces:
  - `data class ExportRow(val entry: LogcatEntry, val process: String?)`
  - `enum class ExportFormat(val extension: String, val label: String) { Studio("logcat", "Android Studio (.logcat)"), Text("txt", "Plain text (.txt)") }`
  - `suspend fun writeStudioLogcat(out: Appendable, rows: List<ExportRow>, device: ExportDevice, filter: String, projectIds: List<String>, onProgress: (Int) -> Unit)`
  - `suspend fun writeThreadtime(out: Appendable, rows: List<ExportRow>, onProgress: (Int) -> Unit)`
  - Both call `currentCoroutineContext().ensureActive()` and `onProgress(rowsWrittenSoFar)` every 500 rows and once at the end with `rows.size`.
- Studio writer: `@Serializable` private data classes mirroring the JSON shape in Global Constraints; write `{"metadata":` + encoded metadata + `,"logcatMessages":[`, then each message encoded and joined by `,\n`, then `]}`, so progress is per row. Use one `Json { encodeDefaults = true }`.
- Threadtime writer: one line per entry plus one per trace line, each `"%s %s %5d %5d %c %-8s: %s".format(date, time, pid, tid, letter, tag, text)` + `\n`; letter = `LogLevel.letter`.

- [ ] **Step 1: Write failing tests** in `LogcatExportWriterTest`:
  - `studio json matches schema` — two rows (one with process `com.example.app:remote`, one with `null` process, pid 77); parse output with `Json.parseToJsonElement`; assert `metadata.device.physicalDevice.serialNumber`, `apiLevel.majorVersion == 36`, `featureLevel == 36`, `type == "HANDHELD"`, `filter == "package:mine level:info"`, `projectApplicationIds == ["com.example.app"]`; first header `applicationId == "com.example.app"`, `processName == "com.example.app:remote"`, `logLevel == "INFO"`; second header `applicationId == processName == "pid-77"`; timestamp `seconds`/`nanos` equal `epochOf(...)` epochSecond and `millis*1_000_000`.
  - `studio json keeps odd text` — message `he said "hi" \\ tab\there 🎉` and an empty message round-trip exactly through `Json.parseToJsonElement`.
  - `studio json folds trace into message` — entry with `trace = listOf("\tat a.B.c(B.kt:1)", "Caused by: x")` → message `"boom\n\tat a.B.c(B.kt:1)\nCaused by: x"`.
  - `assert level` — `LogLevel.Assert` → `"ASSERT"`.
  - `threadtime lines` — entry `11-16 06:46:01.112`, pid 4211, tid 4238, Warn, tag `Img`, message `miss`, trace `["  at x"]` → exactly `"11-16 06:46:01.112  4211  4238 W Img     : miss\n11-16 06:46:01.112  4211  4238 W Img     :   at x\n"`.
  - `threadtime round trips through parser` — every written line (without trace) parses back with `parseThreadtime` to the same `LogcatLine`.
  - `progress reports every 500 and the total` — 1,200 rows → progress calls `[500, 1000, 1200]` for both writers.
- [ ] **Step 2:** Run `--tests 'com.programmersbox.scrcpyviewer.logcat.LogcatExportWriterTest'` — FAIL.
- [ ] **Step 3:** Implement the Produces list.
- [ ] **Step 4:** Run the same test — PASS.
- [ ] **Step 5:** Commit `feat(logcat): Studio .logcat and threadtime writers`.

### Task 3: Controller export state machine

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/logcat/LogcatController.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/logcat/LogcatExportControllerTest.kt`

**Interfaces:**
- Consumes: Task 1 and 2 Produces; `LogcatSource.exportDevice()`.
- Produces (in `LogcatController.kt`):
  - `sealed interface LogcatExport { data object Idle; data class Running(val format: ExportFormat, val done: Int, val total: Int); data class Done(val path: Path, val lines: Int); data object Canceled }` — top-level, `LogcatExport.Idle` etc.
  - `var export: LogcatExport` (snapshot state, private set), starts `Idle`.
  - `fun export(format: ExportFormat, pick: suspend (suggestedName: String, extension: String) -> Path?)` — no-op unless `export is Idle` and `totalLines > 0`. On `scope`: snapshot rows (`ExportRow(line.entry, line.process)` for each `LogcatItem.Line`), `device = source?.exportDevice() ?: parseExportDevice("", "", now)`, `name = exportFileName(device, LocalDateTime.now(), ext)`, `target = pick(name, ext) ?: return` (stay Idle), set `Running(format, 0, rows.size)`, write on `io` into `target.resolveSibling(target.fileName.toString() + ".part")` with a `BufferedWriter` (UTF-8), progress updates posted to `Running.done` on `scope`, then atomic move (Global Constraints). Keep `Running` visible at least `EXPORT_MIN_RUN_MILLIS = 400` (`delay` the remainder). Then `Done(target, rows.size)`, and after `EXPORT_DONE_MILLIS = 3_600` → `Idle`.
  - `fun cancelExport()` — only while `Running`: cancel the job, delete `.part`, set `Canceled`, after `EXPORT_CANCELED_MILLIS = 2_800` → `Idle`.
  - Write failure (non-cancellation exception): delete `.part`, `toast("Could not export: ${e.message ?: e}")`, set `Idle`.
  - Constructor gains `private val now: () -> LocalDateTime = LocalDateTime::now` for the file name.

- [ ] **Step 1: Write failing tests** in `LogcatExportControllerTest` (virtual time with `StandardTestDispatcher`, `io` = the same dispatcher, a fake `LogcatSource` whose `exportDevice()` returns a fixed `ExportDevice`, `@TempDir`/`createTempDirectory` target):
  - `exports every line regardless of filter` — 3 lines, `query = "level:error"` (matches 1), export Studio → file has 3 `logcatMessages`, `metadata.filter == "level:error"`; state goes `Running` → `Done(target, 3)` → after 3,600 ms `Idle`.
  - `text export writes threadtime` — file text equals `writeThreadtime` output for the same rows.
  - `suggested name passed to picker` — picker receives `samsung-SM-A156U1-Android-16_2026-11-16_064600.logcat` and `"logcat"`.
  - `picker cancel stays idle` — `pick` returns null → state `Idle`, no toast, no files in the folder.
  - `lines arriving during export are not included` — the fake picker appends a line to the stream before returning; file still has the pre-pick count.
  - `cancel removes partial file and keeps existing target` — target pre-filled with `old`; a writer slowed by a gate (fake rows ≥ 5,000 and cancel after first progress); `cancelExport()` → state `Canceled`, target text still `old`, no `.part`, after 2,800 ms `Idle`.
  - `write failure toasts and cleans up` — target in a folder made read-only (`toFile().setWritable(false)`; skip with `assumeTrue` if the OS still allows writing) → toast starts with `Could not export: `, state `Idle`, no `.part`.
  - `no lines does nothing` — `export(...)` with empty list never calls `pick`.
  - `running shows at least 400 ms` — tiny export: still `Running` at 399 ms virtual, `Done` at 400 ms.
- [ ] **Step 2:** Run `--tests 'com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatExportControllerTest'` — FAIL.
- [ ] **Step 3:** Implement the Produces list.
- [ ] **Step 4:** Run the same test, then `--tests 'com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatControllerTest'` — both PASS.
- [ ] **Step 5:** Commit `feat(logcat): export state machine with cancel`.

### Task 4: Export UI

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/logcat/LogcatExportUi.kt`
- Modify: `LogcatTab.kt` (button between `PlayPauseButton` and `PopOutButton`; toast overlay), `LogcatList.kt` (bar + dim), `LogcatStatusBar.kt` (count text swap)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/logcat/LogcatExportUiTest.kt`

**Interfaces:**
- Consumes: `LogcatController.export`, `export(format, pick)`, `cancelExport()`, `totalLines`, `query`; `ExportFormat`; `nativeFileDialogs`, `onPickerThread`; `revealInFolder(path)`; `revealLabel` (move it from `ui/devices/emulators/EmulatorSection.kt` to `ui/common/OpenExternally.kt`, keep callers compiling).
- Produces:
  - `@Composable internal fun LogcatExportButton(controller: LogcatController, motion: Boolean, pick: suspend (String, String) -> Path?)` — 36 dp, `ButtonShape`, same hover/tooltip pattern as `PopOutButton`.
  - `@Composable internal fun LogcatExportBar(controller: LogcatController, motion: Boolean, modifier: Modifier)` — 2 dp primary bar.
  - `@Composable internal fun LogcatExportToast(controller: LogcatController, motion: Boolean, modifier: Modifier)`.
  - `internal fun exportStatusText(state: LogcatExport): String?` — `Running` → `Exporting 4,120 of 12,340 lines…`; `Done` → `Saved <file name>`; `Canceled` → `Export canceled · partial file removed`; `Idle` → `null`.
  - `LogcatTab` gains param `pick: suspend (String, String) -> Path? = defaultExportPick` where `defaultExportPick` = `{ name, ext -> onPickerThread { nativeFileDialogs().save("Export logs", name, ext) } }`; `LogcatWindow` passes the same default.

Behavior and motion (from the mockup; durations exact):
- Button icons stacked in one `Box`: download (`Icons.Filled.Download`), ring, check (`Icons.Filled.Check`, tint `LogcatColors.level(LogLevel.Info).first`). Idle → download visible. Running → download slides down 8 dp + scales 0.7 + fades out (280 ms, `EmphasizedDecelerate`), ring scales in from 0.4. Done → ring scales out, check pops `0.3 → 1.18 → 1` (420 ms keyframes). Canceled → ring scales out, download back.
- Ring: `CircularProgressIndicator(progress = { animated })`, 22 dp, stroke 2.5 dp, progress animated with `tween(80, LinearEasing)` toward `done/total`; whole ring rotates continuously (1,400 ms per turn, `infiniteRepeatable`) while Running.
- Cancel: while Running the button's `contentDescription` and tooltip are `Cancel export`; on hover or focus the ring fades to alpha 0.22 and scales 1.15, and a `Icons.Filled.Close` 16 dp fades in from scale 0.5 / rotation −45° (180–260 ms). Click → `cancelExport()`.
- Container: tonal (`secondaryContainer`) while the menu is open or state ≠ Idle; color fades 150 ms.
- Disabled (`alpha 0.38`, no click) when `totalLines == 0`; tooltip `No lines to export`. Otherwise tooltip `Export logs`.
- Menu: `DropdownMenu` anchored to the button, end-aligned. Header row: `Export 12,340 lines` (SemiBold, onSurface) and `all in memory` (onSurfaceVariant). Items:
  - `Android Studio (.logcat)` / `Opens with Logcat › Import Logs from File` / `Saves the filter; Studio applies it on import` / the filter text in a monospace chip (`primary` text on `surfaceContainerHigh`, 4 dp corners); when the filter is blank the chip reads `No filter`.
  - `Plain text (.txt)` / `threadtime lines, same as adb logcat` / `No filter saved`.
  - Items fade + slide 4 dp in with 30 / 70 / 110 ms stagger after the menu opens.
- List: `LogcatExportBar` at the list's top edge, width = progress, fades out 300 ms when not Running; list content alpha animates to 0.55 while Running (300 ms).
- Status bar: the `N of M lines` text becomes `AnimatedContent` keyed on `exportStatusText(state) ?: <existing counts>`, vertical shared axis (new slides in 10 dp from below, old out 10 dp up; 200 ms fade, 260 ms slide).
- Toast: bottom-center of the panel, 44 dp above the bottom, `inverseSurface`; Done → `Saved 12,340 lines to <file name>` + text button `revealLabel(platform)` calling `revealInFolder(path)`; Canceled → `Export canceled`. Enter: slide up 24 dp + fade (340 ms `EmphasizedDecelerate`); exit: 220 ms accelerate.

- [ ] **Step 1: Write failing tests** in `LogcatExportUiTest` (follow `LogcatToolbarTest`'s `ImageComposeScene`/`SceneDriver` setup, `motion = false`):
  - `export button sits between play and pop out` — nodes with descriptions `Export logs` x-position between the Play button and `Open in new window`.
  - `disabled with no lines` — no lines → `Export logs` node has `Disabled` and tooltip text `No lines to export` on hover.
  - `menu shows count and filter` — 3 lines, `query = "level:info"` → click shows `Export 3 lines`, `Android Studio (.logcat)`, `level:info`, `Plain text (.txt)`, `No filter saved`.
  - `picking calls pick with the suggested name` — fake pick records `(name, ext)`; click `Plain text (.txt)` → recorded ext `txt`.
  - `running shows cancel and clicking cancels` — pick returns a temp path, a slow source; while Running a node with description `Cancel export` exists; clicking it → `controller.export == LogcatExport.Canceled`.
  - `status text` — `exportStatusText(Running(Studio, 4120, 12340)) == "Exporting 4,120 of 12,340 lines…"`, `Done(Path.of("/x/a.logcat"), 3) == "Saved a.logcat"`, Canceled and Idle per Produces.
  - `done toast offers reveal` — Done state shows `Saved 3 lines to a.logcat` and a `Show in Finder`/`Show in folder` button (`revealLabel` of the test platform).
- [ ] **Step 2:** Run `--tests 'com.programmersbox.scrcpyviewer.ui.mirror.logcat.LogcatExportUiTest'` — FAIL.
- [ ] **Step 3:** Implement the Produces list and wire it into `LogcatTab`, `LogcatList`, `LogcatStatusBar`, `LogcatWindow`.
- [ ] **Step 4:** Run `LogcatExportUiTest`, `LogcatToolbarTest`, `LogcatWindowTest`, `LogcatListTest` — PASS. Then full `./gradlew :composeApp:jvmTest` — PASS.
- [ ] **Step 5:** Commit `feat(logcat): export button, progress ring, cancel and toast`.

### Task 5: Real-device check

**Files:** none (verification only; fix anything found in the owning task's files).

- [ ] **Step 1:** Run the desktop app (`./gradlew :composeApp:run`) with the Samsung `R5CX12GDWPZ` connected (no `cdm` hold needed for a desktop run). Open a mirror window, Logcat tab, Play, wait for lines, set filter `level:warn`.
- [ ] **Step 2:** Export `.logcat` to `~/Desktop`. Compare its keys to the Studio sample: `python3 -I -c "import json,sys;a=json.load(open(sys.argv[1]));b=json.load(open(sys.argv[2]));print(a['metadata'].keys()==b['metadata'].keys(), a['metadata']['device'].keys()==b['metadata']['device'].keys(), a['logcatMessages'][0]['header'].keys()==b['logcatMessages'][0]['header'].keys())" <ours> ~/Desktop/samsung-SM-A156U1-Android-16_2026-04-22_110600.logcat` — prints `True True True`. Check first/last timestamps against the device clock (`adb -s R5CX12GDWPZ shell date`).
- [ ] **Step 3:** Export `.txt`; `head` shows threadtime lines.
- [ ] **Step 4:** Start a large export and cancel it; folder has no `.part` file.
- [ ] **Step 5:** Ask the user to import the `.logcat` in Android Studio (Logcat › Import Logs from File; Studio isn't installed on this machine) and confirm the filter shows.
