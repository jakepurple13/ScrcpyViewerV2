# Files Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Files tab to the mirror window's quick actions sheet: browse `/sdcard`, pull, push, move, rename, delete, new folder, with drag and drop in and out of the window.

**Architecture:** Pure, tested helpers in `files/` (path quoting, listing commands and parsers, device operations over `ShellRunner`), a grown `FileTransferQueue` (pull, per-folder push, per-job progress and cancel), a `FilesBrowser` state holder per mirror window (no Compose inside, unit tested), and Compose UI under `ui/mirror/files/` plugged into the existing sheet as `SheetTab.Files`.

**Tech Stack:** Kotlin/JVM, Compose Multiplatform desktop on the Nucleus Tao backend (2.5.18), kotlinx.coroutines, kotlin.test, Compose UI tests (`runComposeUiTest`), adb.

**Spec:** `docs/superpowers/specs/2026-11-12-device-files-tab-design.md` (mockup: https://claude.ai/artifact/XQFtYPCQrNg9To2FQWs9oE). Read both before any task.

## Global Constraints

- Files tab default and minimum width: **440 dp**; saved as `filesWidth` (Int dp) in `AppSettings`, default `440`.
- Sort and hidden files saved in `AppSettings`: `filesSort: String = "name"` (`name` | `size` | `mtime`), `filesSortDescending: Boolean = false`, `filesShowHidden: Boolean = false`. Apply to every device.
- Root folder is `/sdcard`, shown as "Internal storage". First open per device per app run: the session's push target (`pushTarget(session.values)`, default `/sdcard/Download/`). Per-device folder and history live in `QuickActionsMemory` (app lifetime, not persisted).
- Motion tokens: open `OPEN_MILLIS` (260) with `EmphasizedDecelerate`, close `CLOSE_MILLIS` (200) with `EmphasizedAccelerate`; every other timing is in the spec's Motion table. Honor the app's existing reduced-motion handling.
- Every device path that reaches a shell goes through `shellQuote` (Task 3). No string-built shell command may interpolate a raw name.
- Drops from the computer are copies only (`copyDropAction`); never MOVE.
- Copy is exact as written in the spec (titles, button labels, empty/blocked state text, chip text, tray text).
- Styles: all surfaces, menus, dialogs and chips use the sheet's styled controls and color scheme, so Glass, Soft, Retro and every palette work.
- No `java.awt.Desktop` or AWT `DragSource`: the Tao backend has no AWT toolkit. Open files with `LocalUriHandler.openUri("file://…")` via a small wrapper like `openSafely`; drag out via the Tao outbound drag (Task 0).
- Test command: `./gradlew :composeApp:jvmTest --tests '<fully.qualified.TestClass>'`; full suite `./gradlew :composeApp:jvmTest` must stay green after every task.
- Device work only after `cdm acquire --android --device R5CX12GDWPZ`; `cdm release` when done.

## Review Focus

- **Hostile file names** (spaces, `'`, `"`, `$`, backticks, `;`, newlines, emoji, CJK): list, rename, move, delete, pull and push all act on exactly that file. Tests in Tasks 3, 4, 5.
- **Device disconnects mid-listing or mid-transfer:** listing shows "Device disconnected", running transfer fails with a message, queued ones are cancelled, nothing hangs. Tests in Tasks 6, 7.
- **Big folders** (5,000 entries in `DCIM/Camera`): one shell call lists them (no per-file process), parse stays under 200 ms, the list scrolls lazily. Tests in Tasks 4, 8.
- **Pulling a name that already exists on the computer:** never overwrite; the copy gets " (1)", " (2)" before the extension. Test in Task 6.
- **Moving a folder into itself or a child; dropping onto a blocked folder:** left out / refused, no shell command runs. Tests in Tasks 7, 11.

---

### Task 0: Spike — drag files out under Tao

No production code. Prove how an outbound drag carrying files works under `decorated-window-tao` 2.5.18 and write down the answer; Task 12 depends on it.

**Files:**
- Create: `docs/superpowers/notes/2026-11-12-tao-drag-out-spike.md`
- Scratch (do not commit): a throwaway `main` in `composeApp/src/jvmTest/kotlin/.../spike/DragOutSpike.kt` or a scratch branch.

**Interfaces:**
- Produces: the note answers these questions, each with yes/no and the code shape that worked:
  1. Which public API starts an outbound drag with `List<File>` (`Modifier.dragAndDropSource` → `TaoDragAndDropManager.requestDragAndDropTransfer` → `OutboundRequest(files, …)`)?
  2. Can the drag start from a gesture that began as an in-app pointer drag, after a delay (files ready later), while the button is still held?
  3. Can it start once the pointer is already outside the window?
  4. Does Finder (and, if a machine is available, Explorer / Nautilus) treat the drop as a copy and leave the source file in place?
  5. Is a custom drag image (`drawDragDecoration`) shown?

- [ ] **Step 1:** In a scratch window, add a box that, on drag, writes a 50 MB temp file after a 2 s delay and then starts an outbound drag with it. Run it on macOS. Try releasing inside the window, outside it, and before the 2 s.
- [ ] **Step 2:** Record the five answers and the working code shape in the note. Include the fallback decision from the spec ("start when ready, wherever the pointer is") if question 3 is no.
- [ ] **Step 3:** Commit the note only.

```bash
git add docs/superpowers/notes/2026-11-12-tao-drag-out-spike.md
git commit -m "docs: Tao drag-out spike results"
```

Report the answers to the user before Task 12 starts.

---

### Task 1: Files tab in the sheet model (tab, width, settings, memory)

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/QuickActions.kt` (`SheetTab`, `QuickActionsMemory`, `rememberQuickActions` width load/save)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/SheetWidths.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/QuickActionsSheet.kt` (`QUICK_ACTIONS_FILES_WIDTH`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/state/AppSettings.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/SheetWidthTest.kt`, `SheetGrowthTest.kt`, `.../state/AppSettingsTest.kt`

**Interfaces:**
- Produces:
  - `enum class SheetTab { Actions, Shell, Files }` (order is the tab order).
  - `val QUICK_ACTIONS_FILES_WIDTH = 440.dp`.
  - `data class SheetWidths(actions, shell, files: Dp = QUICK_ACTIONS_FILES_WIDTH)`; `get`, `with`, `default` cover `Files`; `fromSettings(actions: Int, shell: Int, files: Int)`.
  - `AppSettings.filesWidth: Int = 440`, `filesSort: String = "name"`, `filesSortDescending: Boolean = false`, `filesShowHidden: Boolean = false`.
  - `QuickActionsMemory.filesLocation(serial: String): FilesLocation?` and `setFilesLocation(serial: String, location: FilesLocation)`, with `data class FilesLocation(val path: String, val history: List<String>)` in `ui/mirror/files/FilesLocation.kt`.

- [ ] **Step 1: Write failing tests**

```kotlin
// SheetWidthTest
@Test fun `files tab width`() {
    assertEquals(440.dp, sheetWidth(true, SheetTab.Files))
    assertEquals(SheetWidths(files = 600.dp), SheetWidths().with(SheetTab.Files, 600.dp))
    assertEquals(440.dp, clampSheetWidth(300.dp, SheetTab.Files, 1200.dp, 64.dp))
    assertEquals(SheetWidths(files = 440.dp), SheetWidths.fromSettings(280, 400, 100))
    assertEquals(SheetWidths(files = SHEET_MAX_WIDTH), SheetWidths.fromSettings(280, 400, 99_999))
}
// SheetGrowthTest
@Test fun `files tab grows the window by at least 440 dp`() {
    assertEquals(SheetSplit(440.dp, 0.dp), sheetSplit(440.dp, SheetTab.Files, 400.dp, SheetSplit(), room = 100.dp))
}
// AppSettingsTest
@Test fun `files settings default and round trip`() {
    val s = AppSettings.decode("{}") // use the test file's existing decode helper
    assertEquals(440, s.filesWidth); assertEquals("name", s.filesSort)
    assertFalse(s.filesSortDescending); assertFalse(s.filesShowHidden)
}
```

Also extend the existing `ResizeDone` test in `SheetWidthTest` with `SelectTab(SheetTab.Files)` → `Resize` → `ResizeDone(SheetTab.Files)` saving `SheetTab.Files to <dp>`.

- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*SheetWidthTest' --tests '*SheetGrowthTest' --tests '*AppSettingsTest'`. Expected: FAIL (no `SheetTab.Files`).
- [ ] **Step 3:** Implement. In `rememberQuickActions`, load `fromSettings(it.quickActionsWidth, it.shellWidth, it.filesWidth)` and save with a `when (tab)` into the matching field. Fix every `when (tab)` / `if (tab == SheetTab.Shell)` that the compiler or a read of `SheetWidths.get/with` exposes. The `QuickActionsSheet.kt` content `when` gets a `SheetTab.Files -> {}` placeholder until Task 8.
- [ ] **Step 4:** Run the same tests, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): Files sheet tab, width and settings`

---

### Task 2: Three-segment tab control, slide by tab order, Files badge

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/QuickActionsSheet.kt` (`slideTransform`, `SheetTabs`, header subtitle, `SheetContent`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/SheetMotionTest.kt`, `QuickActionsTest.kt` (or the UI test that covers `SheetTabs`)

**Interfaces:**
- Consumes: `SheetTab.Files` (Task 1).
- Produces:
  - `fun slideTransform(from: SheetTab, to: SheetTab, density: Density): ContentTransform` — replaces the `toShell: Boolean` version. Moving to a higher `ordinal` enters from the right with open timing; lower enters from the left with close timing.
  - `QuickActionsUiState.filesTransfersRunning: Int = 0` (badge count; filled in Task 10).
  - `SheetTabs(tab, consoleRunning, filesRunning: Int, onSelect, modifier)`.

- [ ] **Step 1: Write failing tests**
  - `slideTransform(Actions, Files)` and `(Shell, Files)` enter from +offset; `(Files, Shell)` and `(Files, Actions)` from −offset (assert via the transform's `targetContentEnter` initial offset as the existing slide test does).
  - Compose test: the tab row has three `Role.Tab` nodes named "Actions", "Shell", "Files"; clicking "Files" sends `SelectTab(SheetTab.Files)`; with `filesTransfersRunning = 2` a node with contentDescription "2 transfers running" exists; header text "Browse and move files on this device" shows on Files and "Runs adb shell on this device" on the other two.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement. Highlight is a third of the track, positioned by `animateFloatAsState(tab.ordinal.toFloat())` with `growSpec(to > from)`; badge is a 16 dp pill in `colors.primary`/`onPrimary` that scales in/out (`AnimatedVisibility` with `scaleIn`/`scaleOut`, open/close timing).
- [ ] **Step 4:** Run tests and the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): three-tab sheet control with Files badge`

---

### Task 3: Device paths and shell quoting

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/files/DevicePath.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/files/DevicePathTest.kt`

**Interfaces:**
- Produces:
  - `const val DEVICE_ROOT = "/sdcard"`
  - `fun shellQuote(s: String): String` — POSIX single quotes; `'` becomes `'\''`.
  - `fun devicePath(dir: String, name: String): String` (no doubled `/`).
  - `fun parentOf(path: String): String?` (null at `DEVICE_ROOT`).
  - `fun crumbs(path: String): List<Pair<String, String>>` — (label, path) from `"Internal storage" to "/sdcard"` down.
  - `fun normalizeDir(path: String): String` — trims trailing `/`, maps `/storage/emulated/0` and `/sdcard/` to `/sdcard`.
  - `fun uniqueName(taken: Set<String>, name: String): String` — `"a.txt"` → `"a (1).txt"`, folders/no extension → `"New folder (1)"`; dot-files (`.nomedia`) treat the whole name as base.
  - `fun nameProblem(name: String, taken: Set<String>): String?` — `"No “/” in names"`, `"Name already used"`, or null; blank → `"Name can’t be empty"`.
  - `fun isInside(path: String, ancestor: String): Boolean` — true for equal or descendant.

- [ ] **Step 1: Write failing tests** — table tests: `shellQuote("it's")` == `'it'\''s'`; names with `$HOME`, backtick, `;rm -rf /`, newline, `😀.png`, `写真.jpg` round-trip through `sh -c "printf %s <quoted>"` on the host (skip on Windows) to the original bytes; `crumbs("/sdcard/DCIM/Camera")` == `[("Internal storage","/sdcard"),("DCIM","/sdcard/DCIM"),("Camera","/sdcard/DCIM/Camera")]`; `normalizeDir("/sdcard/Download/")` == `/sdcard/Download`; `uniqueName(setOf("a.txt","a (1).txt"), "a.txt")` == `a (2).txt`; `uniqueName(setOf("New folder"), "New folder")` == `New folder (1)`; `uniqueName(setOf(".nomedia"), ".nomedia")` == `.nomedia (1)`; `nameProblem("a/b", …)`; `isInside("/sdcard/A/B", "/sdcard/A")` true, `isInside("/sdcard/AB", "/sdcard/A")` false.
- [ ] **Step 2:** Run `--tests '*DevicePathTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): device paths and shell quoting`

---

### Task 4: Listing, counts and free-space commands and parsers; kinds and formatting

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/files/DeviceListing.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/files/FileKind.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/files/DeviceListingTest.kt`, `FileKindTest.kt`
- Fixtures: `composeApp/src/jvmTest/resources/listing/` (`android8.txt`, `android11.txt`, `android15.txt`, `blocked.txt`, `big-5000.txt` generated in the test instead of stored)

**Interfaces:**
- Consumes: `shellQuote`, `devicePath` (Task 3).
- Produces:
  - `data class DeviceEntry(val name: String, val isDir: Boolean, val size: Long, val mtimeMillis: Long, val itemCount: Int? = null) { val hidden get() = name.startsWith(".") }`
  - `sealed interface Listing { data class Entries(val entries: List<DeviceEntry>); data object Blocked; data class Failed(val reason: String) }`
  - `fun listCommand(dir: String): String`, `fun parseListing(output: String, exitCode: Int?): Listing`
  - `fun countsCommand(dir: String, folders: List<String>): String`, `fun parseCounts(output: String): Map<String, Int>`
  - `fun freeSpaceCommand(): String`, `fun parseFreeSpace(output: String): Long?` (bytes available)
  - `enum class FileKind { Folder, Image, Video, Apk, Audio, Document }`, `fun kindOf(entry: DeviceEntry): FileKind`
  - `fun formatSize(bytes: Long): String` (`"812 KB"`, `"4.1 MB"`, `"2.05 GB"`, `"2,148 B"` → `"2.1 KB"`, match the mockup's rules), `fun formatModified(millis: Long, zone: ZoneId, locale: Locale): String` → `"Nov 8, 2:32 PM"`, `fun formatItemCount(n: Int): String` → `"1 item"` / `"12 items"`, `fun formatFree(bytes: Long): String` → `"41.2 GB free"`.

Command shape (decided here; one shell process per listing):

```sh
d=<quoted dir>; ls -A "$d" >/dev/null || exit 13
for f in "$d"/.[!.]* "$d"/..?* "$d"/*; do [ -e "$f" ] || [ -L "$f" ] || continue
  stat -L -c '%F|%s|%Y|%n|__svend__' "$f" 2>/dev/null || echo "broken|0|0|$f|__svend__"; done
```

Parse by splitting on `"|__svend__\n"` (so names with newlines survive), then the first three `|` fields; the name is the remainder with the `"$d/"` prefix removed. Exit 13 with output containing `Permission denied` or `Operation not permitted` → `Blocked`; any other non-zero → `Failed(last non-blank line)`. Lines without the marker (stat errors) are ignored. `%F` values: `directory` → dir; everything else → file.

Counts: one loop printing `<count>|<name>|__svend__` with `ls -A "$f" 2>/dev/null | wc -l`. Free space: `df -k /sdcard` → 4th column of the last line × 1024.

- [ ] **Step 1: Write failing tests**
  - Each fixture parses to the expected entries (write fixtures by hand in the format above, one per Android flavor: `%F` reads `regular file` vs `regular empty file`).
  - A name containing `\n`, `|`, and `__svend__` without the trailing `|` parses intact.
  - `parseListing("ls: /sdcard/Android/data: Permission denied", 13)` is `Blocked`.
  - `parseListing("ls: x: No such file or directory", 13)` is `Failed("ls: x: No such file or directory")`.
  - 5,000 generated records parse in under 200 ms (`measureTimeMillis`).
  - `listCommand("/sdcard/it's")` contains `'/sdcard/it'\''s'` and nothing unquoted.
  - `parseCounts`, `parseFreeSpace` on real `df -k` output from Android 8 and 15.
  - `kindOf`: `jpg jpeg png gif webp heic` image; `mp4 mkv mov webm 3gp` video; `apk` apk; `mp3 m4a wav ogg flac opus` audio; else document; case-insensitive.
  - Formatting examples from the mockup: `4_312_448` → `"4.1 MB"`, `214_337` → `"209 KB"`, `2_148` → `"2.1 KB"`, `12_998_656` → `"12.4 MB"`; `formatModified` for 2026-11-08T14:32 in `America/Denver`, `Locale.US` → `"Nov 8, 2:32 PM"`.
- [ ] **Step 2:** Run `--tests '*DeviceListingTest' --tests '*FileKindTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): device listing commands and parsers`

---

### Task 5: Device file operations over the shell

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/files/DeviceFiles.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/files/DeviceFilesTest.kt`
- Device test (opt-in, like `FileTransferDeviceTest`): `DeviceFilesDeviceTest.kt`

**Interfaces:**
- Consumes: `ShellRunner.run(cmd, timeoutMs): ShellResult` (existing), Task 3 and Task 4 functions.
- Produces: `open class DeviceFiles(private val runner: ShellRunner, private val scanFile: (String) -> Unit)` with
  - `suspend fun list(dir: String): Listing`
  - `suspend fun counts(dir: String, folders: List<String>): Map<String, Int>`
  - `suspend fun freeSpace(): Long?`
  - `suspend fun mkdir(dir: String, name: String)` → `mkdir <quoted>`
  - `suspend fun rename(dir: String, from: String, to: String)` → `mv -n` … (fails if target exists)
  - `suspend fun move(dir: String, names: List<String>, toDir: String, renames: Map<String, String> = emptyMap())` → one `mv` per item to `devicePath(toDir, renames[name] ?: name)`, joined with `&&`
  - `suspend fun delete(dir: String, names: List<String>)` → `rm -rf -- <quoted>…`
  - Every mutating call throws `IOException(last non-blank output line)` on non-zero or null exit code and calls `scanFile` for each affected folder on success.

- [ ] **Step 1: Write failing tests** with a fake `ShellRunner` that records commands and returns scripted `ShellResult`s: each method's exact command for a name with a `'` and a space; `delete` of `-rf` named file is safe (`--`); non-zero exit throws with the device's line (`"mv: bad: Permission denied"`); `scanFile` called with the source and target folders after `move`, once for `mkdir`/`rename`/`delete`; `list` maps a 13 + `Permission denied` result to `Listing.Blocked`.
- [ ] **Step 2:** Run `--tests '*DeviceFilesTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Use a 30 s timeout for `list`/`counts` (default `BUFFERED_TIMEOUT_MS`), 120 s for `delete` and `move`.
- [ ] **Step 4:** Run. Expected: PASS. Then, with the device acquired, run the opt-in device test that creates `/sdcard/Download/svw-test 'q' $x/`, lists, renames, moves and deletes it.
- [ ] **Step 5: Commit** `feat(files): device file operations`

---

### Task 6: Transfer queue — jobs, per-folder push, pull, device install, progress, cancel

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/files/FileTransfer.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/FileDrop.kt` (`FileTransfers`, `rememberFileTransfers`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/files/FileTransferTest.kt`, `.../ui/mirror/FileDropTest.kt`

**Interfaces:**
- Consumes: `AdbClient.startProcess(vararg args): Process` (existing), `devicePath`, `uniqueName`.
- Produces:
  - `enum class TransferKind { Install, Push, Pull, InstallFromDevice }`
  - `data class TransferRequest(val path: Path, val kind: TransferKind, val deviceDir: String? = null, val deviceName: String? = null, val opensAfter: Boolean = false)` — `path` is the host file (source for Push/Install, destination folder for Pull); `deviceDir` the push target folder or pull source folder; existing callers keep `deviceDir = null` (= session push target).
  - `data class TransferJob(val id: Long, val request: TransferRequest, val state: JobState, val bytes: Long, val total: Long?, val bytesPerSecond: Long?, val hostResult: Path? = null)`, `sealed interface JobState { Queued; Running; Done; data class Failed(val reason: String); Cancelled }`
  - `FileTransferQueue.jobs: StateFlow<List<TransferJob>>` (finished jobs stay until `dismiss(id)`), `fun cancel(id: Long)`, `fun dismiss(id: Long)`, `fun enqueue(requests): List<Long>` (ids; empty when refused).
  - `fun hostUniquePath(dir: Path, name: String): Path` (pull never overwrites; " (1)" before the extension).
  - `TransferResult.Pulled(name, dir)` with message `"Pulled $name to ${dir.fileName}"`; `Failed` wording for pull: `"Could not pull "`.
  - `FileTransfers.jobs` (Compose state mirror of `queue.jobs`), `FileTransfers.runningCount: Int`.

Runs use `startProcess` so `cancel` can `destroyForcibly()` the running adb, then delete the partial host file (pull) or `rm -f` the partial device file (push, through the shell). Progress: pull polls the growing host file (or folder, summed) every 250 ms against `total` from the listing; push polls `stat -c %s <target>` every 500 ms; folders and installs report `total = null` (indeterminate). `InstallFromDevice` pulls to `<tmp>/scrcpy-vwr/<serial>/install/` then runs the existing install args, then deletes the temp file. `opensAfter` jobs pull to `<tmp>/scrcpy-vwr/<serial>/open/` and set `hostResult`.

- [ ] **Step 1: Write failing tests** (extend `FakeAdb` with a fake `startProcess` returning a controllable `Process`):
  - Push with `deviceDir = "/sdcard/DCIM/Camera"` runs `push <host> /sdcard/DCIM/Camera/<name>`; without it, the session push target as today (existing tests stay green).
  - Pull runs `pull /sdcard/Download/a.txt <dir>/a.txt`; when `a.txt` exists in `<dir>`, it targets `a (1).txt`.
  - `cancel(id)` on the running job destroys the process, state becomes `Cancelled`, the partial host file is gone, and the next queued job starts; `cancel` on a queued job removes it without running adb.
  - Device disconnect (`canTransfer` false) mid-queue: running job `Failed`, queued jobs `Cancelled`, one `TransferResult.Cancelled(n)`.
  - Progress: a fake pull that writes 50% of `total` then waits reports `bytes == total/2`.
  - `InstallFromDevice` runs pull then `install -r`, temp file deleted after.
  - `hostUniquePath` table test.
- [ ] **Step 2:** Run `--tests '*FileTransferTest' --tests '*FileDropTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): transfer jobs with pull, per-folder push, progress and cancel`

---

### Task 7: `FilesBrowser` state holder

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesBrowser.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/Selection.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesLocation.kt` (from Task 1, if not already)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesBrowserTest.kt`, `SelectionTest.kt`

**Interfaces:**
- Consumes: `DeviceFiles` (Task 5), `FileTransfers` (Task 6), `QuickActionsMemory.filesLocation` (Task 1), `AppSettings` sort/hidden fields, Task 3 helpers.
- Produces:
  - `data class Selection(val names: Set<String> = emptySet(), val anchor: String? = null, val cursor: String? = null)` with pure functions `click(order: List<String>, name)`, `shiftClick(order, name)`, `toggle(name)`, `all(order)`, `move(order, delta: Int, extend: Boolean)`, `clear()`, `retain(order)`.
  - `enum class SortKey { Name, Size, Modified }`; `fun sortEntries(entries: List<DeviceEntry>, key: SortKey, descending: Boolean): List<DeviceEntry>` (folders first, then key, then name).
  - `@Stable class FilesBrowser(serial, files: DeviceFiles, transfers: FileTransfers, memory: QuickActionsMemory, settings: AppSettings, startDir: String, scope: CoroutineScope, notify: (Snack) -> Unit)` with Compose-observable state:
    - `path: String`, `canGoBack`, `canGoUp`, `listing: Listing?`, `loading: Boolean`, `visible: List<DeviceEntry>` (filtered, hidden-aware, sorted), `selection: Selection`, `filter: String`, `sort: SortKey`, `descending: Boolean`, `showHidden: Boolean`, `free: Long?`, `connected: Boolean`, `arriving: Set<String>`, `leaving: Set<String>`, `renaming: String?`, `renameError: String?`, `pendingNewFolder: String?`, `dialog: FilesDialog?`, `navDirection: Int` (+1 forward, −1 back, 0 refresh).
    - `fun open(dir: String)`, `fun up()`, `fun back()`, `fun refresh()`, `fun openEntry(name: String)` (folder → open; apk → install from device; file → pull and open), `fun setFilter(text)`, `fun setSort(key)` (same key reverses; size and modified start descending), `fun toggleHidden()`, selection methods forwarding to `Selection`, `fun startRename(name)`, `fun commitRename(text)`, `fun cancelRename()`, `fun newFolder()`, `fun askDelete()`, `fun confirmDelete()`, `fun move(names, toDir)`, `fun push(paths: List<Path>, toDir: String)`, `fun pull(names, toDir: Path)`, `fun dismissDialog()`.
  - `sealed interface FilesDialog { data class Delete(val names: List<String>, val hasFolder: Boolean); data class Clash(val toDir: String, val clashing: List<String>, val onChoice: (ClashChoice) -> Unit) }`, `enum class ClashChoice { Skip, KeepBoth, Replace }`.
  - `data class Snack(val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)`.

Behavior pinned by the spec (each line is a test): previous rows stay while loading; a refresh after every mutating op and after each push into the current folder finishes; `arriving` holds names added since the last listing for 1.4 s; delete/move put names in `leaving`, wait `CLOSE_MILLIS + 30 × (n−1)` ms, then drop them; new folder inserts `pendingNewFolder` with `uniqueName(…, "New folder")` and starts renaming it, `mkdir` only on commit, cancel removes it with no shell call; rename refuses per `nameProblem` and keeps the field open; move leaves out folders where `isInside(toDir, devicePath(path, name))` (no command if nothing is left); push/move with clashes opens one `Clash` dialog, non-clashing items go ahead regardless; Blocked folders refuse push (`push` returns false); disconnect sets `connected = false`, every op is a no-op, reconnect refreshes; location (path + back history) is saved to `memory` on every navigation; sort and hidden are written to `settings`.

- [ ] **Step 1: Write failing tests** — `SelectionTest` (click, shift range both directions, ⌘ toggle, all, arrow move and extend, `retain` after a refresh drops vanished names) and `FilesBrowserTest` covering each behavior line above with a fake `DeviceFiles` (scripted listings), a fake `FileTransfers` queue, and `runTest` virtual time for the delays. Messages: `"Deleted notes.txt"`, `"Deleted 2 items"`, `"Moved notes.txt to Documents"` with action `"Open"`.
- [ ] **Step 2:** Run `--tests '*FilesBrowserTest' --tests '*SelectionTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Item counts and free space load after the listing returns (second call), updating `visible` in place.
- [ ] **Step 4:** Run, then full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): FilesBrowser state and selection model`

---

### Task 8: Files tab layout, list, states and navigation motion

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesTab.kt` (toolbar, crumbs, filter row, column header, list host)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FileRow.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesStates.kt` (empty, no match, blocked, failed, disconnected banner)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/QuickActionsSheet.kt` (`SheetTab.Files -> FilesTab(...)`), `QuickActions.kt` / `MirrorWindow.kt` (create one `FilesBrowser` per window with `rememberFilesBrowser(serial, session, adb, transfers, notify)` and pass it to the panel)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesTabTest.kt`

**Interfaces:**
- Consumes: `FilesBrowser` (Task 7), `FileKind`, formatting (Task 4).
- Produces: `@Composable fun FilesTab(browser: FilesBrowser, modifier: Modifier = Modifier)`; `@Composable fun rememberFilesBrowser(...): FilesBrowser?` (null without adb).

Layout and copy exactly as the spec's "Files tab layout" and "States". Rows: 40 dp, columns `26 dp | 1f | 64 dp | 104 dp`, `LazyColumn` keyed by name. Motion (spec Motion table): `AnimatedContent(targetState = path)` with a shared-X transform of 32 dp by `navDirection` (old: 150 ms linear fade + slide; new: open timing, 50 ms delay); first 14 rows stagger in 16 ms apart rising 6 dp; `Modifier.animateItem(placementSpec = tween(300, easing = EmphasizedDecelerate))` for FLIP on sort/filter/rename; `arriving` rows grow 0 → 40 dp with fade then a primaryContainer highlight fading over 1.4 s; `leaving` rows shrink to 0, fade and slide 16 dp left with close timing and 30 ms stagger; new crumb slides 10 dp in; refresh icon spins 500 ms; counts fade in; indeterminate 2 dp `LinearProgressIndicator` while `loading`.

- [ ] **Step 1: Write failing Compose tests** with a scripted `FilesBrowser` (fake `DeviceFiles`): opens on `/sdcard/Download` with rows in folder-first name order; double-click "Telegram" shows crumbs "Internal storage › Download › Telegram" and Back enabled; clicking the "Internal storage" crumb opens `/sdcard`; empty folder shows "Documents is empty" and a "Push files" button; `Android/data` shows "Android blocks this folder" and "Go up"; "Nothing matches “zzz”" after typing in the filter; clicking "Size" header twice reverses; with 5,000 entries the tab composes and scrolls to the last row (lazy list).
- [ ] **Step 2:** Run `--tests '*FilesTabTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): Files tab list, states and navigation motion`

---

### Task 9: Selection bar, menus, rename field, dialogs, keyboard, snackbar

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/SelectionBar.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesMenus.kt` (item menu, empty-space menu, ⋮ view menu, Move to submenu)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesDialogs.kt` (Delete, Clash)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesKeys.kt` (`fun filesKeyAction(event: KeyEvent, isMac: Boolean): FilesKey?` pure mapping + handler)
- Modify: `FileRow.kt` (rename field), `FilesTab.kt` (snackbar host, Esc order)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesInteractionTest.kt`, `FilesKeysTest.kt`

**Interfaces:**
- Consumes: `FilesBrowser` (Task 7); `NativeFileDialogs.open(title, emptyList(), multiple = false, directories = true)` for "Pull to…"; existing `TransferFileDialogs.chooseFiles` for "Push files here…".
- Produces: `enum class FilesKey { Up, Down, ExtendUp, ExtendDown, Enter, Open, Parent, Rename, Delete, SelectAll, NewFolder, Refresh, Escape }`.

Menu items, their conditions and shortcuts exactly as the spec's "Item menu" table and empty-space list. Selection bar: inverse surface, slides up 70 dp with open timing, out with close timing; buttons Clear, "N selected", Pull (to Downloads), Rename (disabled unless one), Delete, ⋮. Rename field: name selected up to the extension (whole for folders); Enter/blur commit, Esc cancel; refusal shakes 4 dp for 180 ms and shows `renameError`. Delete dialog copy, list of up to four names then "and N more", focus on Delete, Esc/scrim cancels. Clash dialog copy and buttons Skip / Keep both / Replace. Esc order: menu → dialog → rename → filter → selection → sheet (existing `onKeyEvent` only sees unused Esc). Menus scale from 92% at the pointer (150 ms decelerate, out 110 ms); dialog scales from 92% and rises 8 dp; snackbar rises 16 dp.

- [ ] **Step 1: Write failing tests**
  - `FilesKeysTest`: every row of the spec's Keyboard table on macOS (⌘) and Windows/Linux (Ctrl).
  - `FilesInteractionTest`: right-click a file shows "Open on computer", "Pull to Downloads", "Pull to…", "Rename", "Move to…", "Copy path", "Delete"; right-click an `.apk` adds "Install on this device"; selecting two disables "Rename"; Delete on two items shows "Delete 2 items?" and the names; confirming runs `delete` once; F2 then typing an existing name shows "Name already used" and keeps the field; ⇧⌘N shows a "New folder" row in rename, Esc removes it with no `mkdir`; Esc with a selection clears it and the sheet stays open, a second Esc closes the sheet; "Moved … to Documents" snackbar's "Open" opens Documents.
- [ ] **Step 2:** Run `--tests '*FilesInteractionTest' --tests '*FilesKeysTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): selection bar, menus, rename, dialogs and keys`

---

### Task 10: Transfers tray, Files badge, open on computer

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/TransfersTray.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/OpenOnComputer.kt` (`fun UriHandler.openFileSafely(path: Path)`)
- Modify: `QuickActions.kt` (`filesTransfersRunning` from `FileTransfers.runningCount`), `FilesTab.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/TransfersTrayTest.kt`

**Interfaces:**
- Consumes: `FileTransfers.jobs`, `cancel`, `dismiss`, `runningCount` (Task 6); `QuickActionsUiState.filesTransfersRunning` (Task 2).

Tray copy and layout per the spec's "The tray": header "Transfers · N running" / "Transfers · done" / idle "Nothing transferring. Pulls, pushes and installs show here."; rows with direction icon, name, "· To Download", bar, "48.2 MB of 184.5 MB · 34.8 MB/s" (indeterminate bar when `total == null`), cancel; done turns the bar green, shows a check, and the row collapses out 2.5 s later via `dismiss`; failed rows stay with the reason until dismissed. Collapse/expand animates height with open timing and rotates the chevron. When an `opensAfter` job finishes, call `openFileSafely(hostResult)` and show "Opened <name> on your computer". Drops on the mirror image also appear here.

- [ ] **Step 1: Write failing tests**: tray shows a running push row with "of" progress text; a `total = null` job shows an indeterminate indicator; clicking cancel calls `cancel(id)`; a done job's row disappears after 2.5 s (virtual clock); a failed job stays; the Files tab badge reads the running count; an `opensAfter` job calls the injected opener with the temp path.
- [ ] **Step 2:** Run `--tests '*TransfersTrayTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): transfers tray and open on computer`

---

### Task 11: Drop targets — push into list, rows and crumbs; spring-open; in-tab move

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesDrop.kt`
- Modify: `FilesTab.kt`, `FileRow.kt`, `FileDrop.kt` (mirror drop target ignores drags while the pointer is over the sheet)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/FilesDropTest.kt`

**Interfaces:**
- Consumes: `dropPaths`, `copyDropAction` (existing, `FileDrop.kt`), `FilesBrowser.push`, `FilesBrowser.move`, `FilesBrowser.open`.
- Produces:
  - `sealed interface FilesDropTarget { data class Folder(val dir: String); data class Current(val dir: String) }`
  - `fun dropTargetFor(rowName: String?, rowIsDir: Boolean, current: String, dragged: Set<String>, blocked: Boolean, internal: Boolean): FilesDropTarget?` (pure: internal drags need a folder row not in `dragged`; blocked → null; file rows and empty space → `Current` for external drags).
  - `const val SPRING_OPEN_MILLIS = 800`.

Visuals per spec: target row primaryContainer + 1.5 dp primary outline + icon scale 112%; list outline and tint for `Current`; chip "Push into Camera" / "Push to /sdcard/DCIM/Camera/" / "Move into Camera", rising 8 dp; spring-open line filling the row bottom over 800 ms, then `open` with forward motion while the drag continues; crumbs light up and accept push and move. In-tab move drags use Compose pointer drag (`detectDragGestures` on rows) with dragged rows at 45% alpha and the ghost pill (download icon, "1 file" / "3 items").

- [ ] **Step 1: Write failing tests**: `dropTargetFor` table (external over folder → Folder; over file → Current; internal over a dragged folder → null; blocked → null); Compose test with a synthetic `DragAndDropEvent` of a file list: hover a folder row shows "Push into Telegram", drop calls `push(paths, "/sdcard/Download/Telegram")`; hover 800 ms opens the folder; drop on empty space pushes to the current folder; drop on the "Internal storage" crumb pushes to `/sdcard`; the mirror's drop overlay does not show while over the sheet; internal drag of two rows onto a folder row calls `move` once.
- [ ] **Step 2:** Run `--tests '*FilesDropTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then full suite (including `FileDropTest`). Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): drop to push, spring-open folders, drag to move`

---

### Task 12: Drag out to the computer (pull)

Starts only after the user has seen Task 0's note.

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/files/DragOut.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/files/DragCache.kt`
- Modify: `FilesTab.kt`, `FileRow.kt`, app start (cache sweep), window close (cache delete)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/files/DragCacheTest.kt`, `.../ui/mirror/files/DragOutTest.kt`

**Interfaces:**
- Consumes: Task 0's API shape; `FileTransferQueue.enqueue` with `TransferKind.Pull` and a `path` inside the drag cache; `jobs` for progress.
- Produces:
  - `class DragCache(root: Path = Path(System.getProperty("java.io.tmpdir"), "scrcpy-vwr")) { fun newDragDir(serial: String): Path; fun deleteSession(serial: String); fun sweep() }`
  - `class DragOutController(...)` with `fun begin(names: List<String>)`, `fun pointerLeftWindow()`, `fun released()`, observable `progress: Float?` and `readyFiles: List<File>?`.

Behavior per the spec's "Out of the window (pull)" steps 1–6, using whichever start rule Task 0 settled.

- [ ] **Step 1: Write failing tests**: `DragCache` creates `<root>/<serial>/drag/<random>/`, `deleteSession` removes it, `sweep` removes leftovers; `DragOutController`: `begin` enqueues one pull per name into a fresh drag dir; `released()` before ready cancels those jobs and deletes the dir; ready + pointer outside calls the injected outbound starter with the pulled files (fake starter).
- [ ] **Step 2:** Run `--tests '*DragCacheTest' --tests '*DragOutTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(files): drag files out of the window to pull them`

---

### Task 13: Screenshots, device check, hand-check list

**Files:**
- Modify: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/MirrorScreenshotTest.kt` (Files tab: list with a selection, empty, blocked, delete dialog, tray running; light and dark; Material, Glass, Retro)
- Create: `docs/superpowers/notes/2026-11-12-files-tab-hand-check.md`

- [ ] **Step 1:** Add screenshot cases; run `--tests '*MirrorScreenshotTest'`, record the new goldens the way the test file documents, and look at each image.
- [ ] **Step 2:** `cdm acquire --android --device R5CX12GDWPZ`, run the app, and walk the spec's manual list: push by drop onto list, row and crumb; spring-open; pull by button, menu, double-click and drag to Finder; move by drag; rename; delete; new folder; `Android/data` blocked; unplug mid-transfer. `cdm release`.
- [ ] **Step 3:** Write the hand-check note with what passed, and list what still needs Windows and Linux hands.
- [ ] **Step 4: Commit** `test(files): Files tab screenshots and hand-check list`
