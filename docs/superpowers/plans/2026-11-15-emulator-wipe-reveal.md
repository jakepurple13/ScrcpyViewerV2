# Emulator Wipe Data and Show in Finder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The two emulator ⋮ menu items deferred from #60 — Show in Finder / Show in folder and Wipe data… with its confirm dialog — plus the detail pane title using the AVD name.

**Architecture:** `EmulatorLauncher.wipe(avd)` (file deletes, refused while running), menu items and a `WipeDialog` in `ui/devices/emulators/EmulatorSection.kt`, reveal through the existing `revealInFolder`.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-15-emulator-launcher-design.md` (More menu, now including the items below; approved mockup https://claude.ai/artifact/4Qo5e7z3kTrNAsNpzAf9MQ "11").

## Global Constraints

- More menu, after the checkboxes: a divider, then **Show in Finder** (macOS) / **Show in folder** (Windows, Linux) → `revealInFolder(<avd dir>)`; then **Wipe data…** (error colour, trash icon), enabled only when Stopped or Failed.
- Wipe dialog (exact copy): title "Wipe <name>?", body "This erases apps, accounts and files on the emulator. It can't be undone.", buttons "Cancel" and **Wipe data** (error-coloured). Confirm deletes exactly `userdata-qemu.img`, `userdata-qemu.img.qcow2`, `cache.img`, `cache.img.qcow2` and the `snapshots` folder inside the AVD dir (missing ones are fine), then snackbar "Wiped <name>. Next start is a cold boot."; failure snackbar "Couldn't wipe <name>: <reason>".
- Wipe is refused (and the item disabled) while the AVD is Booting, Running or Stopping, or listed by adb; deletion never leaves the AVD dir (no symlink following out of it).
- Detail pane title for a connected emulator: its AVD name (as the row), crossfading like the row.
- Motion (binding): dialog scrim fades 300 ms; the dialog scales .88 → 1 and rises 14 dp over 450 ms (emphasized decelerate); closing reverses over 150 ms; menu items and the disabled state crossfade 200 ms; reduced motion: 150 ms fades. `motion` param; animation tests pass `motion = true` explicitly. No absolute px thresholds on text widths; Windows-safe paths; tests never touch a real SDK/emulator/du.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Wipe deletes only the five named entries inside the AVD dir, never follows a symlink out of it, and is refused while the AVD runs (ours or external) or is listed by adb.
2. A Start pressed while a wipe runs waits or is refused cleanly (no emulator booting on half-deleted data).
3. The dialog's Esc/scrim/Cancel all close without wiping; Enter on Wipe data confirms.

---

### Task 1: Wipe, reveal and the detail pane title

**Files:** Modify `emulator/EmulatorLauncher.kt` (`wipe(avd): Result<Unit>`, busy guard against Start), `ui/devices/emulators/EmulatorsViewModel.kt` (events `Reveal`, `Wipe`), `ui/devices/emulators/EmulatorSection.kt` (menu items, `WipeDialog`), the detail pane title (`ui/devices/SimpleDevicePane.kt` and the advanced header), spec "More menu" section (restore the items, drop the deferral note); Tests `EmulatorLauncherTest`, `EmulatorsViewModelTest`, `EmulatorSectionTest`, `DeviceRowTest`, `DevicesScreenshotTest` (menu with the new items, wipe dialog light/dark).
- [ ] Tests first: `wipe deletes only data files`, `wipe refuses while running or listed`, `wipe never follows a symlink out of the avd dir`, `start during a wipe waits or is refused`, `reveal opens the avd folder`, `Wipe data disabled unless stopped or failed`, dialog open/close motion rows (motion true/false), confirm/cancel/Esc/Enter, snackbars, detail pane title crossfade.
- [ ] Implement; run `--tests '*Emulator*' --tests '*DeviceRow*' --tests '*DevicesScreen*'` + screenshots (open each PNG); full jvmTest once; commit `feat(emulators): wipe data and show in finder`.
