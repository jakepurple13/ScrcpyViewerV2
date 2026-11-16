# Emulator Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A collapsible Emulators section in the Devices screen's device column that starts AVDs headless, shows their boot, mirrors them when ready (opt-in) and stops any running emulator.

**Architecture:** Pure `emulator/` model (SDK lookup, AVD catalog), an app-wide `EmulatorLauncher` (process, port, boot polling, stop) on `AppScope`, an `EmulatorsViewModel`, and `EmulatorSection` composables slotted into `DeviceColumn`; snackbars are collected in `App.kt`.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, Koin, kotlinx.serialization, kotlin.test, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-15-emulator-launcher-design.md`

## Global Constraints

- Command: `<sdk>/emulator/emulator -avd <id> -port <port> [-no-window] [-no-snapshot-load]`, env `ANDROID_SDK_ROOT` and `ANDROID_HOME` = sdk, output to `<DataDir>/emulator-logs/<id>.log`. Boot check `adb -s <serial> shell getprop sys.boot_completed`; AVD of a running emulator `adb -s <serial> emu avd name`; stop `adb -s <serial> emu kill`. All adb calls through the app's `AdbClient` (bundled or `adbPathOverride`), never the SDK's adb.
- Defaults: Mirror when ready OFF, Show emulator window OFF, section expanded. Boot timeout 4 min; stop wait 20 s; poll 1 s.
- Copy (exact): "Emulators", "Collapse", "Expand", "More", "Start", "Stop", "Retry", "Running", "Stopping", "Failed", "<n>s", "API <n> · Android <v> · <size>", "Starting emulator…", "Booting Android…", "Waiting for adb…", "Emulator exited (code <n>). <reason>", "Change", "· emulator <version>", "Cold boot", "Mirror when ready", "Show emulator window", "<name> is ready.", "<name> is ready. Opening its mirror window…", "<name> didn't start.", "Show log", "No Android SDK found", "Scrcpy Vwr looked in ANDROID_HOME, ANDROID_SDK_ROOT and <default>. Install Android Studio, or choose the SDK folder.", "Choose SDK folder", "Emulator"; new copy as written in the spec ("Android Emulator isn't installed", "No emulators yet…", "Couldn't stop <name>.", "Couldn't start the emulator: <message>", "Android didn't finish booting in 4 minutes.").
- Motion table in the spec is binding; nothing snaps. `motion: Boolean = remember { !systemReducesMotion() }` is a parameter of every new composable; motion tests pass `motion = true` explicitly (CI macOS/Windows runners report reduce motion ON). No absolute px thresholds on text widths; test paths Windows-safe.
- Never start a real emulator or touch a device in tests: `ProcessStarter` and `AdbClient` are faked.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Quitting Scrcpy Vwr while an emulator boots or runs: it keeps running and the next launch lists it as Running (stoppable). Task 2.
2. A process that exits, hangs or is stopped mid-boot always leaves the row in Stopped or Failed; no polling coroutine or `Process` outlives it. Task 2.
3. Two Starts in quick succession (same or different AVDs) never pick the same port. Task 2.
4. Booting serials are hidden from the connected rows and arrive with the normal arrival motion once ready; a selected emulator that is stopped leaves cleanly. Task 3.
5. The 240 dp device column with a long AVD name: subtitle ellipsizes, Start/Stop and ⋮ never clip or wrap. Task 3.

---

### Task 1: SDK lookup and AVD catalog

**Files:** Create `emulator/AndroidSdk.kt`, `emulator/AvdCatalog.kt`; Tests `emulator/AndroidSdkTest.kt`, `emulator/AvdCatalogTest.kt`.
**Produces:** `sealed interface SdkLookup { Found(sdk: AndroidSdk); NoEmulator(root: Path); NotFound(defaultPath: String) }`; `data class AndroidSdk(root: Path, emulator: Path, emulatorVersion: String?)`; `fun findSdk(override: String?, env: (String) -> String?, home: Path, os: OpenPlatform): SdkLookup`; `fun avdHome(env, home): Path`. `enum AvdKind { Phone, Tablet, Watch, Tv }`; `data class Avd(id, name, api: Int?, androidVersion: String?, kind, dir: Path)`; `fun parseIni(text): Map<String, String>`; `fun androidVersion(api: Int): String?`; `fun avdKind(config): AvdKind`; `fun readAvds(avdHome): List<Avd>`; `fun avdSize(dir): Long?`; `fun formatSize(bytes): String`.
- [ ] Tests (temp dirs): `override wins over env`, `ANDROID_HOME before ANDROID_SDK_ROOT before the platform default`, `sdk without emulator folder is NoEmulator`, `emulator version from source properties`, `avd home from env or default`; `ini with spaces around equals`, `api from image sysdir including 36_1`, `version table`, `watch tv tablet phone kinds`, `display name falls back to id`, `ini with missing folder is skipped`, `sorted by name`, `size formatting`.
- [ ] Implement; run `--tests '*AndroidSdkTest*' --tests '*AvdCatalogTest*'`; commit `feat(emulators): sdk lookup and avd catalog`.

### Task 2: EmulatorLauncher

**Files:** Create `emulator/EmulatorLauncher.kt`; Modify `state/AppSettings.kt` (`androidSdkPath`, `emulatorsExpanded`, `emulatorPrefs`, `@Serializable EmulatorPrefs`), `di/AppModule.kt` (`@Singleton emulatorLauncher(...)` in `AdbModule`); Tests `emulator/EmulatorLauncherTest.kt`, `state/AppSettingsTest.kt`.
**Produces:** `fun interface ProcessStarter { fun start(command: List<String>, env: Map<String, String>, log: Path): EmulatorProcess }` with `interface EmulatorProcess { val exit: Deferred<Int>; fun destroy(); fun destroyForcibly() }`; `sealed interface AvdState { Stopped; Booting(serial, stage: BootStage, startedAt, cold); Running(serial); Stopping(serial); Failed(reason, cold) }`; `enum BootStage { Starting, Booting, WaitingForAdb }`; `class EmulatorLauncher(adb, devices: StateFlow<List<Device>>, settings: AppSettings, mirrors: MirrorRequests, logDir, scope, starter, clock)` with `states: StateFlow<Map<String, AvdState>>`, `notices: SharedFlow<EmulatorNotice>`, `start(avd, sdk, cold)`, `stop(avdId)`, `bootingSerials: StateFlow<Set<String>>`; helpers `emulatorCommand(sdk, avd, port, showWindow, cold)`, `pickPort(connected, isFree)`, `failureReason(log): String?`. `EmulatorNotice(text, logFile?, mirrorSerial?)`.
- [ ] Tests (virtual time): `start builds the command and env`, `cold boot adds no-snapshot-load and show window drops no-window`, `port skips connected and bound ports`, `two starts get different ports`, `stages go starting booting waiting then running`, `ready saves last boot time and says ready`, `mirror when ready requests a mirror`, `process exit fails with the log reason`, `boot timeout kills and fails`, `stop before adb lists it destroys the process`, `stop sends emu kill and waits for the serial to leave`, `stop that never finishes returns to running`, `external emulator is matched by emu avd name and can be stopped`; AppSettingsTest: new fields default and round-trip, unknown prefs keys ignored.
- [ ] Implement; run `--tests '*EmulatorLauncherTest*' --tests '*AppSettingsTest*'`; commit `feat(emulators): launch, boot watch and stop`.

### Task 3: Emulators section UI

**Files:** Create `ui/devices/emulators/EmulatorsViewModel.kt`, `ui/devices/emulators/EmulatorSection.kt`, `ui/devices/emulators/EmulatorMotion.kt`; Modify `ui/devices/DevicesScreen.kt` (`DevicesContent`/`DeviceColumn` take `emulators: EmulatorsUiState`, `onEmulator: (EmulatorEvent) -> Unit`, `motion`; shared scroll area; hide `bootingSerials`; "Emulator" tag in `DeviceRow`), `ui/App.kt` (collect `EmulatorLauncher.notices` into the snackbar with Show log; pass `NativeSettingsFileDialogs.chooseFolder` for Choose SDK folder / Change); Tests `ui/devices/emulators/EmulatorsViewModelTest.kt`, `ui/devices/emulators/EmulatorSectionTest.kt`, `ui/DevicesScreenshotTest.kt`.
**Produces:** `@KoinViewModel class EmulatorsViewModel(launcher, settings, scope)` with `state: StateFlow<EmulatorsUiState>` (`sdk: SdkLookup`, `rows: List<EmulatorRow(avd, state, size, prefs)>`, `expanded`) and `onEvent(EmulatorEvent)` (`Start`, `ColdBoot`, `Stop`, `Retry`, `ToggleMirror`, `ToggleWindow`, `SetExpanded`, `SdkChosen(path)`, `Reload`); `@Composable EmulatorSection(state, onEvent, onChooseSdk, modifier, motion)`, `EmulatorRowItem`, `EmulatorMenu`, `SdkEmpty`.
- [ ] Tests: view model states (no SDK, no emulator, no AVDs, rows), SDK chosen saves and reloads, toggles and expanded saved, booting serials hidden; `EmulatorSectionTest` for each spec motion row with `motion = true` (moves over frames, no single-frame jump) and `false` (one frame or 150 ms fade); screenshots: rows in every state light/dark, collapsed, No SDK, menu open, 240 dp list with a long name.
- [ ] Implement; run `--tests '*Emulator*' --tests '*DevicesScreen*' --tests '*DeviceMotion*'` and screenshots (open each PNG); commit `feat(emulators): emulators section on the devices screen`.
