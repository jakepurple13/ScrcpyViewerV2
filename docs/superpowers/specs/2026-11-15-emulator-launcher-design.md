# Emulator launcher

Date: 2026-11-15
Status: approved 2026-11-15 (mockup https://claude.ai/artifact/4Qo5e7z3kTrNAsNpzAf9MQ, "11", placement A; minus Wipe data and Show in Finder)

## Goal

Start, watch and stop Android emulators from the Devices screen, without Android Studio. Emulators start headless
and are mirrored inside Scrcpy Vwr like any other device.

## Decisions already made with the user

- Placement A: a collapsible **Emulators** section in the device column, under the connected devices.
- Emulators start with `-no-window`; Scrcpy Vwr is the window. "Show emulator window" (per emulator, off by default)
  turns the emulator's own window back on.
- "Mirror when ready" is per emulator and off by default.
- Stop works on any running emulator, including ones started outside Scrcpy Vwr.
- Every state change animates; nothing snaps. Reduced motion is respected through `systemReducesMotion()`, passed in
  as a `motion: Boolean` parameter.
- Quitting Scrcpy Vwr leaves the emulators it started running; they list as Running next launch (user, 2026-11-15).
- A boot not done in 4 minutes is stopped and shows Failed (user, 2026-11-15).
- The new copy marked "new copy" below is approved as written (user, 2026-11-15).
- Wipe data and Show in Finder are deferred to a follow-up (user, 2026-11-15).

## Where things are found

**SDK**, first match wins; a folder counts when it exists:

1. `Settings.androidSdkPath` (set by "Choose SDK folder" / "Change").
2. `ANDROID_HOME`, then `ANDROID_SDK_ROOT`.
3. The platform default: `~/Library/Android/sdk` (macOS), `%LOCALAPPDATA%\Android\Sdk` (Windows), `~/Android/Sdk`
   (Linux).

An app started from Finder or the Start menu usually has no `ANDROID_HOME`, so step 3 is the common case.

**Emulator binary**: `<sdk>/emulator/emulator` (`emulator.exe` on Windows). Its version for the SDK line is
`Pkg.Revision` in `<sdk>/emulator/source.properties` (this Mac: 37.3.9). No process is run to find it.

**AVDs**: the `*.ini` files in the AVD home: `ANDROID_AVD_HOME`, else `$ANDROID_USER_HOME/avd`, else
`~/.android/avd`. Each `<id>.ini` has `path=` to `<id>.avd/`; an ini whose folder is missing (this Mac has three,
left by deleted AVDs) is skipped. From `<id>.avd/config.ini` (keys may be written `key=value` or `key = value`):

| Shown | Source |
|---|---|
| Name | `avd.ini.displayname`, else the id (`heads_up_api34`) |
| API | the `android-<n>` part of `image.sysdir.1` (`system-images/android-36.1/google_apis/arm64-v8a/` → 36). Not `target=` in the outer ini: it reads `android-0` for 36.1 images |
| Android version | a table from API: 36 → 16, 35 → 15, 34 → 14, 33 → 13, 32 → 12L, 31 → 12, 30 → 11, 29 → 10, 28 → 9, 27 → 8.1, 26 → 8.0, 25 → 7.1, 24 → 7.0, 23 → 6.0, 22 → 5.1, 21 → 5.0; unknown APIs show no version |
| Kind (icon) | Watch when `tag.id` contains `wear`; TV when it contains `tv`; Tablet when `hw.device.name` contains `tablet` or the shorter screen side (`hw.lcd.width`/`height` at `hw.lcd.density`) is at least 600 dp; else Phone |
| Size | the `.avd` folder's size on disk (`du -sk` on macOS/Linux, sum of file sizes on Windows), read in the background; "8.2 GB" (one decimal, GB or MB) |

The list is read when the Devices screen opens, after a new SDK folder is chosen, and after a start or stop. Sizes
are measured on those reads except after a start (as built: a running emulator's size need not refresh; the Stop
measures again), so a Start never runs `du`.
Rows sort by name (case-insensitive) and never reorder while their state changes.

**Running emulators**: every connected device whose serial is `emulator-<port>` is matched to its AVD once per
connection with `adb -s <serial> emu avd name` (first line of the output; `OK` is dropped). An emulator whose AVD
isn't in the list (Gradle managed devices) only shows under the connected devices.

## Device column layout

Today the column is: header (device summary, Restart adb), device rows (scrolling), "Pair over Wi-Fi" button. The
scrolling area now holds the device rows (or "No devices…" text) and then the Emulators section, so both scroll
together; the header and "Pair over Wi-Fi" stay where they are. No "Connected" label is added: the existing header
already names the devices above.

Connected device rows change in two ways:

- A row whose serial starts with `emulator-` gets an **Emulator** tag after its name (labelSmall, surfaceVariant
  pill, like the mockup's `USB` tag).
- An emulator the launcher is still booting is left out of the connected rows. It enters (the existing
  `AnimatedDeviceItem` arrival: slide, expand, glow) once it is ready, instead of showing as "offline" with the
  cable hint while it boots.

As built (follow-ups, 2026-11-15), emulator rows only; a real device's row, menu, hints and selection are unchanged:

- **Title**: a connected emulator whose AVD is known (launcher state Booting/Running/Stopping, ours or one started
  elsewhere once matched) is titled with the AVD's display name ("heads_up_api34", "Pixel 9") instead of the system
  model ("sdk_gphone64_arm64"); the serial subtitle is unchanged. Until matched it keeps the existing title; the
  change crossfades (250 ms, the row's height following; at once under reduced motion).
- **Menu**: an emulator's right-click menu has Mirror only, no "Connect over Wi-Fi".
- **Offline hint**: an offline emulator row shows "Emulator offline. Stop and start it again from Emulators." instead
  of the cable hint.
- **Selection**: with no user choice, an emulator never takes the default over from another ready device already
  shown as selected (one that finishes booting, or one started elsewhere, does not move the selection to it); with
  no other device it is selected as before.

## Emulators section

**Header**: "Emulators", a count pill with the number of AVDs ("0" when no SDK), then a chevron button on the right
(tooltip "Collapse" / "Expand"). Clicking the header or the chevron folds the section. Open/closed is saved
(`Settings.emulatorsExpanded`, default true).

**Rows** (same shape as device rows: 12 dp corners, 10 dp padding), left to right: a kind icon (Phone, Tablet,
Watch, TV), name over a subtitle, the state controls, and a **More** (⋮) icon button.

| State | Subtitle | Controls |
|---|---|---|
| Stopped | "API 36 · Android 16 · 8.2 GB" (parts missing are left out) | **Start** (tonal, play icon) |
| Booting | "Starting emulator…", "Booting Android…" or "Waiting for adb…", with a 3 dp progress track below | chip with spinner and elapsed seconds "12s", then a **Stop** icon button |
| Running | the serial, monospace: "emulator-5554" | chip with dot "Running", then **Stop** |
| Stopping | the serial | chip with spinner "Stopping" |
| Failed | the reason in error colour, for example "Emulator exited (code 1). Not enough disk space for the data image." | chip "Failed", then **Retry** (tonal, redo icon) |

Chip colours follow the mockup: Booting uses the warn colours (`warnColor()`), Running the ok colours (primary, as
the connected "Ready" chip), Stopping surfaceVariant, Failed errorContainer.

The progress track has no real progress to show. It fills towards 96 % over the expected boot time (the last
successful warm boot of this AVD, `EmulatorPrefs.lastBootMillis`, else 30 s; 1.6× that for a cold boot), easing out so it
slows near the end, then fills to 100 % on ready.

**Below the rows**, the SDK line: folder icon, the SDK path (monospace, home shown as `~`), "· emulator 37.3.9",
and a **Change** text button (opens the folder picker).

**More menu** (Material `DropdownMenu` from the ⋮ button):

- A label with the AVD id (`Pixel_9_API_36`).
- **Cold boot** (snowflake icon), only when Stopped or Failed: starts with `-no-snapshot-load`.
- **Mirror when ready** checkbox.
- **Show emulator window** checkbox. A change applies from the next start.

The checkboxes are saved per AVD in `Settings.emulatorPrefs` and the menu stays open when one is toggled.

Deferred by the user (2026-11-15) to a follow-up: the mockup's **Show in Finder / Show in folder** and **Wipe data…**
menu items and the wipe dialog. Leave the menu without a divider or those items.

## Empty and error states

Each replaces the rows and SDK line inside the section (the header stays):

- **No SDK**: bold "No Android SDK found", then "Scrcpy Vwr looked in ANDROID_HOME, ANDROID_SDK_ROOT and
  ~/Library/Android/sdk. Install Android Studio, or choose the SDK folder." (the last folder is the platform default
  from above), and a tonal **Choose SDK folder** button (folder icon). In a dashed-outline box.
- **SDK without the emulator** (new copy, not in the mockup): bold "Android Emulator isn't installed", then "Found
  the SDK at <path>, but not its emulator. Install Android Emulator in Android Studio's SDK Manager, or choose
  another SDK folder.", and **Choose SDK folder**.
- **No AVDs** (new copy): "No emulators yet. Create one in Android Studio's Device Manager." with the SDK line
  below.

A chosen folder that isn't an SDK (no `emulator` folder and no `platform-tools`) is still saved; the section then
shows the matching state above, so the user sees why.

## Starting

`<sdk>/emulator/emulator -avd <id> -port <port> [-no-window] [-no-snapshot-load]`, with `ANDROID_SDK_ROOT=<sdk>` and
`ANDROID_HOME=<sdk>` in its environment (an app opened from Finder has neither), stdin empty, stdout and stderr both
written to `<DataDir>/emulator-logs/<id>.log` (a file, not a pipe, for the reason in `AdbClient.runAdb`). The
process is started with `ProcessBuilder` and kept, so its exit is seen.

`<port>` is the lowest even port from 5554 to 5682 with no `emulator-<port>` connected and both `<port>` and
`<port>+1` free on 127.0.0.1. The serial is then known up front: `emulator-<port>`.

Boot stages, checked every second against the device tracker and adb:

1. **Starting emulator…** until adb lists the serial (in any state).
2. **Booting Android…** until `adb -s <serial> shell getprop sys.boot_completed` returns `1` (each check times out
   after 5 s; an error just means "not yet").
3. **Waiting for adb…** until the tracker shows the serial as `device` with its info read (the model and Android
   version the connected row and mirror window need).

Then the row is Running, `lastBootMillis` is saved (warm boots only; a cold boot does not change it), and the snackbar says "<name> is ready." With "Mirror when
ready" on, it says "<name> is ready. Opening its mirror window…" and `MirrorRequests.request(serial)` opens it. The
window grows out of the emulator's row: the row carries `Modifier.mirrorOrigin(serial)` while Running, and the
launcher calls `MirrorOrigins.buttonOnScreen(serial)?.let(MirrorOrigins::record)` just before the request (when
the row isn't on screen the window opens the way a double-click opens one).

The launcher is app-wide (a Koin singleton on `AppScope`), so a boot keeps going and still mirrors while another
screen is showing. Its snackbars are collected in `App.kt`, next to the recording results.

## Failures

| Case | Result |
|---|---|
| The process exits before ready | Failed: "Emulator exited (code <n>). <reason>", where the reason is the text after the last `ERROR \|`, `FATAL \|` or `PANIC:` line of the log (left out when there is none). Snackbar "<name> didn't start." with action **Show log** (`openExternally` on the log file) |
| The process can't start | Failed: "Couldn't start the emulator: <message>" |
| Not ready after 4 minutes | The emulator is stopped (as Stop below), then Failed: "Android didn't finish booting in 4 minutes." and the same snackbar |
| The AVD is already running elsewhere but not yet listed by adb | The emulator exits with its own error ("Running multiple emulators with the same AVD…"), shown as the reason |
| Start on an AVD already running | Not offered: the row shows Running |

Retry starts again the same way (Cold boot if the failed start was cold).

## Stopping

- **Booting, not yet listed by adb**: our process is destroyed (forcibly after 5 s); the row goes straight to
  Stopped.
- **Listed by adb** (ours or started elsewhere): Stopping, `adb -s <serial> emu kill` (10 s timeout). Stopped when
  the serial leaves the tracker. If it is still listed after 20 s, our own process is destroyed; for one started
  elsewhere the row returns to Running with the snackbar "Couldn't stop <name>." (new copy).

Open mirror windows of a stopped emulator close the way they do when a phone is unplugged.

An emulator started elsewhere and stopped elsewhere simply follows the tracker: Running while listed, Stopped when
gone.

Quitting Scrcpy Vwr leaves running emulators running (the process is not a child that dies with the app); next
launch lists them as Running and they can be stopped from there. A boot still in progress at quit carries on
unwatched.

## Settings

Added to `Settings`, not shown on the Settings screen:

- `androidSdkPath: String? = null`
- `emulatorsExpanded: Boolean = true`
- `emulatorPrefs: Map<String, EmulatorPrefs> = emptyMap()`, keyed by AVD id;
  `@Serializable EmulatorPrefs(mirrorWhenReady: Boolean = false, showWindow: Boolean = false, lastBootMillis: Long? = null)`

## Motion

All tweens use the app's emphasized easings (`EmphasizedDecelerate` in, `EmphasizedAccelerate` out). Constants
live in `ui/devices/emulators/EmulatorMotion.kt`.

| Change | Motion |
|---|---|
| Section folds / unfolds | chevron turns −90° over 350 ms; body expands/shrinks vertically + fades over 400 ms |
| Row controls change (Start ↔ booting chip + Stop ↔ Running ↔ Stopping ↔ Failed + Retry) | `AnimatedContent`: new controls fade up 6 dp over 320 ms, old fade out 150 ms; row width changes animate (`animateContentSize`) |
| Subtitle changes (details ↔ stage ↔ serial ↔ reason, and each stage) | crossfade with a 4 dp upward slide, 200 ms |
| Chip colour | `animateColorAsState`, 300 ms |
| Progress track | expands in (200 ms) when booting starts; its fill moves with a 300 ms linear tween each second; on ready it fills to 100 %, then shrinks away (300 ms) |
| Elapsed seconds | the number ticks in place, like a clock (a deliberate exception: a moving number every second distracts) |
| Count pill number | old number slides up and out, new slides in from below, 200 ms |
| Emulator joins / leaves the connected rows | the existing `AnimatedDeviceItem` arrival and departure |
| Empty / error state ↔ rows | crossfade + height change, 300 ms |
| More menu, checkboxes | Material's own (`DropdownMenu` scale/fade, `Checkbox` tick) |
| Snackbars | the app's existing snackbar motion |
| Reduced motion | every change is instant or a 150 ms fade; the spinner still turns |

## Tests

- **SDK lookup** (`AndroidSdkTest`): override first, then each env var, then each platform default; a folder
  without `emulator/` gives the "no emulator" result; missing folders are skipped; emulator version from
  `source.properties`; AVD home from `ANDROID_AVD_HOME`, `ANDROID_USER_HOME`, default. Temp dirs only, paths
  Windows-safe.
- **AVD catalog** (`AvdCatalogTest`): `key=value` and `key = value`; API from `image.sysdir.1` including `36.1`;
  the version table; kind from `tag.id`, device name and screen size; display name fallback; an ini with a missing
  folder is skipped; sort by name; size formatting.
- **Launcher** (`EmulatorLauncherTest`, fake `ProcessStarter`, fake `AdbClient`, a test device flow, virtual time):
  command and env for Start, Cold boot and Show emulator window; port picking skips connected and bound ports;
  the three stages in order; ready saves `lastBootMillis` and emits the right notice; Mirror when ready requests a
  mirror, off does not; process exit → Failed with the parsed reason; 4-minute timeout kills and fails; Stop while
  not listed destroys the process; Stop while listed sends `emu kill` and waits for the serial to go; external
  emulator mapped by `emu avd name` shows Running and stops; Stop that never finishes returns to Running.
- **View model** (`EmulatorsViewModelTest`): no SDK / no emulator / no AVDs states; Choose SDK folder saves the
  path and reloads; toggles save prefs; expanded is saved; booting serials are hidden from the connected rows.
- **Motion** (`EmulatorSectionTest`, `SceneDriver`, `motion = true` passed explicitly, and `false`): fold, control
  swap, subtitle swap, chip colour, progress track and count change each move over several frames with no
  single-frame jump; without motion each lands in one frame or a 150 ms fade.
- **Screenshots** (`DevicesScreenshotTest`, `-Pscreenshots`): section with Stopped, Booting, Running, Failed rows
  (light, dark); collapsed; No SDK; More menu open; narrow list (240 dp) with long names.

## Out of scope

Creating, editing, renaming or deleting AVDs; snapshots (save, load, list); emulator extended controls (location,
battery, sensors); the tray menu; starting emulators from a mirror window; an emulator list in Settings; changing
the emulator's command-line flags beyond Cold boot and Show emulator window.
