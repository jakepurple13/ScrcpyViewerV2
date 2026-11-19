# Taskbar and Dock Update Alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bounce the Dock icon or flash the taskbar button once when a background check finds a new release, and show
download progress (red on failure) on the icon.

**Architecture:** `UpdateManager` exposes the release a background check announced. A pure `TaskbarUpdateIndicator`
turns update states and announcements into calls on a `TaskbarSink` interface, and is unit tested with a fake sink.
`NucleusTaskbarSink` implements the sink with the `nucleus.taskbar-progress-tao` extension functions on the main
`NucleusWindow`, wired in `Main.kt`.

**Tech Stack:** Kotlin/JVM, Compose Desktop (Nucleus Tao backend), kotlinx.coroutines, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-19-taskbar-update-progress-design.md`

## Global Constraints

- Dependency: `dev.nucleusframework:nucleus.taskbar-progress-tao`, `version.ref = "nucleus"` (2.5.18).
- Attention type is always `TaskbarProgress.AttentionType.INFORMATIONAL`; never `CRITICAL`.
- At most one attention request per release version per launch. None while `popupDismissed` is true.
- A check with `userInitiated = true` never announces.
- No second attention request on `Ready` (that was option C, rejected).
- Only the main window gets taskbar calls. Mirror and logcat windows get none.
- No in-app UI changes, so no narrow-width tests are needed.

## Review Focus

- The launch check finishes before the main window's effect subscribes. Expected: the alert still fires once.
  `announced` is a `StateFlow`, so it replays (Task 1 and Task 2 tests).
- A download reports `total <= 0` (unknown size). Expected: indeterminate bar, no divide by zero (Task 2).
- Download chunks arrive many times per percent. Expected: `progress()` is sent only when the whole percent changes
  (Task 2).
- "Later" on 1.6.0, then a background check finds 1.7.0. Expected: one alert for 1.7.0. `check()` resets
  `popupDismissed` before it sets `announced` (Task 1).
- The main window effect is disposed while the bar shows. Expected: the bar is hidden (Task 3, `onDispose`).

## Deviation from the spec

The spec named `newRelease: SharedFlow<Release>`. The plan uses `announced: StateFlow<Release?>` instead, because a
`SharedFlow` without replay drops the launch check's emission when the window's effect subscribes late.

---

### Task 1: `UpdateManager.announced`

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/update/UpdateManager.kt` (the `check()`
  success block, about lines 176-185, plus a new property next to `popupDismissed`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/update/UpdateManagerTest.kt`

**Interfaces:**
- Produces: `val announced: StateFlow<Release?>` on `UpdateManager`. It holds the last release that a background
  check found and that differs from the `available` before that check. It starts as `null` and is never cleared.

- [ ] **Step 1: Write the failing tests** in `UpdateManagerTest`:

```kotlin
@Test
fun `a background check announces a new release`() {
    val m = manager()
    m.checkAndAwaitAvailable()
    assertEquals("1.6.0", m.announced.value?.version)
}

@Test
fun `a check from Settings does not announce`() {
    val m = manager()
    m.check(userInitiated = true)
    m.awaitState { it is UpdateState.Available }
    assertNull(m.announced.value)
}

@Test
fun `the same release found again is not announced again`() {
    val m = manager()
    m.check(userInitiated = true)
    m.awaitState { it is UpdateState.Available }
    m.checkAndAwaitAvailable() // background, same 1.6.0
    assertNull(m.announced.value)
}

@Test
fun `a newer release after Later is announced with the popup reset`() {
    var version = "1.6.0"
    val m = manager(FakeUpdateBackend { FakeUpdateBackend.available(version) })
    m.checkAndAwaitAvailable()
    m.dismissPopup()
    version = "1.7.0"
    m.check(userInitiated = false)
    val announced = await { m.announced.first { it?.version == "1.7.0" } }
    assertNotNull(announced)
    assertFalse(m.popupDismissed.value)
}
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew :composeApp:jvmTest --tests "*UpdateManagerTest*announce*" --tests "*UpdateManagerTest*Later is announced*"`
Expected: compile failure, `Unresolved reference 'announced'`.

- [ ] **Step 3: Implement.** Add `private val _announced = MutableStateFlow<Release?>(null)` and the public
  `announced` property, with KDoc. In `check()`, pass `userInitiated` into the launched block. Inside the existing
  `synchronized(lock)` success block, after the `_popupDismissed` reset and before `_available.value = release`, add:
  `if (!userInitiated && release != null && release.version != _available.value?.version) _announced.value = release`.

- [ ] **Step 4: Run all UpdateManager tests**

Run: `./gradlew :composeApp:jvmTest --tests "*UpdateManagerTest*"`
Expected: PASS, including the existing tests.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/update/UpdateManager.kt composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/update/UpdateManagerTest.kt
git commit -m "feat(update): announce releases found by background checks"
```

---

### Task 2: `TaskbarUpdateIndicator`

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/update/TaskbarUpdateIndicator.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/update/TaskbarUpdateIndicatorTest.kt`

**Interfaces:**
- Consumes: `UpdateManager.announced`, `UpdateManager.state`, `UpdateManager.popupDismissed` (Task 1).
- Produces:

```kotlin
/** Where the indicator draws: the Dock icon or the taskbar button. Fractions are in 0.0..1.0. */
interface TaskbarSink {
    fun attention()
    fun progress(fraction: Double)
    fun indeterminate()
    fun error(fraction: Double)
    fun hide()
}

class TaskbarUpdateIndicator(private val sink: TaskbarSink) {
    fun onAnnounced(release: Release?, popupDismissed: Boolean)
    fun onState(state: UpdateState)
    /** Collects the three flows until cancelled. It does not hide the bar on exit; the caller's onDispose does. */
    suspend fun run(manager: UpdateManager)
}
```

Behavior:
- `onAnnounced`: calls `attention()` when `release != null`, `!popupDismissed`, and the release's version has not
  been alerted yet. Remember alerted versions in a `MutableSet<String>`.
- `onState`:
  - `Downloading` with `total > 0` computes `fraction = read / total`, clamped to `0.0..1.0`. It sends
    `progress(fraction)` only when `floor(fraction * 100)` differs from the last percent sent.
  - `Downloading` with `total <= 0` sends `indeterminate()` once.
  - `Failed` right after a `Downloading` sends `error(lastFraction)`.
  - `Failed` after anything else sends `hide()`.
  - `Installing` sends nothing.
  - Every other state sends `hide()`.
  - After each non-`Downloading` state, reset the last percent, so a retry starts its bar from the beginning.
- `run`: `coroutineScope { launch { manager.state.collect(::onState) }; launch { combine(manager.announced, manager.popupDismissed, ::Pair).collect { (r, d) -> onAnnounced(r, d) } } }`.
  The calls cross threads, so mark the state with `@Synchronized` or confine it to one dispatcher. The collectors
  run on the caller's (UI) dispatcher, so confinement is enough. Note that in KDoc.

- [ ] **Step 1: Write the failing tests** with a `FakeSink` that records calls as strings (`"attention"`,
  `"progress:0.4"`, `"indeterminate"`, `"error:0.4"`, `"hide"`). `v160` is `Release("1.6.0", "u")`:

```kotlin
@Test fun `an announced release asks for attention once`() {
    ind.onAnnounced(v160, popupDismissed = false)
    ind.onAnnounced(v160, popupDismissed = false)
    assertEquals(listOf("attention"), sink.calls)
}
@Test fun `nothing while the popup is dismissed or nothing is announced`() {
    ind.onAnnounced(null, popupDismissed = false)
    ind.onAnnounced(v160, popupDismissed = true)
    assertEquals(emptyList(), sink.calls)
}
@Test fun `a newer release asks again`() {
    ind.onAnnounced(v160, false); ind.onAnnounced(v160.copy(version = "1.7.0"), false)
    assertEquals(listOf("attention", "attention"), sink.calls)
}
@Test fun `download progress is sent per whole percent`() {
    listOf(0L, 5L, 10L, 15L, 40L).forEach { ind.onState(UpdateState.Downloading(v160, it, 1000)) }
    assertEquals(listOf("progress:0.0", "progress:0.01", "progress:0.04"), sink.calls)
}
@Test fun `unknown size shows indeterminate once`() {
    ind.onState(UpdateState.Downloading(v160, 10, 0)); ind.onState(UpdateState.Downloading(v160, 20, -1))
    assertEquals(listOf("indeterminate"), sink.calls)
}
@Test fun `a failed download turns the bar red at its last fraction`() {
    ind.onState(UpdateState.Downloading(v160, 62, 100))
    ind.onState(UpdateState.Failed("boom", v160))
    assertEquals(listOf("progress:0.62", "error:0.62"), sink.calls)
}
@Test fun `a failed check hides`() {
    ind.onState(UpdateState.Checking); ind.onState(UpdateState.Failed("offline", null))
    assertEquals(listOf("hide", "hide"), sink.calls)
}
@Test fun `ready, cancel and idle hide the bar; installing leaves it`() {
    ind.onState(UpdateState.Downloading(v160, 50, 100))
    ind.onState(UpdateState.Ready(v160))
    ind.onState(UpdateState.Available(v160))
    ind.onState(UpdateState.Installing(v160))
    assertEquals(listOf("progress:0.5", "hide", "hide"), sink.calls)
}
@Test fun `a retry after failure starts the bar again`() {
    ind.onState(UpdateState.Downloading(v160, 50, 100)); ind.onState(UpdateState.Failed("x", v160))
    ind.onState(UpdateState.Downloading(v160, 50, 100))
    assertEquals(listOf("progress:0.5", "error:0.5", "progress:0.5"), sink.calls)
}
```

Also add one test for `run`: build an `UpdateManager` the way `UpdateManagerTest.manager()` does (copy the helper),
run a background check to `Available`, then start `indicator.run(manager)` in a `launch` and assert
`sink.calls.first() == "attention"` within 5 s, then cancel. This covers the late-subscription case in Review Focus.

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew :composeApp:jvmTest --tests "*TaskbarUpdateIndicatorTest*"`
Expected: compile failure, `Unresolved reference 'TaskbarUpdateIndicator'`.

- [ ] **Step 3: Implement `TaskbarSink` and `TaskbarUpdateIndicator`** in `TaskbarUpdateIndicator.kt`, as described
  above. Format fractions in the fake with `toString()` on the `Double` (for example `0.62`). Compute fractions as
  `read.toDouble() / total`, so 62/100 gives exactly `0.62`.

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :composeApp:jvmTest --tests "*TaskbarUpdateIndicatorTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/update/TaskbarUpdateIndicator.kt composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/update/TaskbarUpdateIndicatorTest.kt
git commit -m "feat(update): map update states to taskbar alerts and progress"
```

---

### Task 3: Nucleus sink, dependency and wiring

**Files:**
- Modify: `gradle/libs.versions.toml` (next to `nucleus-window-material3`)
- Modify: `composeApp/build.gradle.kts:152` (jvmMain dependencies, after `libs.nucleus.window.material3`)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/update/NucleusTaskbarSink.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/Main.kt` (inside the main `AppWindow`
  content, after `SideEffect { mainWindow = nucleusWindow }`, about line 246)

**Interfaces:**
- Consumes: `TaskbarSink`, `TaskbarUpdateIndicator(sink).run(manager)` (Task 2).
- Produces: `class NucleusTaskbarSink(private val window: NucleusWindow) : TaskbarSink`.

- [ ] **Step 1: Add the dependency.** In the catalog, add
  `nucleus-taskbar-progress-tao = { module = "dev.nucleusframework:nucleus.taskbar-progress-tao", version.ref = "nucleus" }`.
  In jvmMain, add `implementation(libs.nucleus.taskbar.progress.tao)`.

- [ ] **Step 2: Implement `NucleusTaskbarSink`.** Each method calls the matching extension from
  `dev.nucleusframework.taskbarprogress.tao`:
  - `attention()` calls `requestTaskbarAttention(TaskbarProgress.AttentionType.INFORMATIONAL)`.
  - `progress(f)` calls `showTaskbarProgress(f)`.
  - `indeterminate()` calls `showTaskbarIndeterminate()`.
  - `error(f)` calls `showTaskbarError(f)`.
  - `hide()` calls `hideTaskbarProgress()`.

  Wrap each call in `runCatching`. On failure, log at `WARNING` to `System.getLogger("scrcpyviewer.Update")`. A
  native failure must never crash the UI. The calls return `Boolean`; ignore the result.

- [ ] **Step 3: Wire it in `Main.kt`.** Add:

```kotlin
// A background check that finds a release bounces the Dock icon / flashes the taskbar button once; downloads show a bar.
if (remember { NucleusTaskbarProgress.isAvailable() }) {
    val sink = remember(nucleusWindow) { NucleusTaskbarSink(nucleusWindow) }
    val indicator = remember(sink) { TaskbarUpdateIndicator(sink) }
    LaunchedEffect(indicator) { indicator.run(updateManager) }
    DisposableEffect(sink) { onDispose { sink.hide() } }
}
```

- [ ] **Step 4: Build and run the full JVM test suite**

Run: `./gradlew :composeApp:jvmTest`
Expected: BUILD SUCCESSFUL. This is a single Gradle run; don't start a parallel one, because this machine runs out
of memory.

- [ ] **Step 5: Hand check on macOS.** Launch with `./gradlew :composeApp:run`. Move the app behind another window,
  then trigger a background check that finds a release. For a dev run, the builder may point the backend at a fake
  newer version temporarily; do not commit that. Confirm:
  1. The Dock icon bounces once.
  2. With "Download updates automatically" on, the bar fills.
  3. The bar disappears at Ready.

  Windows and Linux cannot be checked on this Mac. Report them as unchecked.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/update/NucleusTaskbarSink.kt composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/Main.kt
git commit -m "feat(update): show update alerts and download progress on the Dock and taskbar"
```
