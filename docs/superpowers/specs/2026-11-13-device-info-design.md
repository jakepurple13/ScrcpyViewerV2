# Device info tab

Date: 2026-11-13
Status: approved (mockup https://claude.ai/artifact/AEV6zuT1JVS9XRxa4GRaQt, "8 Device info" tab, motion A)

## Goal

The Devices screen's detail pane gets two tabs: **Options** (today's Simple and Advanced views, unchanged) and
**Info** (new). Info shows what a tester wants to know about the selected device without opening a shell: battery,
storage, memory, display, network and software. Battery and memory refresh while the tab shows. "Copy as text" puts
every field on the clipboard for a bug report.

Motion matters as much as the data. Every state change below has its own animation. Nothing snaps.

## Where it lives

- A tab control (`Options | Info`) at the top of the detail pane, above the existing `ViewSwitch`. It shows only
  while a device is selected. Options holds whatever the pane shows today (Simple or Advanced view, footer included).
- The chosen tab is saved in `AppSettings` as `deviceDetailTab` (`Options` default), the same way
  `advancedDeviceOptions` is saved. It applies to every device and survives restarts.
- The tab control uses Material 3 `SingleChoiceSegmentedButtonRow` (as in `ui/settings/StylePicker.kt`), so the
  Glass, Soft and Retro 95 styles restyle it like the other segmented controls. "Info" carries no badge.

## Data

All reads go through the adb socket client (`AdbClient.shell`), never a spawned `adb` process. Two commands, each
one adb round trip. Sections are separated by marker lines so one parse splits them.

### Static command (once per selection, and on Retry)

```sh
echo @@props
for p in ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.build.display.id \
  ro.product.cpu.abi ro.product.manufacturer ro.product.model ro.build.version.oneui; do echo "$p=$(getprop $p)"; done
echo "kernel=$(uname -r)"
echo @@wm
wm size; wm density
echo @@display
dumpsys display | grep -m1 DisplayDeviceInfo
echo @@df
df -k /data
echo @@diskstats
dumpsys diskstats | grep -E '^(App|App Data|Photos|Videos|Audio|Downloads|System|Other) Size:'
echo @@ip
ip -f inet addr show wlan0
echo @@wifi
dumpsys wifi 2>/dev/null | grep -m1 'mWifiInfo '
```

### Live command (every 5 s while Info shows)

```sh
echo @@battery
dumpsys battery
echo @@mem
cat /proc/meminfo
```

### Parsing (pure functions, `info/DeviceInfoParsers.kt`)

Real output from the Galaxy A15 (SM-A156U1, Android 16, One UI 8.5) is checked in under
`composeApp/src/jvmTest/resources/deviceinfo/sm-a156u1/` (network names and MAC addresses replaced). Every parser
is tested against it. Any missing or unparsable field becomes `null` and its row is hidden. A missing section never
fails the whole read.

| Model | Source | Rules |
|---|---|---|
| `BatteryInfo(level, status, power, temperatureC, voltageMv, health)` | `dumpsys battery` | `level`/`scale` → percent. `status` 2 Charging, 3 Discharging, 4 NotCharging, 5 Full, else Unknown. Power source from `AC powered`/`USB powered`/`Wireless powered`/`Dock powered: true`, else None. `temperature` is tenths of °C (292 → 29.2). `voltage` is mV. `health` 2 Good, 3 Overheat, 4 Dead, 5 OverVoltage, 7 Cold, else Unknown. Lines are matched by key, indented or not, first match wins (Samsung repeats `charge counter`). No `level` → null battery. |
| `MemoryInfo(totalKb, availableKb)` | `/proc/meminfo` | `MemTotal`, `MemAvailable` (fall back to `MemFree` + `Cached` when `MemAvailable` is missing). Used = total − available. |
| `StorageInfo(totalBytes, usedBytes, breakdown)` | `df -k /data`, diskstats | Total and used from the `df` data row (1K blocks). `breakdown` is `StorageBreakdown(apps, media, system, other)` in bytes from diskstats: apps = App Size + App Data Size; media = Photos + Videos + Audio + Downloads; system = System Size; other = Other Size. Null when diskstats gave none of them. |
| `DisplayInfo(widthPx, heightPx, densityDpi, overrideSize, overrideDensity, refreshHz)` | `wm size`, `wm density`, display line | `Physical size: WxH`, optional `Override size: WxH`; same for density. `refreshHz` from `renderFrameRate X`, else `refreshRate X`, else `fps=X` on the DisplayDeviceInfo line. Smallest width dp = min(w, h) × 160 / density, using the override values when present. |
| `NetworkInfo(ssid, ipAddress, linkMbps)` | `ip`, wifi line | IP from `inet a.b.c.d/`. SSID from `SSID: "…"` (`<unknown ssid>` → null). Link speed from `Link speed: NMbps`. |
| `SoftwareInfo(androidVersion, sdk, securityPatch, buildId, abi, manufacturer, model, oneUiVersion, kernel)` | props section | `key=value` lines. `ro.build.version.oneui` 80500 → "8.5" (major = v / 10000, minor = v / 100 % 100). Empty values → null. |

`StaticInfo(software, display, storage, network)` and `LiveInfo(battery, memory)` hold the results.

### Reader

`open class DeviceInfoReader(adb: AdbClient, serial: String)` in `info/DeviceInfoReader.kt` with
`open suspend fun readStatic(): StaticInfo` and `open suspend fun readLive(): LiveInfo`. Each call has a 15 s timeout
and throws `IOException` on adb failure. Tests fake it like `DeviceApps` is faked today.

## State

`DeviceInfoViewModel` (Koin `viewModel`, like `DevicesViewModel`):

- Inputs: the selected device from `DeviceSelection`, and `setActive(Boolean)` called by the Info tab while it's on
  screen (`DisposableEffect`).
- While active and the selected device's state is `Device`:
  - Static info loads once per selected serial. The result is cached per serial for the session, so switching back
    shows it at once and refreshes it quietly.
  - Live info loads immediately, then every 5 s. Polling stops when the tab goes away, the device changes, or the
    device leaves.
- `DeviceInfoUiState(serial, static: Load<StaticInfo>, live: Load<LiveInfo>, liveUpdatedAt: Long?)` where
  `Load` is `Loading | Ready(value) | Failed(message)`. A failed live poll keeps the last good values and retries on
  the next tick. It shows Failed only when there are no values yet.
- Unauthorized or offline device: the pane shows the same message the device row does ("Accept the USB debugging
  prompt on the device" / "Device is offline") and nothing polls.
- `retry()` reloads static and live.

## UI (`ui/devices/info/DeviceInfoPane.kt`)

Layout follows the mockup: a header row with the device name, then cards in an adaptive grid (min column 220 dp,
12 dp gaps) inside a vertical scroll, then a footer row with "Updated N s ago" and "Copy as text". The footer stays
at the bottom of the pane.

Cards, each a filled `Card` with a small uppercase title and icon:

1. **Battery**: a 64 dp ring (`CircularProgressIndicator`-style arc drawn in Canvas) and a big percent. Below it,
   the state ("Charging · USB", "Full · plugged in", "On battery"), temperature, health and voltage. The ring uses
   the error color under 20% while not charging, otherwise the success green the app already uses for Ready.
2. **Storage**: "18.6 of 113.2 GB used" (decimal GB, one decimal, from 1K blocks × 1024). A stacked bar (apps / media / system / other)
   with a legend. Without a breakdown, the bar has one used segment.
3. **Memory**: "2.8 of 3.7 GB in use" and a bar. Small caption "Refreshes every 5 s".
4. **Display**: resolution (monospace), density, refresh rate, smallest width (`sw384dp`). When an override is
   active, the value reads "720 × 1560 (set, device is 1080 × 2340)".
5. **Network**: Wi-Fi name, IP address (click copies, with the existing copy morph), link speed. With no Wi-Fi it
   says "Not on Wi-Fi".
6. **Software**: Android "16 (API 36)", One UI (Samsung only), security patch, build (monospace), ABI, kernel
   (monospace).

Copy as text uses `CopyButton` from `ui/common/CopyButton.kt` and produces plain lines, for example:

```
Galaxy A15 5G (SM-A156U1) · R5CX12GDWPZ
Battery: 100%, full, USB, 29.2 °C, good
Storage: 18.6 of 113.2 GB used
Memory: 2.8 of 3.7 GB in use
Display: 1080 × 2340, 450 dpi, 90 Hz, sw384dp
Network: Office-5G, 192.168.1.36, 351 Mbps
Android 16 (API 36), One UI 8.5, patch 2026-07-05
Build: BP4A.251205.006.A156U1UESCEZG1, arm64-v8a, kernel 5.15.189-android13-8-33641424
```

## Motion

Use the easings in `ui/common/Motion.kt` (`EmphasizedDecelerate`, `EmphasizedAccelerate`). Respect the app's
reduced-motion setting wherever other screens do (check how `DeviceMotion.kt` and the launch animation read it;
when reduced, every change below becomes an instant swap or a 150 ms fade).

| Change | Motion |
|---|---|
| Options ↔ Info (motion A, picked by the user) | Horizontal shared axis. Outgoing content slides 36 dp toward the side being left and fades out over 160 ms (`EmphasizedAccelerate`). Incoming content starts after that, slides in 36 dp from the other side and fades in over 320 ms (`EmphasizedDecelerate`). Options is the left tab, Info the right. One `AnimatedContent` keyed on the tab. The segmented control's own selection animates as Material does. |
| Selecting another device while Info shows | Vertical shared axis on the cards, matching the rail: out 14 dp up (moving to a device lower in the list) or down, fade 160 ms; in from 18 dp the other way, 340 ms. The device name in the header moves with the cards. |
| First load | Card placeholders (same size as the cards, a soft pulse of the surface color, 1.2 s loop) cross-fade into the real cards over 300 ms, staggered with `staggeredEntrance` from `MotionExtras.kt`. |
| Battery ring and bars | The ring sweep and every bar segment grow from 0 to their value when the card appears (700–800 ms, `EmphasizedDecelerate`), and animate between values on each refresh. |
| Changing numbers (battery %, memory in use, "Updated N s ago") | `AnimatedContent` per number: the old value slides up and fades, the new one comes up from below (200 ms), with tabular figures so the width doesn't jump. |
| Failed → Retry → Ready | The error message and Retry button fade out. Placeholders fade in, then the cards cross-fade in as above. |
| Device becomes unauthorized or offline | The cards fade and shrink to 96% (200 ms) and the message fades in. The reverse when it comes back. |
| Copy as text | The existing copy morph (icon to check, "Copied"). |

## Errors and edge cases

- Static read fails: the cards area shows "Couldn't read device info" with the reason and a Retry button.
- Live read fails after a success: keep the last values. The footer shows "Last updated N s ago" in the warning
  color until a poll succeeds.
- Emulators: no `wlan0` or no diskstats breakdown is normal. Those rows hide, nothing errors.
- A device selected over Wi-Fi and USB at once is two serials. Each has its own cache.
- Polling never runs for a device that isn't selected, or while the Options tab shows.

## Tests

- `info/DeviceInfoParsersTest`: every parser against the SM-A156U1 fixtures, plus hand-written cases: status codes,
  missing `MemAvailable`, override size and density, `<unknown ssid>`, empty sections, One UI missing, `df` with a
  different mount column.
- `info/DeviceInfoReaderTest`: the section splitter and both commands against a fake `AdbClient` (see
  `AdbClientTest`/`DeviceAppsTest` for the pattern).
- `ui/devices/info/DeviceInfoViewModelTest`: virtual time. Polls every 5 s only while active. Stops on device change
  and on deactivate. Caches static per serial. Not-ready device does not poll. Failed live poll keeps old values.
- `DevicesScreenshotTest`: Info tab with ready data, loading placeholders, failed state, and the Options/Info tab
  control.
- Manual: run the app against the Samsung phone (`cdm acquire --android --real --device R5CX12GDWPZ`), check every
  card against `adb shell` output, unplug USB to see the charging state change within 5 s.

## Out of scope

Cellular info, multiple displays, CPU usage, a detail view per card, Info in the mirror window.

## Risks

- The narrow-window layout work (`worktree-resize-text`) also edits `DevicesScreen.kt` and the list-detail panes.
  Keep this feature's change to `DevicesScreen.kt` small: one tab row plus an `AnimatedContent` around the existing
  `ViewSwitch`. Everything else lives in new files.
- `dumpsys` formats vary by OEM and version. The parsers match keys, not line positions, and hide what they can't
  read.
