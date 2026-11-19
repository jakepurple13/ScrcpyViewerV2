# Taskbar and Dock update alerts

## Intent

Today the only signs of a new release are inside the main window: the Settings badge and the update popup. When
Scrcpy Vwr sits behind other windows, the user never sees them. Use Nucleus' taskbar progress library to show the
update on the Dock icon (macOS) and on the taskbar button (Windows, Linux).

Mockup the user approved: https://claude.ai/artifact/MRffCW3iCvZBw4uPU3d7es (option B).

## Goals

- When a background check finds a new release, request attention once: `requestTaskbarAttention(INFORMATIONAL)`.
  macOS bounces the Dock icon once (only when the app is not frontmost). Windows flashes the taskbar button 4 times.
- While the update downloads (in the background or after "Update now"), show a progress bar on the icon from
  `read / total`.
- When a download fails, turn the bar red (`showTaskbarError(lastFraction)`). It stays red until a retry or the next
  check.
- When the update is `Ready`, is cancelled, or the state leaves the download states in any other way, hide the bar.

## Non-goals and anti-goals

- No second bounce when the update is `Ready` (that was option C).
- No repeated alerts. A release gets at most one attention request per launch. After "Later", the same release
  never alerts again; a newer release can alert.
- Mirror and logcat windows get no bars of their own. On macOS the Dock bar covers the whole app anyway.
- Nothing changes inside the app UI: no layout, badge or popup changes.

## Approaches

- **A. Attention only.** Cheapest, but it leaves out the download, which already reports progress.
- **B. Attention + download progress (picked).** It signals the new release and shows the download, which is
  usually what happens right after, without nagging.
- **C. B + a "Ready" bounce.** A second bounce per update felt like nagging.

## Design

- **Dependency:** add `nucleus-taskbar-progress-tao` (`dev.nucleusframework:nucleus.taskbar-progress-tao`,
  version.ref `nucleus`) to `libs.versions.toml`, and add it to jvmMain.
- **UpdateManager:** add a `newRelease: SharedFlow<Release>`. It emits when a check that the user did **not**
  start finds a release whose version differs from the previous `available`. A check started from Settings does not
  emit, because the user is already looking at the app. Covered in `UpdateManagerTest`.
- **TaskbarUpdateIndicator** (new, in `update/`): a small class that maps `UpdateManager.state` and `newRelease`
  onto a `TaskbarSink` interface (`attention()`, `progress(Double)`, `error(Double)`, `hide()`). It has no Compose
  and no Nucleus types, so it can be tested with a fake sink. It skips `attention()` while `popupDismissed` is true.
  - `Downloading` sends `progress(read / total)`. When `total <= 0`, it shows indeterminate progress instead.
  - `Downloading` followed by `Failed` sends `error(lastFraction)`.
  - `Ready`, `Available`, `Checking`, `UpToDate` and `Idle` send `hide()`. `Installing` sends nothing, because the
    app quits.
- **Wiring (`Main.kt`):** inside the main `AppWindow`, run a `LaunchedEffect(nucleusWindow)` that runs the
  indicator against a sink calling the `NucleusWindow` extension functions. Call `hideTaskbarProgress()` when the
  effect is disposed. Skip everything when `NucleusTaskbarProgress.isAvailable()` is false.

## Left to the builder

- Whether to skip the Windows flash while the main window has focus (macOS already ignores a bounce for the
  frontmost app).
- Linux: rely on Nucleus' `.desktop` auto-detection. Set `TaskbarProgress.linuxDesktopFilename` only if packaged
  builds turn out to need it.
- Throttle progress calls if per-chunk updates are too frequent. Sending a value only when the whole percent
  changes is enough.
- Check that the release build (ProGuard) keeps the library's JNI bridge classes.
