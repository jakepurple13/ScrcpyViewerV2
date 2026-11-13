<p align="center">
  <img src="composeApp/src/jvmMain/resources/icon.png" width="128" alt="Scrcpy Vwr icon">
</p>

<h1 align="center">Scrcpy Vwr</h1>

<p align="center">
  A desktop app for mirroring and controlling Android devices, built on the
  <a href="https://github.com/Genymobile/scrcpy">scrcpy</a> protocol with Compose Multiplatform.
</p>

---

## What it is

Running [scrcpy](https://github.com/Genymobile/scrcpy) usually means typing a command in a terminal and remembering
which flags you want. Scrcpy Vwr replaces that with a desktop app: a live list of connected devices, a searchable
list of every scrcpy option as checkboxes, and saved presets you can share.

It is not a wrapper around the `scrcpy` program. The app includes its own scrcpy client. It talks to `scrcpy-server`
directly, decodes the video with FFmpeg, and draws it in a window the app owns. scrcpy-server and adb come bundled,
so you do not need to install scrcpy, adb or FFmpeg.

## Features

### Devices
- **Live device list.** Uses adb's `track-devices` stream, so devices appear and disappear as they connect. You never
  need to press refresh.
- **Device states.** Shows each device as ready, unauthorized (accept the prompt on the phone), or offline.
- **Simple and advanced views.** Clicking a device opens a simple pane: connection, Android version, serial, the
  chosen preset and a large **Mirror** button. **Advanced options** opens the full options pane. The app remembers
  which view you used.
- **Info tab.** Battery, storage, memory, display, network and software details for the selected device. Battery and
  memory refresh every 5 seconds. **Copy as text** copies every field, for bug reports.
- **Wi-Fi debugging.** Pair a device with a QR code, a pairing code, or an IP address, or scan the network for
  devices. You can also switch a USB device to Wi-Fi with one click. Devices on the network are found through mDNS,
  with a `dns-sd` fallback on macOS.
- **Restart adb** from the device list when the adb server gets stuck. If mirror windows or Wi-Fi devices would be
  lost, the app asks first.
- **Emulators.** A collapsible Emulators section under Connected lists your Android Virtual Devices. Start one with no
  emulator window and mirror it in Scrcpy Vwr, or stop one, including emulators started elsewhere.

### Options and presets
- **Every scrcpy 5.0 option** as a checkbox. Options that take a value add a text field or dropdown.
- **Search and filter** options by group: Video, Audio, Control, Device, Window, Recording, Camera, Virtual display
  and Connection.
- **Generated command.** Shows the equivalent `scrcpy -s <serial> …` command line, with a copy button.
- **Per-device options.** Each device remembers its own options.
- **Presets.** Save, edit, duplicate, delete and apply presets, and set one as the default for new devices.
- **Import and export** presets as `.scrcpypresets.json` files or pasted JSON. When an imported name already exists,
  you choose Replace, Keep both or Skip.

### Mirroring
- **Several mirror windows at once**, one per device.
- **Mouse and keyboard input**, including scrcpy's UHID keyboard and mouse modes.
- **Navigation buttons:** Back, Home, Recents, Volume up/down, Rotate and Power.
- **Audio playback** from the device.
- **Screenshots and screen recordings**, saved to a capture folder you choose in Settings. After a screenshot, a
  thumbnail shows for a few seconds. From it you can copy the image as PNG or export a GIF.
- **Zoom** from 25% to 400%, plus fit to window.
- **Drag and drop files.** Drop an APK to install it. Other files go to `/sdcard/Download/`.
- **Reconnect.** If a device disconnects, the window keeps the last frame and offers Reconnect when the device returns.
- **Send input to other windows.** One mirror window can lead. Its taps, swipes, scrolls, typing, nav buttons and
  quick actions also go to the other mirror windows you pick. Positions map by percent of each device's screen.
- **Arrange mirror windows.** An Arrange button in the title bar (and in the menu bar icon) tiles open mirror windows:
  As placed, Grid, Side by side, Focus this window or Cascade. Windows keep their device's shape. **Keep tiled**
  re-tiles them as windows open and close.

### Quick actions sheet
A bolt button in each mirror window's side toolbar opens a sheet beside the device screen. The window grows to make
room, so the mirrored image keeps its size. Drag the sheet's edge to resize it. It has these tabs:

- **Actions.** Quick-settings tiles: dark mode, Wi-Fi, airplane mode, show taps, auto-rotate, Developer options and
  Reboot. Your own custom actions appear below them.
- **Apps.** Every launchable app, with icon, name, package and version. Search, filter to user or system apps, and
  Launch, Force stop, Uninstall or Copy package name.
- **Shell.** An `adb shell` console with live output, Stop and Ctrl+C, a filter, Pause, Copy and command history.
- **Files.** A file browser for the device's shared storage. Pull, push, move, rename, delete and make folders.
  Drag files in from your computer, or drag rows out to Finder or Explorer. On macOS and Windows the copy starts after
  you drop, with no wait. A transfers tray shows progress, speed and Cancel.
- **Links.** Open a URL or deep link on the device. History is kept per device, and starred links are shared by all
  devices.
- **Logcat.** Live `adb logcat` with Android Studio's filter language (`tag:`, `package:`, `level:`, `is:crash`,
  regex and more), level colors, folded stack traces and autocomplete. **Show in Logcat** in the Apps tab filters to
  one app. **Open in new window** moves Logcat to its own window, with an always-on-top pin.

### Custom actions
- **Actions page** in the main window. Build a **Toggle** (with a command that reads its state) or a **Run once**
  action, with a name, an icon and `adb shell` commands.
- **Variables.** `{variables}` ask for a value when the action runs: text, a list, or an app picked from the device.
- **Output** goes to a toast, the Shell tab or nowhere. Actions can ask for confirmation first.
- **16 templates** such as Grant permission, Start activity, Send broadcast and Stay awake.
- **Import and export** actions as JSON. The import preview shows every command and never runs anything.
- **AI skill.** Settings › AI installs a skill into Claude Code, Gemini CLI, Codex, Grok CLI or a folder you pick.
  The AI tool can then write actions that appear in the app within about 2 seconds, in a "Made by AI" group.

### Appearance
- **Light and dark mode**, with 11 color palettes, neon palettes, a Custom palette editor and a pure black option.
- **Styles:** Material, Glass (see-through panes; on macOS the real desktop blurs behind them), Soft (neumorphic) and
  Retro 95.
- **Motion everywhere.** Theme changes reveal in a circle from the control you clicked. Mirror windows grow out of the
  Mirror button and shrink back into it when closed. The main window has a launch and quit animation (Icon unfold,
  Screen on or Off). All motion follows your OS reduce-motion setting.
- **Menu bar / system tray icon.** Its menu lists connected devices: mirror one, or bring its mirror window forward.
  It also has Open Scrcpy Vwr, Settings… and Quit. You can turn it off in Settings › Appearance.

### App
- **Keyboard shortcuts.** Settings › Keyboard shortcuts lets you record shortcuts for Send input to other windows and
  each Arrange layout. None are set by default.
- **Searchable settings**, grouped into Appearance, Mirroring, Keyboard shortcuts, adb, AI, Updates and Help.
- **Updates.** Checks on launch and every 6 hours. It can also download updates in the background and install them
  when you quit.
- First-run welcome guide. You can reopen it from Settings.
- Uses the bundled adb by default, or a custom adb path you choose.

## Download

Installers for each release are on the [Releases](https://github.com/jakepurple13/ScrcpyViewerV2/releases) page:

| Platform | File   |
|----------|--------|
| macOS    | `.dmg` (the `.zip` is for the in-app updater) |
| Windows  | `.exe` (NSIS installer, per user) |
| Linux    | `.deb` |

The app checks for updates when it opens and can install them itself (Settings > Updates).

> **macOS:** the builds are not signed. The first time you open the app, right-click it and choose **Open**. When
> macOS asks for Local Network access, allow it. Wi-Fi debugging does not work without it.

## Getting started

1. On the Android device, turn on **Developer options**, then turn on **USB debugging**.
2. Connect the device with USB, or pair it over Wi-Fi from the Devices screen.
3. Accept the "Allow USB debugging" prompt on the device.
4. Double-click the device. Or select it, change options, and press **Mirror**.

## Tech stack

| Area           | Technology                                                                                      |
|----------------|-------------------------------------------------------------------------------------------------|
| Language       | Kotlin 2.3 (JVM target only)                                                                    |
| UI             | Compose Multiplatform 1.12 for Desktop, Material 3, Material Icons Extended                     |
| Navigation     | Jetpack Navigation 3 and Material 3 Adaptive (list-detail panes)                                |
| Windows        | [Nucleus](https://nucleusframework.dev) Tao window backend (no AWT) with the Nucleus Material title bar |
| Video decoding | FFmpeg 8.1 through [JavaCPP presets](https://github.com/bytedeco/javacpp-presets) (`org.bytedeco:ffmpeg`) |
| Device link    | Bundled adb (Android platform-tools) and scrcpy-server 5.0, using the adb wire protocol over sockets |
| Concurrency    | kotlinx.coroutines                                                                              |
| Serialization  | kotlinx.serialization (JSON settings and presets)                                               |
| QR codes       | [QRose](https://github.com/alexzhirkevich/qrose) (Wi-Fi pairing)                                |
| DI             | [Koin](https://insert-koin.io) 4.2 with the Koin compiler plugin                                |
| App icons      | [apk-parser](https://github.com/hsiafan/apk-parser) (reads icons out of APKs for the Apps tab)  |
| Native calls   | [JNA](https://github.com/java-native-access/jna) (macOS desktop blur, file promise drags, Windows virtual-file drags) |
| Tray icon      | Nucleus ComposeNativeTray (macOS menu bar / Windows and Linux system tray)                       |
| Runtime        | JDK 25, the Gradle daemon JVM (compiled with JDK 21 toolchain); no JetBrains Runtime needed     |
| Build          | Gradle (Kotlin DSL) with version catalog, [Nucleus](https://nucleusframework.dev) packaging (electron-builder) |
| Updates        | Nucleus updater (`latest-*.yml` metadata on GitHub releases)                                     |
| CI             | GitHub Actions: tests on macOS, Windows and Linux; installers built and released on `v*` tags |

## How it works

1. **Device tracking.** The app connects to the adb server on `localhost:5037` and sends `host:track-devices-l`. If no
   server is running, it starts one with the bundled adb.
2. **Starting a session.** For each mirror window, the app pushes `scrcpy-server` to the device and sets up an
   `adb reverse` tunnel (or `adb forward` as a fallback). It then launches the server with `app_process`, passing your
   options as server arguments.
3. **Video.** The app reads the H.264/H.265/AV1 stream from the video socket and decodes it with FFmpeg. It then draws
   each frame in a Compose canvas, scaled to keep the aspect ratio.
4. **Audio.** The app plays the audio socket through Java Sound.
5. **Control.** Mouse, keyboard and scroll events become scrcpy control messages. Window coordinates are mapped to
   device coordinates using the current frame size.

Closing a mirror window closes its sockets. This stops the server on the device and removes the tunnel.

## Building from source

Requirements: JDK 21 or newer. Gradle downloads JDK 25 automatically for its daemon, and the app runs and is
packaged on that JDK.
Packaging also needs Node.js (24 in CI): the Nucleus plugin builds the installers with electron-builder.

```bash
# Run the app (downloads scrcpy-server and adb into composeApp/bundled on first run)
./gradlew :composeApp:run

# Run the unit tests
./gradlew :composeApp:jvmTest

# Screenshot tests (slow, offscreen rendering)
./gradlew :composeApp:jvmTest -Pscreenshots

# Real-device tests (needs attached devices)
./gradlew :composeApp:jvmTest -PdeviceTests -PdeviceSerials=<serial1,serial2>

# Build an installer for the current OS
# (macOS: DMG + ZIP, Windows: NSIS .exe, Linux: .deb, each with latest-*.yml)
./gradlew :composeApp:packageDistributionForCurrentOS
```

The `downloadBundledTools` task fetches scrcpy-server v5.0 (checked against its SHA-256) and the latest Android
platform-tools adb for each OS.

## Project layout

```
composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/
├── actions/    custom quick actions: model, storage, templates, import/export, AI skill and inbox
├── adb/        adb wire protocol client, device tracking, Wi-Fi pairing and mDNS discovery
├── apps/       device app listing and icon extraction
├── di/         Koin modules
├── info/       device info reading and parsing
├── logcat/     logcat stream, line parsing and the filter language
├── scrcpy/     scrcpy-server launcher, stream protocol, control and device messages
├── media/      FFmpeg video decoding, audio playback, screenshots and recording
├── options/    scrcpy option catalog, values and command-line / server-argument generation
├── presets/    preset model, JSON codec, storage and import planning
├── state/      app settings and per-device option storage
├── files/      device file browser, transfers and APK install
├── tools/      bundled adb and scrcpy-server lookup
├── update/     update checks and downloads
└── ui/         Compose screens: devices, mirror, actions, presets, settings, onboarding, window chrome, theme
```

## Data location

Settings, presets, custom actions and per-device options are stored as JSON in:

| OS      | Path                                                         |
|---------|--------------------------------------------------------------|
| macOS   | `~/Library/Application Support/ScrcpyViewer`                 |
| Windows | `%APPDATA%\ScrcpyViewer`                                     |
| Linux   | `$XDG_DATA_HOME/ScrcpyViewer` (default `~/.local/share/ScrcpyViewer`) |

Set the `scrcpyviewer.dataDir` system property to use a different folder.

## Acknowledgements

- [scrcpy](https://github.com/Genymobile/scrcpy) by Genymobile, whose server this app bundles and whose protocol it
  implements.
- [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) for adb.
- [FFmpeg](https://ffmpeg.org/) and [JavaCPP](https://github.com/bytedeco/javacpp) for video decoding.
