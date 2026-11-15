# Emulator and Markup Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the small follow-ups left by the emulator launcher (#60) and the markup editor window (#59).

**Architecture:** Targeted changes in the device list (rows, menu, hints, selection) and the markup toolbar; no new subsystems.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-15-emulator-launcher-design.md` and `docs/superpowers/specs/2026-11-15-markup-window-design.md` (as built), plus the decisions below (user, 2026-11-15).

## Global Constraints

- Copy (exact, new, approved): "Emulator offline. Stop and start it again from Emulators."
- Every state change animates; nothing snaps; `motion` is a parameter and animation tests pass `motion = true` explicitly (CI macOS/Windows runners report reduce motion ON). No absolute px thresholds on text widths; test paths Windows-safe.
- Tests never touch a real SDK, emulator or `du` (use the `EmulatorEnvironment` seam).
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. A real (non-emulator) device's row, menu, hints and selection are unchanged.
2. An emulator started outside the app whose AVD name isn't known yet still shows a sensible title, then the AVD name once matched, without a jump.
3. Toolbar wrapping while the window is dragged continuously: items glide, no flicker, no stuck positions.

---

### Task 1: Emulator rows and markup toolbar wrap

**Files:** Modify the Devices screen device rows / context menu / hints / selection (ui/devices/*), the emulators view model or launcher state if needed for AVD names, `ui/mirror/shots/markup/MarkupEditor.kt` (toolbar); Tests: DevicesScreenTest / device row tests, EmulatorLauncher/EmulatorsViewModel tests as needed, MarkupEditorTest; screenshots where rows change.

- [ ] **Connected emulator title:** a connected emulator whose AVD is known (launcher state, ours or matched external) is titled with the AVD's display name (e.g. "heads_up_api34" / "Pixel 9"), not the system model ("sdk_gphone64_arm64"); serial subtitle unchanged. Until matched, the existing title; the change animates (crossfade).
- [ ] **No Wi-Fi for emulators:** the connected row's right-click menu omits "Connect over Wi-Fi" (and any other Wi-Fi pairing entry) for emulator serials.
- [ ] **Offline emulator hint:** an offline emulator row shows "Emulator offline. Stop and start it again from Emulators." instead of the cable hint.
- [ ] **No selection jump on ready:** when nothing is chosen and an emulator finishes booting, the selection does not move to it if another device is already shown/selected by default; with no other device, selecting it is fine. Test both.
- [ ] **Avoid `du` on every Start:** sizes are measured on screen open / SDK change / after a Stop, not on every Start (keep them current enough; a running emulator's size need not refresh).
- [ ] **Markup toolbar wrap motion:** when the toolbar wraps or unwraps, each group (tools, "| Undo", "| swatches", Save + Copy) glides to its new place (e.g. `LookaheadScope` + `Modifier.animateBounds`, size and position tween FastOutSlowIn/emphasized, ~250 ms) instead of jumping; reduced motion: at once. Also drop a separator that would start a row (no leading "|"). Test: during a width change across the wrap point, a group's position moves over several frames (motion = true), lands at once with motion = false.
- [ ] Run `--tests '*Emulator*' --tests '*DevicesScreen*' --tests '*Device*' --tests '*Markup*'` + screenshots (open each PNG), full jvmTest once; commit `fix(emulators): AVD names, no Wi-Fi for emulators, offline hint, steady selection; glide the markup toolbar`.
