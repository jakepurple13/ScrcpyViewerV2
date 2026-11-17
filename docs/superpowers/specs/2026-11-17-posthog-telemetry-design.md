# PostHog analytics and crash reporting

Date: 2026-11-17. Status: draft, awaiting approval.
Visual proposal (events, mockups): https://claude.ai/artifact/4PFEZSiSpj8QeHLbpnBRWU. Where the page and this
document differ (defaults, the dropped `-Pposthog.dev` flag), this document wins.

## Intent

The maintainer wants to learn how Scrcpy Vwr is used (which features, which connection types, what fails) and to
receive crash reports from release builds, through one vendor: PostHog (US cloud). The app is open source and
distributed through GitHub releases, so the data must be anonymous, the user must control it, and the PostHog
credentials must never be committed.

## Goals

- Anonymous usage events (the 23 listed below) from release builds and from local runs on the maintainer's Mac.
- Crash reports for uncaught JVM exceptions, native crashes (FFmpeg/JNA via `hs_err_pid*.log`), and selected handled
  errors, sent as PostHog `$exception` events so PostHog error tracking groups them.
- A new Settings › Privacy category with separate switches for usage statistics and crash reports.
- A consent page: the last onboarding page for new users, and a one-time prompt for existing users after updating.
- PostHog key and host injected at build time: from `local.properties` locally, from GitHub secrets on release
  (`v*` tag) CI runs.

## Non-goals

- Session replay, feature flags, surveys, heatmaps.
- Identifying users or linking an install to a person or device.
- Sentry or any second vendor.
- Analytics on CI PR or main builds.

## Anti-goals (failure even if it "works")

- Any of these leaving the machine: device serials, IP addresses, device model names, package or app names, shell
  commands, logcat lines, custom action commands, file names, folder paths, link URLs, preset names, scrcpy option
  values, screenshots, recordings.
- The app slowing down, blocking the UI thread, or showing errors because PostHog is unreachable.
- Analytics code running at all in a build without a key.

## Decisions (made with the maintainer)

| Topic | Decision |
|---|---|
| Vendor | PostHog, US cloud. Host `https://us.i.posthog.com`. |
| Crash reporting | Yes, through PostHog error tracking (`$exception` events). |
| Client | Our own small client. HTTP via **Ktor client** (maintainer's choice over `java.net.http`). No PostHog SDK. |
| Defaults | Crash reports **on** by default, usage statistics **off** by default. Both shown in the consent prompt. |
| Local builds | Key and host come from `local.properties` (git-ignored). Every local run that has them sends, tagged `channel=dev`. A machine without them compiles with blanks and sends nothing. |
| CI builds | Only `v*` tag runs get `POSTHOG_KEY` / `POSTHOG_HOST` from GitHub secrets, tagged `channel=release`. PR and main runs get none. |
| Settings | New `SettingsCategoryId.Privacy`, placed between Updates and Help. |

## Approaches considered

- **A. Own client on Ktor with a disk-backed queue (picked).** Events and crash reports are appended to a queue file
  in the app data dir before sending, so a crash report written just before the JVM dies is sent on the next launch.
  Scrubbing lives in one place, and tests swap in a fake sender. Cost: we write batching and retry ourselves. The
  maintainer picked Ktor over `java.net.http` for robustness, accepting the new dependency.
- **B. PostHog `posthog-server` Java SDK.** Built-in batching and retry, but its queue is in memory, so events and
  crash reports are lost on a crash or kill; we would still need our own disk queue for crashes. Rejected.
- **C. Sentry for crashes plus PostHog for usage.** Two vendors and dashboards; Sentry's free tier is 1 user and about
  5k errors a month. Rejected.

## Design

### Build-time configuration

- `generateAppVersion` in `composeApp/build.gradle.kts` adds `POSTHOG_KEY`, `POSTHOG_HOST` and `TELEMETRY_CHANNEL`
  to the generated `BuildInfo` object.
- Resolution order for key and host: Gradle property `posthog.key` / `posthog.host` (CI passes
  `-Pposthog.key=… -Pposthog.host=…` from secrets), then `local.properties` entries of the same names, else blank.
  They are task inputs, so changing them reruns the task.
- `TELEMETRY_CHANNEL` is `release` when the Gradle property `posthog.channel=release` is passed (tag CI only), else
  `dev`.
- A blank key means telemetry is compiled off: `Telemetry` is a no-op object, no Ktor client is created, no file is
  written, and the Privacy switches render disabled with an explanatory banner.
- The key is a PostHog project key (`phc_…`). It is write-only but still extractable from any installer, so it is
  kept out of the repo and out of other people's builds, not hidden from users.
- `.github/workflows/build.yml`: the `build` job's package step passes the three properties only when
  `startsWith(github.ref, 'refs/tags/v')`. Secrets `POSTHOG_KEY` and `POSTHOG_HOST` are added to the repo by the
  maintainer.

### Runtime components (new package `com.programmersbox.scrcpyviewer.telemetry`)

- **`TelemetryConfig`**: key, host, channel from `BuildInfo`; `enabled = key.isNotBlank()`.
- **`InstallId`**: random UUID stored in `<AppDataDir>/telemetry/install-id`. Reset from Settings creates a new one.
  Used as PostHog `distinct_id`. Events are sent with `$process_person_profile: false` so PostHog creates no person
  profiles.
- **`Telemetry`** (Koin single): `track(event, props)` and `captureException(throwable, handled)`. Both return
  immediately. They check the matching consent switch, add common properties, scrub, and append to the queue.
- **Common properties** on every event: `app_version`, `os` (mac/windows/linux), `os_version`, `arch`, `channel`,
  `session_id` (random per launch), `$lib = "scrcpy-vwr"`.
- **`TelemetryQueue`**: append-only JSON Lines file `<AppDataDir>/telemetry/queue.jsonl`, written with
  kotlinx.serialization. Capped at 1,000 events / 1 MB; oldest dropped first.
- **`TelemetrySender`**: Ktor `HttpClient` (CIO engine) with ContentNegotiation and kotlinx JSON. POSTs batches of up
  to 100 events to `{host}/batch/` with `api_key`. Flushes every 30 s, at startup (leftovers from last run), and
  during `AppShutdown` with a 2 s timeout. On failure: exponential backoff up to 10 min; 4xx other than 429 drops the
  batch. Never logs to the UI.
- **`Scrubber`**: replaces the user's home path with `~` (any case, `/` or `\` separators, whole folder only), device serials seen this session and IPv4/IPv6 addresses
  with `<device>` / `<ip>`, and caps strings at 500 characters. Applied to every property value and stack frame.
  Serials are kept in memory only, never written to disk.
- **Exception messages** (decided): `$exception_list[].value` carries the message only for exception types that
  opt in by implementing the `SafeMessage` marker interface (`telemetry/SafeMessage.kt`): types whose message is fixed
  text we wrote, free of anti-goal data (`MirrorStageException`, `UpdateInstallFailed`, `ShellTimeoutException`).
  Every other exception, the app's own types included (several carry paths, serials or adb output), is sent with its
  type and stack frames and an empty value. A unit test asserts a third-party exception's
  message never reaches the queue.
- **Recent events**: the last 50 sent or blocked events kept in memory for Settings › Privacy › "See what was sent
  recently".

### Consent and settings

- `Settings` gains `usageStats: Boolean = false`, `crashReports: Boolean = true`, `privacyPromptSeen: Boolean = false`.
- Switching usage statistics off deletes queued usage events; switching crash reports off deletes queued
  `$exception` events.
- Settings › Privacy (new category): "Share usage statistics" switch, "Send crash reports" switch, "See what was sent
  recently" expandable list, "Anonymous ID" with Reset ID. In a build without a key, both switches show off and
  disabled with the banner "This build has no analytics key, so nothing is sent. Release builds from GitHub include
  it." Entries are searchable through the Settings search like other entries.
- Onboarding: a new last page "Help improve Scrcpy Vwr" with both switches at their defaults. Finishing or skipping
  onboarding sets `privacyPromptSeen`.
- Existing users (onboarding already seen, `privacyPromptSeen` false) see the same page once as a standalone dialog,
  the way the Actions intro works. Not shown in a build without a key.
- Until the user has seen the prompt, defaults apply (crash reports on, usage off).
- Motion (required): switches animate thumb and track; the disabled state and banner animate in and out (expand and
  fade, not snap); the recent-events list expands with rows entering in sequence; the Reset ID value cross-fades;
  the new onboarding page uses the existing onboarding page transition. Matches the mockups in the proposal page.

### Crash capture

- `Thread.setDefaultUncaughtExceptionHandler` installed first thing in `main()`, before Koin. It writes the
  `$exception` event to the queue synchronously, then defers to the previous handler. On the UI thread (the Tao
  event loop thread, marked from the application's composition), which survives a failed main-dispatcher coroutine
  and keeps running the windows, the write is queued instead so the UI never blocks.
- Coroutine scopes the app owns (`AppScope` and window scopes) use a `CoroutineExceptionHandler` that reports.
- Compose window exception handling: uncaught exceptions inside Compose windows are reported the same way.
- Native crashes (best effort, decided): `-XX:ErrorFile` is not set, because packaged JVM flags are fixed at build
  time and cannot name the per-user data dir. On the next launch the app scans the JVM's default locations (the
  working directory and `java.io.tmpdir`) for `hs_err_pid*.log` files whose content mentions
  `com.programmersbox.scrcpyviewer` and whose pid is not the current process. Each is reported as `$exception` with
  `$exception_type = "native_crash"`, the signal name (e.g. `SIGSEGV`), and the top 20 native/Java frame function
  names (scrubbed); no other file content is sent. The file is then deleted, whether or not crash reports are on.
- Handled errors reported with `handled = true`: scrcpy-server fails to start, decoder errors, adb server dies, update
  install fails.
- `$exception` format: `$exception_list` with type, value (message), `mechanism` `{handled, synthetic: false}`, and
  `stacktrace` `{type: "raw", frames}`. Each frame follows PostHog's manual capture format: `platform: "custom"`,
  `lang` (`"java"`, or `"native"` for native crash frames), `function`, `module`, `filename` and `lineno` when known,
  `resolved: true`, `in_app` = package starts with `com.programmersbox`. `$exception_fingerprint` left to PostHog.

### Events (23)

Durations and sizes are bucketed. Label values (stages, tabs, layouts, theme names, enum names) are sent lowercase
snake_case (`side_by_side`, `keep_both`, `dark_mode`); versions and `error_class` (a Java class name) are sent as they are.
"Never" values from the anti-goals are never properties.

| Event | Properties |
|---|---|
| `app_opened` | `first_launch`, `theme_style` |
| `app_closed` | `session_minutes` (bucket), `mirror_windows_open` |
| `onboarding_finished` | `outcome` (finished, skipped), `last_step` |
| `update_installed` | `from_version`, `to_version` |
| `device_connected` | `connection` (usb, wifi, emulator), `android_major`, `devices_connected` |
| `wifi_pairing` | `method` (qr, code, ip, scan), `success` |
| `emulator_started` | `cold_boot`, `success`, `boot_seconds` (bucket) |
| `adb_restarted` | `mirrors_open` |
| `mirror_started` | `connection`, `used_preset`, `advanced_options`, `option_count`, `audio` |
| `mirror_ended` | `minutes` (bucket), `reason` (closed, disconnected, error) |
| `mirror_failed` | `stage` (push, server, decode), `error_class` (the cause's class for a stage failure, `none` without one) |
| `first_frame_time` | `ms` (bucket), `connection` |
| `windows_arranged` | `layout`, `window_count`, `keep_tiled` |
| `input_broadcast_started` | `follower_count` |
| `screenshot_taken` | `after` (thumbnail, copy, markup) |
| `recording_stopped` | `minutes` (bucket) |
| `sheet_tab_opened` | `tab` (actions, apps, shell, files, logcat, links) |
| `quick_action_run` | `action` (built-in id, or `custom`) |
| `file_transfer` | `direction` (pull, push), `size` (bucket), `outcome` (done, cancelled, failed) |
| `preset_saved` | `total_presets` |
| `presets_imported` | `count`, `conflict_choice` |
| `theme_changed` | `style`, `palette`, `mode`, `neon` |
| `settings_opened` | `category` |

Event names live in one `TelemetryEvent` sealed type (or enum plus typed property builders) so a call site cannot
pass free-form strings.

### Testing

- Unit: scrubber (home path, serials, IPs, length cap), queue cap and order, consent filtering and deletion on
  switch-off, batch building, backoff, `$exception` frame mapping, hs_err parsing, no-op behaviour with a blank key.
- Sender tested with Ktor `MockEngine`.
- Settings › Privacy and the consent page: Compose UI tests for switch state, disabled state, recent-events list.
- Manual: run on this Mac with `local.properties` set, confirm events and a forced exception appear in PostHog
  under `channel=dev`.

### Documentation

- README gains a short "Privacy" section: what is sent, what never is, how to turn it off, and that builds from source
  send nothing unless the builder adds their own key.

## Calls made without the maintainer (check these)

- Ktor engine: CIO (pure Kotlin, no extra HTTP stack).
- Settings › Privacy sits between Updates and Help.
- A build without a key skips the consent page and does not set `privacyPromptSeen`, so a later keyed build still asks.
- Tests never send: `jvmTest` runs with a blank key or a fake sender, even on the maintainer's Mac with
  `local.properties` set.
- The telemetry flush on quit runs inside `AppShutdown`'s existing sequence with a 2 s cap, never extending quit past
  that.

## Left to the builder

- Exact Ktor version (latest stable 3.x, at least two weeks old) and engine choice details within CIO.
- Exact bucket edges.
- Where each event's call site sits.
- Koin wiring details and how the Compose window exception handler is hooked under Nucleus Tao.
