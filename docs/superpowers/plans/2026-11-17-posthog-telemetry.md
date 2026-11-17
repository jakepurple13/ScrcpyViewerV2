# PostHog Telemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Anonymous PostHog usage events and crash reports from Scrcpy Vwr, controlled by new Settings › Privacy switches and a consent page, with the PostHog key injected at build time.

**Architecture:** A `telemetry` package holds a facade (`Telemetry`) that checks consent, adds common properties, scrubs, and appends to a JSON Lines queue file. A Ktor-based sender drains the queue to PostHog's `/batch/` endpoint every 30 s, on startup and on quit. Crash capture writes `$exception` events to the same queue synchronously. A blank build-time key swaps in a no-op `Telemetry`.

**Tech Stack:** Kotlin 2.3 / Compose Multiplatform desktop, Koin annotations, kotlinx.serialization, Ktor client 3.x (CIO engine), kotlin.test + kotlinx-coroutines-test, Ktor `MockEngine`.

**Spec:** `docs/superpowers/specs/2026-11-17-posthog-telemetry-design.md` (read it first; it wins over the proposal page).

Paths below: `S/` = `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/`, `T/` = `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/`.

## Global Constraints

- Host default `https://us.i.posthog.com`. Endpoint `{host}/batch/`, body `{"api_key": KEY, "batch": [...]}`.
- Never send: device serials, IP addresses, device model names, package/app names, shell commands, logcat lines, custom action commands, file names, folder paths, link URLs, preset names, scrcpy option values, screenshots, recordings.
- Exception `value` (message) only for exception classes whose name starts with `com.programmersbox.scrcpyviewer`; all others send an empty value.
- Defaults: `crashReports = true`, `usageStats = false`, `privacyPromptSeen = false`.
- Channel: `release` only when Gradle property `posthog.channel=release`; otherwise `dev`.
- Blank key: no Ktor client created, no telemetry files written, no network, Privacy switches disabled with banner text exactly: "This build has no analytics key, so nothing is sent. Release builds from GitHub include it."
- Queue cap 1,000 events / 1 MB, oldest dropped. Batch size ≤ 100. Flush every 30 s. Backoff exponential, max 10 min. 4xx other than 429 drops the batch.
- Quit flush capped at 2 s inside `AppShutdown`.
- Strings capped at 500 characters after scrubbing.
- Every event: `$process_person_profile: false`, `distinct_id` = install ID, plus `app_version`, `os`, `os_version`, `arch`, `channel`, `session_id`, `$lib = "scrcpy-vwr"`.
- Telemetry calls never throw into the caller and never block the UI thread (except the crash handler's synchronous queue write).
- Tests never send: Koin test graphs and unit tests use `TelemetryConfig.Disabled` or a fake sender.
- Every UI state change animates (switch, banner, disabled state, recent-events list, ID reset, onboarding page).
- Run Gradle from one agent at a time: parallel Gradle runs OOM this machine.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. PostHog unreachable for hours (offline laptop): queue stays ≤ cap, no UI error, quit still ends within the 2 s flush cap. Test in Task 4.
2. A half-written last line in `queue.jsonl` (JVM died mid-append): the queue skips it and keeps the rest. Test in Task 3.
3. `track()` called from many threads while a flush runs: no event lost or sent twice. Test in Task 3.
4. Telemetry's own failure (data dir read-only, disk full, serializer error): `track` and `captureException` swallow it, and the crash handler still defers to the previous handler. Tests in Tasks 5 and 6.
5. Consent switched off while events are queued or a batch is in flight: the switched-off category is deleted from the queue and the in-flight batch is not re-queued on failure. Tests in Tasks 3 and 5.

---

### Task 1: Build-time key injection and Ktor dependency

**Files:**
- Modify: `gradle/libs.versions.toml` (add `ktor` version and libraries)
- Modify: `composeApp/build.gradle.kts` (`generateAppVersion` around lines 35-58; jvmMain deps ~116; jvmTest deps ~151)
- Modify: `.github/workflows/build.yml:101-103`

**Interfaces:**
- Produces: generated `BuildInfo.POSTHOG_KEY: String`, `BuildInfo.POSTHOG_HOST: String`, `BuildInfo.TELEMETRY_CHANNEL: String` (`"dev"` or `"release"`).

- [ ] **Step 1: Add Ktor to the catalog.** Version: latest stable 3.x published at least two weeks ago (check Maven Central). Libraries `ktor-client-core`, `ktor-client-cio`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json` (jvmMain) and `ktor-client-mock` (jvmTest). Comment the version line like its neighbours: `# PostHog telemetry sender (telemetry/TelemetrySender.kt).`
- [ ] **Step 2: Extend `generateAppVersion`.** Read `posthog.key`, `posthog.host` from Gradle properties first, then from `rootProject.file("local.properties")` (java.util.Properties), else `""`. Host falls back to `https://us.i.posthog.com` only when a key is present. Channel = `"release"` if property `posthog.channel` == `"release"` else `"dev"`. Register all three as task inputs and write them as `const val`s into `BuildInfo`. The key must never be printed in logs.
- [ ] **Step 3: Verify local build.** The worktree has a git-ignored `local.properties` with `posthog.key` and `posthog.host` (do not print, copy or commit the key). `./gradlew :composeApp:generateAppVersion && grep -c 'POSTHOG_KEY = "phc_' composeApp/build/generated/appVersion/kotlin/com/programmersbox/scrcpyviewer/BuildInfo.kt` → `1`, and `TELEMETRY_CHANNEL = "dev"`.
- [ ] **Step 4: Verify blank and release builds.** Move `local.properties` to `local.properties.bak`, run `generateAppVersion`, grep: `POSTHOG_KEY = ""`, `POSTHOG_HOST = ""`. Then `-Pposthog.key=phc_test -Pposthog.channel=release`: `phc_test`, `https://us.i.posthog.com`, `release`. Move `local.properties.bak` back and re-run Step 3.
- [ ] **Step 5: CI.** In the `build` job's "Build and package" step add `env: POSTHOG_KEY: ${{ secrets.POSTHOG_KEY }}`, `POSTHOG_HOST: ${{ secrets.POSTHOG_HOST }}` and append `-Pposthog.key="$POSTHOG_KEY" -Pposthog.host="$POSTHOG_HOST" -Pposthog.channel=release` to the gradlew command only when `startsWith(github.ref, 'refs/tags/v')` (bash `if [[ "$GITHUB_REF" == refs/tags/v* ]]`). Add a comment: key is a write-only PostHog project key, injected on release tags only.
- [ ] **Step 6: Commit** `build: inject PostHog key at build time and add Ktor client`.

### Task 2: Config, install ID, scrubber, buckets

**Files:**
- Create: `S/telemetry/TelemetryConfig.kt`, `S/telemetry/InstallId.kt`, `S/telemetry/Scrubber.kt`, `S/telemetry/Buckets.kt`
- Test: `T/telemetry/ScrubberTest.kt`, `T/telemetry/InstallIdTest.kt`, `T/telemetry/BucketsTest.kt`

**Interfaces:**
- Produces:
  - `data class TelemetryConfig(val key: String, val host: String, val channel: String) { val enabled: Boolean; companion object { val Disabled: TelemetryConfig; fun fromBuildInfo(): TelemetryConfig } }`
  - `class InstallId(dir: Path) { fun get(): String; fun reset(): String }` (file `dir/install-id`, UUID v4, created lazily)
  - `class Scrubber(home: String = System.getProperty("user.home")) { fun rememberSerial(serial: String); fun scrub(s: String): String }`
  - `fun bucket(value: Long, edges: List<Long>): String` → `"<a"`, `"a-b"`, `">=z"` labels; plus `val MINUTE_EDGES`, `SECOND_EDGES`, `MS_EDGES`, `BYTE_EDGES` (builder picks edges).

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun homePathBecomesTilde() = assertEquals("~/x/y.txt", Scrubber(home = "/Users/jake").scrub("/Users/jake/x/y.txt"))
@Test fun rememberedSerialIsReplaced() { val s = Scrubber("/h"); s.rememberSerial("R5CX12GDWPZ"); assertEquals("dev <device> gone", s.scrub("dev R5CX12GDWPZ gone")) }
@Test fun ipv4AndIpv6AreReplaced() = assertEquals("<ip>:5555 and <ip>", Scrubber("/h").scrub("192.168.1.20:5555 and fe80::1ff:fe23:4567:890a"))
@Test fun cappedAt500() = assertEquals(500, Scrubber("/h").scrub("a".repeat(900)).length)
@Test fun installIdIsStableUntilReset() { val d = createTempDirectory(); val a = InstallId(d).get(); assertEquals(a, InstallId(d).get()); assertNotEquals(a, InstallId(d).reset()) }
@Test fun disabledConfigIsDisabled() = assertFalse(TelemetryConfig.Disabled.enabled)
@Test fun bucketLabels() { assertEquals("<1", bucket(0, listOf(1, 5))); assertEquals("1-5", bucket(3, listOf(1, 5))); assertEquals(">=5", bucket(9, listOf(1, 5))) }
```

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests 'com.programmersbox.scrcpyviewer.telemetry.*'`. Expected: compile failure (types missing).
- [ ] **Step 3: Implement** the four files. Scrubber keeps serials in a concurrent set in memory only. `enabled = key.isNotBlank()`.
- [ ] **Step 4: Run** same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(telemetry): config, install ID, scrubber and buckets`.

### Task 3: Event model and disk queue

**Files:**
- Create: `S/telemetry/TelemetryEvent.kt`, `S/telemetry/ExceptionPayload.kt`, `S/telemetry/TelemetryQueue.kt`
- Test: `T/telemetry/TelemetryQueueTest.kt`, `T/telemetry/ExceptionPayloadTest.kt`

**Interfaces:**
- Consumes: `Scrubber` (Task 2).
- Produces:
  - `enum class TelemetryKind { Usage, Crash }`
  - `sealed class TelemetryEvent(val name: String) { abstract fun props(): Map<String, Any> }` with one data subclass per spec event (23), properties exactly as the spec's Events table; property values limited to `String`, `Number`, `Boolean`, `List<String>`.
  - `@Serializable data class QueuedEvent(val uuid: String, val event: String, val kind: TelemetryKind, val timestamp: String /*ISO-8601*/, val properties: JsonObject)`
  - `fun exceptionProperties(t: Throwable, handled: Boolean, scrubber: Scrubber): JsonObject` → `$exception_list` (outer first, causes after), each `{type, value, mechanism:{handled, type:"generic"}, stacktrace:{type:"raw", frames:[{function, module, filename, lineno, in_app, platform:"java"}]}}`; frames ordered as PostHog expects (oldest call first).
  - `class TelemetryQueue(file: Path, maxEvents: Int = 1000, maxBytes: Long = 1_000_000) { fun append(e: QueuedEvent); fun peek(n: Int): List<QueuedEvent>; fun remove(uuids: Set<String>); fun removeKind(kind: TelemetryKind); fun size(): Int }` — thread-safe, file-backed, survives restart.

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun survivesRestart() { val f = tmp(); TelemetryQueue(f).append(ev("a")); assertEquals(listOf("a"), TelemetryQueue(f).peek(10).map { it.event }) }
@Test fun dropsOldestOverCap() { val q = TelemetryQueue(tmp(), maxEvents = 3); (1..5).forEach { q.append(ev("e$it")) }; assertEquals(listOf("e3","e4","e5"), q.peek(10).map { it.event }) }
@Test fun skipsTruncatedLastLine() { val f = tmp(); TelemetryQueue(f).append(ev("ok")); f.appendText("{\"uuid\":\"x\",\"ev"); assertEquals(listOf("ok"), TelemetryQueue(f).peek(10).map { it.event }) }
@Test fun removeKindDeletesOnlyThatKind() { val q = TelemetryQueue(tmp()); q.append(ev("u", Usage)); q.append(ev("c", Crash)); q.removeKind(Usage); assertEquals(listOf("c"), q.peek(10).map { it.event }) }
@Test fun concurrentAppendsLoseNothing() { val q = TelemetryQueue(tmp()); (1..8).map { t -> thread { repeat(50) { q.append(ev("t$t-$it")) } } }.forEach { it.join() }; assertEquals(400, q.size()) }
@Test fun foreignExceptionMessageIsDropped() { val p = exceptionProperties(IllegalStateException("secret /sdcard/x com.foo.app"), handled = true, Scrubber("/h")); assertEquals("", p.list(0).str("value")) }
@Test fun ownExceptionMessageIsKept() { val p = exceptionProperties(TestOwnException("decoder stalled"), handled = false, Scrubber("/h")); assertEquals("decoder stalled", p.list(0).str("value")) }
@Test fun causeChainIsListed() { val p = exceptionProperties(RuntimeException(IOException()), true, Scrubber("/h")); assertEquals(listOf("java.lang.RuntimeException", "java.io.IOException"), p.types()) }
@Test fun inAppFlagFollowsPackage() { /* frame from com.programmersbox.* -> in_app true; java.* -> false */ }
```

`TestOwnException` is declared in `T/telemetry/` under package `com.programmersbox.scrcpyviewer.telemetry` so it counts as our own type. `ev`, `tmp`, `list`, `str`, `types` are small test helpers in the test file.

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests 'com.programmersbox.scrcpyviewer.telemetry.*'`. Expected: FAIL (compile).
- [ ] **Step 3: Implement.** Queue: one lock; append writes one JSON line and fsyncs (`FileChannel.force(false)`) so a crash handler's write survives; enforce caps by rewriting the file (temp + atomic move) when exceeded; parse lines leniently, skipping any that fail to decode.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(telemetry): typed events, exception payload and disk queue`.

### Task 4: Ktor sender

**Files:**
- Create: `S/telemetry/TelemetrySender.kt`
- Test: `T/telemetry/TelemetrySenderTest.kt`

**Interfaces:**
- Consumes: `TelemetryQueue`, `QueuedEvent`, `TelemetryConfig`.
- Produces:
  - `class TelemetrySender(config: TelemetryConfig, queue: TelemetryQueue, engine: HttpClientEngine = CIO.create(), clock: () -> Long = System::currentTimeMillis)`
  - `suspend fun flush(): FlushResult` — sends batches of ≤ 100 until the queue is empty or a failure; `enum class FlushResult { Empty, Sent, RetryLater, Dropped }`
  - `fun nextDelayMs(): Long` — 30 000 normally; after consecutive failures 30 s × 2^n capped at 600 000.
  - `fun close()`

- [ ] **Step 1: Failing tests** (all with `MockEngine`):

```kotlin
@Test fun postsBatchToHostWithApiKey()     // request url == "https://us.i.posthog.com/batch/", body.api_key == key, body.batch.size == 3, queue empty after
@Test fun splitsIntoBatchesOf100()         // 250 queued -> 3 requests (100, 100, 50)
@Test fun serverErrorKeepsQueueAndBacksOff() // 500 -> queue size unchanged, result RetryLater, nextDelayMs() == 60_000 then 120_000 ..., never > 600_000
@Test fun tooManyRequestsIsRetried()       // 429 -> RetryLater, queue unchanged
@Test fun badRequestDropsBatch()           // 400 -> Dropped, those uuids removed
@Test fun offlineDoesNotThrow()            // engine throws IOException -> RetryLater, no exception escapes
@Test fun queueStaysCappedWhileOffline()   // 1_200 appends with failing engine -> queue.size() == 1000
@Test fun eventsRemovedByConsentAreNotRequeued() // removeKind(Usage) during a failing flush -> usage events gone afterwards
```

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*TelemetrySenderTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Ktor `HttpClient(engine) { install(ContentNegotiation) { json() }; install(HttpTimeout) { requestTimeoutMillis = 10_000 } }`. Each batch item: `{event, properties, timestamp, uuid}` with `distinct_id` inside properties (added by the facade). Remove only the uuids actually sent. Never log the key.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(telemetry): Ktor batch sender with backoff`.

### Task 5: Telemetry facade, settings fields, Koin, startup and quit flush

**Files:**
- Create: `S/telemetry/Telemetry.kt`
- Modify: `S/state/AppSettings.kt` (Settings fields; capture `firstLaunch = !Files.exists(file)` in the constructor as `val firstLaunch: Boolean`)
- Modify: `S/di/AppModule.kt` (`TelemetryConfig` as a `@Provided` param of `startAppKoin`, default `TelemetryConfig.fromBuildInfo()`; `Telemetry` singleton in `StorageModule`)
- Modify: `S/AppShutdown.kt` (flush step after `quitHook.release()`, before `install()`; constant `telemetryTimeoutMs = 2_000`)
- Modify: `S/Main.kt` (start telemetry after Koin, line ~111)
- Modify: `T/FakeAppKoin.kt` (pass `TelemetryConfig.Disabled`)
- Test: `T/telemetry/TelemetryTest.kt`, extend `T/AppShutdownTest.kt` if present

**Interfaces:**
- Consumes: Tasks 2-4.
- Produces:
  - `interface Telemetry { fun track(event: TelemetryEvent); fun captureException(t: Throwable, handled: Boolean); fun captureExceptionBlocking(t: Throwable); val enabled: Boolean; val recent: StateFlow<List<RecentEvent>>; fun installId(): String; fun resetInstallId(); fun rememberSerial(serial: String); fun start(); suspend fun flush(); object Noop : Telemetry }`
  - `data class RecentEvent(val name: String, val at: Instant, val sent: Boolean)` (last 50; `sent = false` when blocked by consent)
  - `class PostHogTelemetry(config, dataDir: Path, settings: AppSettings, scope: CoroutineScope, sender: (TelemetryQueue) -> TelemetrySender = { TelemetrySender(config, it) }) : Telemetry` — files under `dataDir/telemetry/`.
  - `Settings.usageStats: Boolean = false`, `Settings.crashReports: Boolean = true`, `Settings.privacyPromptSeen: Boolean = false`
  - Koin: `Telemetry` resolves to `Telemetry.Noop` when `config.enabled` is false.
  - Existing classes that later tasks instrument take `telemetry: Telemetry = Telemetry.Noop` as a defaulted constructor param so existing tests compile unchanged.

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun usageBlockedByDefault()          // fresh settings, track(AppOpened) -> queue empty, recent[0].sent == false
@Test fun crashAllowedByDefault()          // captureException(own) -> one "$exception" queued with kind Crash
@Test fun commonPropertiesAdded()          // usageStats on -> props contain distinct_id, app_version, os, arch, channel, session_id, $lib == "scrcpy-vwr", $process_person_profile == false
@Test fun switchingUsageOffDeletesQueuedUsage()   // queue usage, settings.update { usageStats = false } -> queue has no Usage
@Test fun switchingCrashOffDeletesQueuedCrash()
@Test fun trackNeverThrowsOnUnwritableDir()       // dataDir/telemetry made read-only (assumeTrue(!Os.isWindows)) -> no exception
@Test fun disabledConfigWritesNothing()           // koin with TelemetryConfig.Disabled -> get<Telemetry>() is Telemetry.Noop, dataDir/telemetry absent
@Test fun recentKeepsLast50()
@Test fun shutdownFlushIsCappedAt2s()             // AppShutdown with a telemetry whose flush suspends forever -> run() reaches exit() within virtual 2_000 ms
```

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*TelemetryTest' --tests '*AppShutdownTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** `track` launches on `scope` with `Dispatchers.IO`; `captureExceptionBlocking` writes synchronously (crash handler use). Consent read from `settings.settings.value` at call time; a collector on `settings.settings` calls `queue.removeKind` when a switch turns off. `start()` launches the flush loop (`flush()` then `delay(sender.nextDelayMs())`). Wrap every body in `runCatching` and print failures to stderr only.
- [ ] **Step 4: Wire.** Koin singleton; Main.kt calls `telemetry.start()` after Koin; AppShutdown runs `withTimeoutOrNull(telemetryTimeoutMs) { telemetry.flush() }` after `quitHook.release()`.
- [ ] **Step 5: Run** the full suite `./gradlew :composeApp:jvmTest`. Expected: PASS (existing tests unaffected).
- [ ] **Step 6: Commit** `feat(telemetry): consent-aware facade wired into startup and quit`.

### Task 6: Crash capture

**Files:**
- Create: `S/telemetry/CrashCapture.kt`, `S/telemetry/NativeCrashScanner.kt`
- Modify: `S/Main.kt` (install handler first thing in `main()`; report native crashes after Koin), `S/di/AppModule.kt` (`AppScope` gets a `CoroutineExceptionHandler`), Compose window exception handling where windows are created (Nucleus Tao: check for `LocalWindowExceptionHandlerFactory` support; if the Tao window does not honour it, wrap window content and report from the Compose `WindowExceptionHandler` the Nucleus API exposes, and note the outcome in the commit message)
- Test: `T/telemetry/CrashCaptureTest.kt`, `T/telemetry/NativeCrashScannerTest.kt`, fixture `composeApp/src/jvmTest/resources/telemetry/hs_err_sample.log`

**Interfaces:**
- Consumes: `Telemetry` (Task 5).
- Produces:
  - `object CrashCapture { fun install(telemetry: () -> Telemetry?) }` — sets `Thread.setDefaultUncaughtExceptionHandler`, calls `telemetry()?.captureExceptionBlocking(t)` inside `runCatching`, then the previous handler.
  - `class NativeCrashScanner(dirs: List<Path>, currentPid: Long) { fun scan(): List<NativeCrash>; fun delete(c: NativeCrash) }`, `data class NativeCrash(val file: Path, val signal: String, val frames: List<String>)` — only files containing `com.programmersbox.scrcpyviewer`, pid ≠ current, top 20 frames, function names only.
  - `Telemetry.captureNativeCrash(c: NativeCrash)` added to the interface (`$exception_type = "native_crash"`, scrubbed).
- Handled errors to report with `captureException(t, handled = true)`: scrcpy-server start failure (`ScrcpySession.connect` catch, `S/scrcpy/ScrcpySession.kt:378-384`), decoder failure (`videoPlaybackFailed`, line 239), adb restart failure (`DevicesViewModel.runRestart`, `S/ui/devices/DevicesViewModel.kt:354`), update install failure (`UpdateManager.previousFailure`, `S/update/UpdateManager.kt:122`, reported once at startup as an own-type exception carrying no path).

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun uncaughtExceptionIsQueuedThenPreviousHandlerRuns()   // install with fake telemetry; throw on a new thread; fake got it; previous handler called
@Test fun handlerSurvivesTelemetryThrowing()                    // telemetry() throws -> previous handler still called
@Test fun scannerFindsOurCrashOnly()                            // dir with our fixture + a foreign hs_err -> 1 result, signal "SIGSEGV", frames.size <= 20
@Test fun scannerSkipsCurrentPid()
@Test fun nativeCrashFileDeletedEvenWhenCrashReportsOff()
@Test fun nativeCrashPropsCarryNoPaths()                        // fixture frames include /Users/x/... -> scrubbed to ~
```

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*CrashCaptureTest' --tests '*NativeCrashScannerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement**; dirs = `Paths.get("").toAbsolutePath()` and `System.getProperty("java.io.tmpdir")`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(telemetry): crash capture for uncaught, handled and native crashes`.

### Task 7: Settings › Privacy

**Files:**
- Modify: `S/ui/settings/SettingsRegistry.kt` (`SettingsCategoryId.Privacy` between `Updates` and `Help`; new `SettingsCategory(Privacy, "Privacy", …)`; `SettingsActions` gains `onUsageStats: (Boolean) -> Unit`, `onCrashReports: (Boolean) -> Unit`, `onResetInstallId: () -> Unit`)
- Modify: `S/ui/settings/SettingsControls.kt` (switch controls, banner, recent-events list, ID row)
- Modify: `S/ui/settings/SettingsViewModel.kt` (`setUsageStats`, `setCrashReports`, `resetInstallId`; expose `telemetryEnabled`, `recent`, `installId` in `SettingsUiState`)
- Modify: `S/ui/settings/SettingsScreen.kt` (wire actions)
- Test: `T/ui/settings/PrivacySettingsTest.kt`

**Interfaces:**
- Consumes: `Telemetry.enabled`, `recent`, `installId()`, `resetInstallId()`; `Settings.usageStats`, `crashReports`.
- Produces: entries with ids `privacy-usage`, `privacy-crash`, `privacy-recent`, `privacy-id`; keywords include `analytics`, `telemetry`, `crash`, `privacy`, `statistics`, `posthog`.

Copy (exact): "Share usage statistics" / "Which features you use, with no device, file or app names." · "Send crash reports" / "Error details when the app crashes, with paths and serials removed." · "See what was sent recently" · "Anonymous ID" · "Reset ID" · banner from Global Constraints.

Motion: switch thumb/track animate (Material `Switch`); banner `AnimatedVisibility(expandVertically + fadeIn)`; disabled rows animate alpha (`animateFloatAsState`); recent list expands with `AnimatedVisibility` and rows enter staggered; ID text `Crossfade` on reset; chevron rotates with `animateFloatAsState`.

- [ ] **Step 1: Failing tests** (`SettingsViewModel` level plus one `SceneDriver` scene, following `T/ui/settings/SettingsTransitionTest.kt`):

```kotlin
@Test fun privacyCategorySitsBetweenUpdatesAndHelp() = assertEquals(listOf(Appearance, Mirroring, Adb, Ai, Updates, Privacy, Help), settingsCategories.map { it.id })
@Test fun usageSwitchPersists()        // vm.setUsageStats(true) -> appSettings.settings.value.usageStats
@Test fun resetChangesInstallId()
@Test fun searchFindsPrivacyByAnalytics()   // SettingsSearch "analytics" -> privacy-usage present
@Test fun disabledBuildShowsBannerAndDisabledSwitches()  // scene with telemetryEnabled=false: banner text node present after frames(20)
```

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*PrivacySettingsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :composeApp:jvmTest --tests '*Settings*'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(settings): Privacy category for usage statistics and crash reports`.

### Task 8: Consent page

**Files:**
- Modify: `S/ui/onboarding/OnboardingDialog.kt` (`ONBOARDING_PAGE_COUNT` 4 → 5 when telemetry enabled; new last page `PrivacyPage`)
- Create: `S/ui/onboarding/PrivacyPromptDialog.kt` (standalone one-page dialog for existing users)
- Modify: `S/ui/App.kt:383-390` (pass telemetry state; show `PrivacyPromptDialog` when `onboardingSeen && !privacyPromptSeen && telemetryEnabled`)
- Modify: `S/ui/settings/SettingsViewModel.kt` (`finishOnboarding` and `finishPrivacyPrompt` set `privacyPromptSeen = true` only when telemetry is enabled)
- Test: `T/ui/PrivacyPromptTest.kt`

**Interfaces:**
- Consumes: Task 7's `setUsageStats`, `setCrashReports`.
- Produces: `fun OnboardingDialog(page: Int, onPage: (Int) -> Unit, onFinish: () -> Unit, privacy: PrivacyChoices?)` where `data class PrivacyChoices(val usage: Boolean, val crash: Boolean, val onUsage: (Boolean) -> Unit, val onCrash: (Boolean) -> Unit)`; `null` = keyless build, page omitted.

Copy (exact): title "Help improve Scrcpy Vwr"; body "Send anonymous statistics and crash reports. No device names, files, apps or commands are ever included. You can change this any time in Settings › Privacy."; rows "Usage statistics", "Crash reports"; buttons "Back", "Done" (standalone dialog: "Done" only).

Motion: page uses the existing onboarding `AnimatedContent` transition; standalone dialog uses the same enter/exit as `ActionsIntroDialog`.

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun keylessBuildHasFourPagesAndNeverSetsPromptSeen()
@Test fun keyedBuildHasFivePagesAndFinishSetsPromptSeen()
@Test fun existingUserSeesStandalonePromptOnce()   // onboardingSeen=true, privacyPromptSeen=false -> shown; after Done -> not shown
@Test fun promptStartsAtDefaults()                 // usage off, crash on
```

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*PrivacyPromptTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Update `OnboardingScreenshotTest` expectations if the page count change affects it (screenshots run only with `-Pscreenshots`).
- [ ] **Step 4: Run** `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(onboarding): consent page for usage statistics and crash reports`.

### Task 9: App, device and connection events

**Files (call sites from the codebase map):**
- `S/Main.kt` after ~111: `AppOpened(firstLaunch = appSettings.firstLaunch, themeStyle)`; `UpdateInstalled(from = updateManager.updatedFrom, to = APP_VERSION)` when `updatedFrom != null`
- `S/AppShutdown.kt` before the flush step: `AppClosed(sessionMinutes bucket, mirrorWindowsOpen)` (count captured in `request()` before sessions close)
- `S/ui/App.kt:389`: `OnboardingFinished(outcome = if (page == ONBOARDING_PAGE_COUNT - 1) "finished" else "skipped", lastStep = page)`
- `S/adb/DeviceTracker.kt:139-141` (info success): `telemetry.rememberSerial(serial)` then `DeviceConnected(connection, androidMajor, devicesConnected)`; connection = `emulator` if `isEmulatorSerial`, `wifi` if `isTcp`, else `usb`. Also call `rememberSerial` for every serial in `onPayload`.
- `S/ui/devices/WifiPairingViewModel.kt` `fail` (246) / `succeed` (251): `WifiPairing(method = tab qr|code|ip, success)`; scan result (159): `method = "scan"`
- `S/emulator/EmulatorLauncher.kt` `ready` (373) / `fail` (403) / early failures (186, 194): `EmulatorStarted(coldBoot, success, bootSeconds bucket)`
- `S/ui/devices/DevicesViewModel.kt` `runRestart` (354): `AdbRestarted(mirrorsOpen)`
- Test: `T/telemetry/AppEventsTest.kt` using a `RecordingTelemetry` fake (`T/telemetry/RecordingTelemetry.kt`, records `TelemetryEvent`s)

**Interfaces:**
- Consumes: `Telemetry`, event classes (Task 3).
- Produces: `class RecordingTelemetry : Telemetry` (test fake reused in Task 10).

- [ ] **Step 1: Failing tests:** one per call site with the existing fakes (e.g. `FakeAdbClient` for `DeviceTracker`), asserting the recorded event and properties, e.g. `assertEquals(DeviceConnected("wifi", 14, 1), rec.events.single())`, and one asserting no event property value contains the fake serial or IP.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*AppEventsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** by passing `telemetry` through constructors (Koin provides it; default `Telemetry.Noop`).
- [ ] **Step 4: Run** `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(telemetry): app, device and connection events`.

### Task 10: Mirroring, capture, sheet, preset and settings events

**Files (call sites):**
- `S/ui/mirror/MirrorController.kt` `start` (138): record start time; `MirrorStarted(connection, usedPreset = deviceOptions.effectiveBasePresetId(key) != null, advancedOptions = settings.advancedDeviceOptions, optionCount = values.entries.size, audio = "no-audio" !in values.entries)` — pass the preset lookup in as a lambda from `S/di/AppModule.kt:224-227`
- `S/scrcpy/ScrcpySession.kt`: `MirrorEnded(minutes, reason)` from `close` (closed), `disconnected` (584, disconnected), `fail` (577, error); `MirrorFailed(stage, errorClass)` from `connect` catch (378-384) and `videoPlaybackFailed` (239, stage `decode`); `FirstFrameTime(ms bucket, connection)` on first non-null `publishFrame` (222)
- `S/scrcpy/ServerLauncher.kt`: throw `MirrorStageException(stage: MirrorStage, message: String, cause: Throwable?) : IOException` (new, in `S/scrcpy/`) from push (129/158 → `Push`), tunnel/forward (244 → `Server`) and server connect (341/348/363 → `Server`), keeping today's messages so existing UI text is unchanged
- `S/ui/mirror/arrange/WindowArranger.kt` `apply` (185): `WindowsArranged(layout, windowCount, keepTiled)`
- `S/ui/mirror/follow/InputFollowing.kt` `setFollowers` (54): `InputBroadcastStarted(followerCount)` only when going from empty to non-empty
- `S/ui/mirror/shots/ScreenshotThumbnailState.kt` `finishScreenshot` after save (397): `ScreenshotTaken(after)`
- `S/scrcpy/ScrcpySession.kt` `stopRecording` (323, capture `startedNanos` first): `RecordingStopped(minutes bucket)`
- `S/ui/mirror/QuickActions.kt` `select` (429): `SheetTabOpened(tab)`; `onEvent` (482): `QuickActionRun(action)` = `QuickToggle` name, `"developer_options"`, `"reboot"`, or `"custom"` from `setCustom` (600) / `startRun` (665)
- `S/files/FileTransfer.kt` `run` outcome (470-490): `FileTransfer(direction = pull|push, size bucket, outcome)`; `Install` and `InstallFromDevice` count as `push`
- `S/presets/PresetStore.kt` `upsert` (38): `PresetSaved(totalPresets)`; `S/ui/presets/PresetsViewModel.kt` `confirmImport` (145): `PresetsImported(count, conflictChoice)` (most common choice among conflicts, or `"none"`)
- `S/ui/settings/SettingsViewModel.kt` `setTheme`/`setPalette`/`setNeon`/`setStyle` (171-187): `ThemeChanged(style, palette, mode, neon)` with the resulting settings; `selectCategory` (167): `SettingsOpened(category)`
- Test: `T/telemetry/FeatureEventsTest.kt` using `RecordingTelemetry`

- [ ] **Step 1: Failing tests:** one per call site with existing fakes, asserting events and properties; plus `mirrorStageExceptionKeepsMessage()` asserting `MirrorStageException(Push, "adb push failed", null).message == "adb push failed"` and that it `is IOException`; plus `presetNamesNeverSent()` asserting no recorded property value equals the preset name used in the test.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*FeatureEventsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(telemetry): mirroring, capture, sheet, preset and settings events`.

### Task 11: README and end-to-end check

**Files:**
- Modify: `README.md` (new "Privacy" section after Features)

- [ ] **Step 1: Write the section:** what is sent (feature use, Android major version, connection type, bucketed durations and sizes, crash type and stack trace), what is never sent (the Global Constraints list), defaults (crash reports on, usage statistics off), how to change it (Settings › Privacy), and that builds from source send nothing unless the builder adds their own `posthog.key` to `local.properties`.
- [ ] **Step 2: Manual end-to-end on this Mac** (uses the worktree's git-ignored `local.properties`): `./gradlew :composeApp:run`, turn on usage statistics, open a mirror on the Samsung SM-A156U1 (`R5CX12GDWPZ`), take a screenshot, quit. Expected in PostHog (US) within a minute: `app_opened`, `device_connected`, `mirror_started`, `screenshot_taken`, `mirror_ended`, `app_closed`, all with `channel=dev`, none carrying the serial. Then force a test exception (temporary debug menu item or `-Dscrcpyviewer.telemetryCrashTest=true` handled in Main.kt and removed before commit) and confirm it appears in Error tracking.
- [ ] **Step 3: Run** `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 4: Commit** `docs: describe usage statistics and crash reports`.
