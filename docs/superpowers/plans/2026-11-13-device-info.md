# Device Info Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Info tab beside Options in the Devices detail pane that shows battery, storage, memory, display, network and software for the selected device, refreshing battery and memory every 5 s, with the approved motion.

**Architecture:** Pure parsers turn `dumpsys`/`getprop`/`df` output into small data classes (`info` package). `DeviceInfoReader` runs two marker-separated shell commands through `AdbClient.shell`. `DeviceInfoViewModel` loads static info once per selection and polls live info while the tab is on screen. `DeviceInfoPane` draws animated cards. `DevicesScreen` gains an Options/Info segmented control and a horizontal shared-axis `AnimatedContent`.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, kotlinx.coroutines, Koin annotations (`@KoinViewModel`), kotlin.test, the repo's screenshot test support.

**Spec:** `docs/superpowers/specs/2026-11-13-device-info-design.md` (read it first; it holds the shell commands, parse rules, card copy and motion table).

## Global Constraints

- Device reads go through `AdbClient.shell(serial, cmd)` only. No spawned `adb` process.
- Live polling interval: 5000 ms. Reader timeout: 15 000 ms per call.
- Saved tab: `AppSettings.deviceDetailTab: String = "Options"` (an enum name, unknown → Options, like `themeStyle`).
- New type names must not clash with the existing `adb.DeviceInfo` (model/android/sdk/serialNo). Use the names below.
- Motion (from the spec): tab switch out 160 ms slide 36 dp + fade (`EmphasizedAccelerate`), then in 320 ms slide 36 dp + fade (`EmphasizedDecelerate`); device switch out 14 dp / 160 ms, in 18 dp / 340 ms; rings and bars 700–800 ms `EmphasizedDecelerate`; number ticks 200 ms; placeholders cross-fade 300 ms; not-ready 200 ms fade + scale 0.96. Respect the app's reduced-motion setting the way `ui/devices/DeviceMotion.kt` does.
- Copy is plain and specific (see spec "UI" section). Decimal GB with one decimal.
- Run tests with `./gradlew :composeApp:jvmTest --tests '<pattern>'`. One Gradle build at a time on this machine.

## Review Focus

1. Switching devices while a read for the previous device is still in flight: the late result must not appear under the new device. Test in Task 3.
2. Device unplugged or turned unauthorized mid-poll: polling stops, no exception escapes, the pane shows the not-ready message. Test in Task 3.
3. A Wi-Fi device whose shell hangs: the read times out after 15 s with `IOException`, the last good values stay. Test in Task 2 (timeout) and Task 3 (keep values).
4. A device with no battery (`present: false`, e.g. Android TV): battery is `null` and the card reads "No battery", no crash. Test in Task 1 and Task 4.
5. Leaving the Info tab or the Devices rail destination: polling stops (the tab's `DisposableEffect` calls `setActive(false)`). Test in Task 3.

---

### Task 1: Info models and parsers

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/info/DeviceInfoModels.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/info/DeviceInfoParsers.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/info/DeviceInfoParsersTest.kt`
- Fixtures (already committed): `composeApp/src/jvmTest/resources/deviceinfo/sm-a156u1/{battery,meminfo,df,diskstats,wm,display,ip,wifi,props}.txt`

**Interfaces:**
- Produces (all in package `com.programmersbox.scrcpyviewer.info`):
  - `enum class ChargeStatus { Charging, Discharging, NotCharging, Full, Unknown }`
  - `enum class PowerSource { Ac, Usb, Wireless, Dock, None }`
  - `enum class BatteryHealth { Good, Overheat, Dead, OverVoltage, Cold, Unknown }`
  - `data class BatteryInfo(val level: Int, val status: ChargeStatus, val power: PowerSource, val temperatureC: Double?, val voltageMv: Int?, val health: BatteryHealth)`
  - `data class MemoryInfo(val totalKb: Long, val availableKb: Long) { val usedKb: Long }`
  - `data class StorageBreakdown(val apps: Long, val media: Long, val system: Long, val other: Long)`
  - `data class StorageInfo(val totalBytes: Long, val usedBytes: Long, val breakdown: StorageBreakdown?)`
  - `data class DisplayInfo(val widthPx: Int, val heightPx: Int, val densityDpi: Int, val overrideWidthPx: Int?, val overrideHeightPx: Int?, val overrideDensityDpi: Int?, val refreshHz: Double?) { val smallestWidthDp: Int }` (uses override values when present)
  - `data class NetworkInfo(val ssid: String?, val ipAddress: String?, val linkMbps: Int?)`
  - `data class SoftwareInfo(val androidVersion: String?, val sdk: Int?, val securityPatch: String?, val buildId: String?, val abi: String?, val manufacturer: String?, val model: String?, val oneUiVersion: String?, val kernel: String?)`
  - `data class StaticInfo(val software: SoftwareInfo, val display: DisplayInfo?, val storage: StorageInfo?, val network: NetworkInfo)`
  - `data class LiveInfo(val battery: BatteryInfo?, val memory: MemoryInfo?)`
  - `object DeviceInfoParsers` with `fun sections(output: String): Map<String, String>` (splits on lines `@@name`), `battery(text): BatteryInfo?`, `memory(text): MemoryInfo?`, `storage(df: String, diskstats: String): StorageInfo?`, `display(wm: String, displayLine: String): DisplayInfo?`, `network(ip: String, wifi: String): NetworkInfo`, `software(props: String): SoftwareInfo`.

- [ ] **Step 1: Write the failing tests.** Load fixtures with `javaClass.getResource("/deviceinfo/sm-a156u1/<name>.txt")!!.readText()`. Tests and exact expectations:
  - `battery from samsung` → `BatteryInfo(100, Full, Usb, 29.2, 4334, Good)`.
  - `battery status codes` → hand text with `status: 2` → Charging, `3` → Discharging, `4` → NotCharging, `9` → Unknown; `AC powered: true` → Ac; all powered false → None.
  - `battery not present is null` → text containing `present: false` and `level: 100` → `null`.
  - `battery uses first key match` → text with `  charge counter: 1` twice and `level: 40`, `scale: 50` → level 80.
  - `memory from samsung` → `MemoryInfo(3644192, 931408)`, `usedKb == 2712784`.
  - `memory without MemAvailable falls back to free plus cached` → `MemTotal: 1000 kB`, `MemFree: 100 kB`, `Cached: 300 kB` → available 400.
  - `storage from samsung` → total `110534640L * 1024`, used `18165728L * 1024`, breakdown apps `10329260032 + 4519657306`, media `8888320 + 773464064`, system `24251482112`, other `1679273984`.
  - `storage without diskstats has no breakdown` → breakdown null.
  - `display from samsung` → `DisplayInfo(1080, 2340, 450, null, null, null, 90.0)`, `smallestWidthDp == 384`.
  - `display override` → wm text `Physical size: 1080x2340\nOverride size: 720x1560\nPhysical density: 450\nOverride density: 320` → overrides set, `smallestWidthDp == 360`.
  - `display refresh fallbacks` → line with `refreshRate 60.0` → 60.0; with `fps=120.0` → 120.0; none → null.
  - `network from samsung` → `NetworkInfo("Office-5G", "192.168.1.36", 351)`.
  - `network unknown ssid and no wlan` → wifi `SSID: <unknown ssid>`, ip empty → `NetworkInfo(null, null, null)`.
  - `software from samsung` → androidVersion "16", sdk 36, patch "2026-07-05", buildId "BP4A.251205.006.A156U1UESCEZG1", abi "arm64-v8a", manufacturer "samsung", model "SM-A156U1", oneUiVersion "8.5", kernel "5.15.189-android13-8-33641424". The fixture `props.txt` holds bare values in this order; convert it in the test to `key=value` lines (`ro.build.version.release=16` … `ro.build.version.oneui=80500`, `kernel=…`) since that is what the real command prints.
  - `software empty values are null` → `ro.build.version.oneui=` → oneUiVersion null.
  - `sections split on markers` → `"@@a\n1\n2\n@@b\n3"` → `mapOf("a" to "1\n2", "b" to "3")`; missing section reads as absent.

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*DeviceInfoParsersTest*'` → FAIL (unresolved references).
- [ ] **Step 3: Implement** the models and `DeviceInfoParsers`. Match lines by key with regexes, ignore `\r`, never by line index. Every parser returns null/partial instead of throwing.
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** `feat(info): device info models and parsers` (include the fixtures directory).

### Task 2: Device info reader

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/info/DeviceInfoReader.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/info/DeviceInfoReaderTest.kt`

**Interfaces:**
- Consumes: Task 1 parsers and models; `AdbClient.shell(serial, cmd): String`.
- Produces: `open class DeviceInfoReader(private val adb: AdbClient, private val serial: String, private val timeoutMs: Long = 15_000)` with `open suspend fun readStatic(): StaticInfo`, `open suspend fun readLive(): LiveInfo`; `companion object { val STATIC_COMMAND: String; val LIVE_COMMAND: String }` holding the spec's commands verbatim (section names `props, wm, display, df, diskstats, ip, wifi` and `battery, mem`).

- [ ] **Step 1: Write the failing tests** with a `FakeAdb` subclass of `AdbClient(adbPath = { error("no adb") })` overriding `shell` (pattern: `apps/DeviceAppsTest.kt`):
  - `readStatic splits sections` → fake returns the fixtures joined with `@@` markers → `StaticInfo` equal to the Task 1 expectations; the fake saw exactly `STATIC_COMMAND`.
  - `readLive parses battery and memory` → fixtures → expected `LiveInfo`.
  - `missing sections give nulls not errors` → fake returns `"@@props\nkernel=6.1"` → display, storage null; network all null; software.kernel "6.1".
  - `adb failure becomes IOException` → fake throws `java.net.SocketTimeoutException` → `assertFailsWith<IOException>`.
  - `slow shell times out` → fake `delay(60_000)`, reader with `timeoutMs = 50` → `IOException` (use `runBlocking`; real time is fine at 50 ms).
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*DeviceInfoReaderTest*'` → FAIL.
- [ ] **Step 3: Implement.** Wrap `adb.shell` in `withTimeout(timeoutMs)`; map `TimeoutCancellationException` and non-cancellation exceptions to `IOException` (rethrow `CancellationException`).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(info): device info reader`.

### Task 3: View model, saved tab and DI

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/state/AppSettings.kt` (add field after `advancedDeviceOptions`)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/devices/info/DeviceInfoViewModel.kt`
- Modify: DI wiring in `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/di/AppModule.kt` only if `@KoinViewModel` plus the existing `@ComponentScan` does not pick it up (check how `DevicesViewModel` is found; load the `koin:koin` skill for this step).
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/devices/info/DeviceInfoViewModelTest.kt`
- Test: extend `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/state/AppSettingsTest.kt`

**Interfaces:**
- Consumes: `DeviceSelection.state: StateFlow<DeviceSelectionState>` (devices + selected serial); Task 2 `DeviceInfoReader`.
- Produces:
  - `enum class DetailTab { Options, Info }` (in `ui/devices/info/DeviceInfoViewModel.kt`)
  - `AppSettings.Settings.deviceDetailTab: String = DetailTab.Options.name`
  - `sealed interface Load<out T> { data object Loading; data class Ready<T>(val value: T); data class Failed(val message: String) }`
  - `data class DeviceInfoUiState(val serial: String? = null, val title: String? = null, val notReady: String? = null, val static: Load<StaticInfo> = Load.Loading, val live: Load<LiveInfo> = Load.Loading, val liveUpdatedAt: Long? = null, val liveStale: Boolean = false)`
  - `@KoinViewModel class DeviceInfoViewModel(selection: DeviceSelection, settings: AppSettings, readerFor: DeviceInfoReaderFactory, scope: CoroutineScope = defaultViewModelScope(), clock: () -> Long = System::currentTimeMillis)` with `val state: StateFlow<DeviceInfoUiState>`, `val tab: StateFlow<DetailTab>`, `fun setTab(tab: DetailTab)`, `fun setActive(active: Boolean)`, `fun retry()`.
  - `fun interface DeviceInfoReaderFactory { fun create(serial: String): DeviceInfoReader }`, provided in DI as `{ DeviceInfoReader(adbClient, it) }`.
  - `notReady` text: Unauthorized → "Accept the USB debugging prompt on the device"; Offline → "Device is offline"; Other → "Device isn't ready". `title` uses the same `deviceTitle(device)` the device row uses.

- [ ] **Step 1: Write the failing tests** (use `kotlinx.coroutines.test.runTest` + `StandardTestDispatcher`, a fake reader returning scripted values, a `MutableStateFlow` device list like `DevicesViewModelTest`):
  - `does nothing until active` → no reader calls before `setActive(true)`.
  - `loads static once and polls live every 5 s` → active; advance 0 → 1 static, 1 live; advance 15 000 → 4 live calls, still 1 static.
  - `stops polling when inactive` → `setActive(false)`; advance 20 000 → no new calls.
  - `switching device ignores late results` → reader for R1 suspends on a `CompletableDeferred`; select R2; complete R1 → state.serial == "R2" and holds R2's values only.
  - `caches static per serial` → R1 → R2 → R1: static for R1 read once, state shows cached R1 static immediately (Ready before the next read finishes).
  - `not ready device does not poll` → selected device Unauthorized → `notReady == "Accept the USB debugging prompt on the device"`, zero reader calls.
  - `device turning unauthorized stops polling` → ready then flip state → no further calls, notReady set.
  - `failed live keeps last values` → live succeeds then throws `IOException` → `live` still `Ready(old)`, `liveStale == true`; next success clears `liveStale`.
  - `failed static shows message and retry reloads` → static throws `IOException("adb: closed")` → `Failed("adb: closed")`; `retry()` → Ready.
  - `tab is saved` → `setTab(Info)` → `settings.settings.value.deviceDetailTab == "Info"`; a new VM reads `tab.value == Info`.
  - In `AppSettingsTest`: `deviceDetailTab defaults to Options and round-trips`.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*DeviceInfoViewModelTest*' --tests '*AppSettingsTest*'` → FAIL.
- [ ] **Step 3: Implement.** One `collectLatest` over `combine(selection.state, active, retryTrigger)`; inside, when active and ready, read static (cache `Map<String, StaticInfo>`), then loop `readLive(); delay(5000)`. `collectLatest` cancellation is what drops late results; also check the serial before writing state.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(info): device info view model and saved detail tab`.

### Task 4: Info pane cards

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/devices/info/DeviceInfoPane.kt` (pane, header, grid, footer, states)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/devices/info/InfoCards.kt` (the six cards, ring, stacked bar, ticking number)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/devices/info/InfoText.kt` (formatting + copy report)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/devices/info/InfoTextTest.kt`

**Interfaces:**
- Consumes: Task 1 models, Task 3 `DeviceInfoUiState`, `Load`.
- Produces:
  - `@Composable fun DeviceInfoPane(state: DeviceInfoUiState, onRetry: () -> Unit, modifier: Modifier = Modifier)`
  - `object InfoText` with `fun gb(bytes: Long): String` ("18.6"), `fun batteryState(b: BatteryInfo): String`, `fun androidLine(s: SoftwareInfo): String?` ("16 (API 36)"), `fun report(title: String, serial: String, static: StaticInfo?, live: LiveInfo?): String`.

- [ ] **Step 1: Write the failing tests** in `InfoTextTest`:
  - `gb uses decimal one place` → `gb(18165728L * 1024) == "18.6"`, `gb(110534640L * 1024) == "113.2"`.
  - `battery state text` → Charging+Usb → "Charging · USB"; Full+Usb → "Full · plugged in"; Discharging+None → "On battery"; NotCharging+Ac → "Plugged in, not charging".
  - `report matches spec example` → Task 1 Samsung values with memory `MemoryInfo(3644192, 931408)` and title "Galaxy A15 5G (SM-A156U1)", serial "R5CX12GDWPZ" → exactly the 8 lines in the spec's copy example.
  - `report skips missing parts` → `static = null`, `live = LiveInfo(null, null)` → only the title line.
  - `no battery reads No battery` → `report` with `battery = null` and memory set → contains no `Battery:` line; the card copy constant `InfoText.NO_BATTERY == "No battery"`.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*InfoTextTest*'` → FAIL.
- [ ] **Step 3: Implement `InfoText`.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Implement the composables** per the spec's UI section and motion table: adaptive grid (`LazyVerticalGrid(GridCells.Adaptive(220.dp))` or a `FlowRow` with min width), card order Battery, Storage, Memory, Display, Network, Software; ring drawn in `Canvas` with `animateFloatAsState(tween(800, easing = EmphasizedDecelerate))` starting from 0 on first composition; stacked bar segments animate width the same way (700 ms); `TickingText(value: String)` = `AnimatedContent` slide-up 200 ms with `fontFeatureSettings = "tnum"`; placeholders pulse (`infiniteRepeatable`, 1.2 s) and `Crossfade`/`AnimatedContent` 300 ms into cards with `Modifier.staggeredEntrance(index, trigger = state.serial)`; failed state = message + `TextButton("Retry")`; not-ready = `AnimatedContent` fade + scale 0.96, 200 ms; footer "Updated N s ago" ticking each second (`produceState` from `liveUpdatedAt`), warning color and "Last updated" when `liveStale`; `CopyButton(text = { InfoText.report(...) }, label = "Copy as text")`. IP row click uses `rememberCopyAction()`.
- [ ] **Step 6: Compile** `./gradlew :composeApp:compileKotlinJvm` → BUILD SUCCESSFUL.
- [ ] **Step 7: Commit** `feat(info): info pane cards with motion`.

### Task 5: Options/Info tab in the Devices screen

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/devices/DevicesScreen.kt` (detail lambda around lines 250–280: add the tab row and wrap the existing `ViewSwitch` + `DeviceInfoPane` in one `AnimatedContent`)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/devices/info/DetailTabMotion.kt` (`detailTabTransform`, `deviceSwitchTransform`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/App.kt` (get `DeviceInfoViewModel` with `koinViewModel()` and pass state/callbacks down, the way `devicesViewModel` is passed)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/devices/info/DetailTabMotionTest.kt`

**Interfaces:**
- Consumes: Task 3 `DetailTab`, `DeviceInfoViewModel` API; Task 4 `DeviceInfoPane`.
- Produces: `fun detailTabTransform(from: DetailTab, to: DetailTab, reduced: Boolean): ContentTransform`; `fun deviceSwitchTransform(fromIndex: Int, toIndex: Int, reduced: Boolean): ContentTransform`; `DevicesScreen` gains params `detailTab: DetailTab = DetailTab.Options`, `onDetailTab: (DetailTab) -> Unit = {}`, `info: DeviceInfoUiState = DeviceInfoUiState()`, `onInfoActive: (Boolean) -> Unit = {}`, `onInfoRetry: () -> Unit = {}` (defaults keep existing screenshot tests compiling).

- [ ] **Step 1: Write the failing tests** in `DetailTabMotionTest`, asserting on the transform's specs the way `ui/devices/DeviceMotionTest.kt` checks motion values (read it first): Options→Info slides incoming from +36 dp (positive x) and outgoing to −36 dp; Info→Options mirrors it; incoming fade has 320 ms duration and 160 ms delay, outgoing 160 ms; `reduced = true` gives a plain fade with no slide; device switch to a later index moves content up (incoming from +18 dp y, outgoing to −14 dp y).
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*DetailTabMotionTest*'` → FAIL.
- [ ] **Step 3: Implement** `DetailTabMotion.kt`, then the screen change: `SingleChoiceSegmentedButtonRow` ("Options", "Info") at the top of the detail pane when a device is selected (match `StylePicker.kt`); `AnimatedContent(detailTab, transitionSpec = { detailTabTransform(initialState, targetState, reduced) })`; Info branch = `DeviceInfoPane` inside a `DisposableEffect(Unit) { onInfoActive(true); onDispose { onInfoActive(false) } }`; inside the pane, an `AnimatedContent(state.serial)` with `deviceSwitchTransform` using each serial's index in `state.devices`. Keep the edit to `DevicesScreen.kt` to the tab row and this wrapper.
- [ ] **Step 4: Run** the motion test → PASS, then `./gradlew :composeApp:jvmTest --tests '*DevicesViewModelTest*' --tests '*DevicesScreenshotTest*' --tests '*AppNavigationTest*'` → PASS (existing screenshots may need the tab row: regenerate only those that changed because of the new control, and say so in the commit message).
- [ ] **Step 5: Commit** `feat(devices): Options and Info tabs with sideways motion`.

### Task 6: Screenshot tests

**Files:**
- Modify: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/DevicesScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 5 `DevicesScreen` params; Task 1 models (build the Samsung values in the test).

- [ ] **Step 1: Add tests** following the file's `render(...)` helper: `devices info light` and `devices info dark` (Ready static + live from the Samsung values), `devices info loading` (Loading/Loading), `devices info failed` (static `Failed("Couldn't reach the device")`), `devices info not ready` (notReady text). Animations must be settled in the capture (use the helper's existing clock handling).
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*DevicesScreenshotTest*'` → first run records the new images, second run → PASS. Open each new PNG and check cards, footer and tab row look right in both themes.
- [ ] **Step 3: Commit** `test(devices): info tab screenshots`.
