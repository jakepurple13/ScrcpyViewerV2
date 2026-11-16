# About and Open-source Licenses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settings › Help gets "About Scrcpy Vwr" and "Open-source licenses" dialogs, the licences from AboutLibraries plus hand-written entries for the bundled tools.

**Architecture:** AboutLibraries Gradle plugin generates `aboutlibraries.json` at build time (user, 2026-11-16: option 2): the export task writes into a build-dir resources folder added to jvmMain resources and runs before resource processing, so every build (local and CI) has a fresh list and nothing is committed; a `config/` dir adds FFmpeg (LGPL 3.0+), adb (Apache 2.0) and scrcpy-server (Apache 2.0); `AboutDialog` and `LicensesDialog` in `ui/settings/` wired like `AddAiSkillDialog`.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.12.2 Material 3, AboutLibraries 15.3.0 (fall back to 15.2.0 if 15.3.0's Kotlin 2.4 metadata doesn't load on 2.3.21): plugin `com.mikepenz.aboutlibraries.plugin`, `com.mikepenz:aboutlibraries-core`, `com.mikepenz:aboutlibraries-compose-m3`.

**Spec / mockup:** approved by the user 2026-11-16: https://claude.ai/artifact/5uoTYKSFvpAWHXztU4Ds9z (HTML: /private/tmp/claude-501/-Users-jacobrein-Desktop-scrcpy-viewer/a00085e7-d19e-4c1b-92b1-7ecc2149f607/scratchpad/licenses.html).

## Global Constraints

- Copy (exact): rows "About Scrcpy Vwr" ("Version <v> · what's inside and where it came from.", button "About") and "Open-source licenses" ("The libraries and tools Scrcpy Vwr is built on, with their licences.", button "View licenses"); search keywords "about", "version", "license", "licence", "oss", "attribution", "ffmpeg". About dialog: app name, "Version <v>", rows Mirroring "scrcpy-server <v>", Android tools "adb <v>", Video "FFmpeg <v>", Runtime "Java <v> · Compose <v>"; buttons "Copy details" (→ "Copied" 1.5 s), "GitHub", "Open-source licenses", "Close". Licenses dialog: title "Open-source licenses", subtitle "Scrcpy Vwr is built on these. Thank you to everyone who makes them.", search placeholder "Search libraries or licences", count "<n> entries" / "<shown> of <n>", groups "Bundled tools" then "Libraries", empty text "Nothing matches. Try a licence name such as \"MIT\" or \"Apache\".", "Close".
- Versions come from the build (APP_VERSION, the scrcpy-server/adb/FFmpeg versions used in the build script, the running Java and Compose versions) — no hard-coded numbers in UI code.
- Rows: name, version, licence chip, developer line; click unfolds description, links (open externally) and full licence text; chevron rotates. Motion: dialogs scale .88→1 + rise 14 dp 450 ms emphasized decelerate, scrim 300 ms, close 200 ms; unfold 400 ms; search results fade in 300 ms; reduced motion: 150 ms fades. `motion` param; animation tests pass `motion = true` explicitly; no absolute px thresholds on text widths.
- Use AboutLibraries' Compose (m3) pieces where they fit the mockup (e.g. its data + license text), custom row/grouping/search UI where they don't.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time.

## Review Focus

1. The JSON is generated on every build (clean build, `jvmTest`, packaging on CI) and the app finds it on the classpath; a test asserts the bundled tools and a known dependency (e.g. Koin) are present.
2. FFmpeg shows LGPL 3.0 or later with the javacpp-presets source link; adb and scrcpy-server Apache 2.0 with their sources.
3. Search across names, licences and developers; empty state; very long licence text scrolls inside its row.
4. Linux CI memory: the JSON is small (exclude unused fields).
5. Copy details text is useful for a bug report (all versions + OS).

---

### Task 1: AboutLibraries setup and the two dialogs

**Files:** `gradle/libs.versions.toml`, `composeApp/build.gradle.kts` (plugin, deps, `aboutLibraries {}` export to `build/generated/aboutlibraries/aboutlibraries.json`), `composeApp/config/libraries/*.json` + `config/licenses/*.json`, a generated-resources dir under `build/` wired as a jvmMain resource srcDir with the export task as a dependency of `jvmProcessResources` (no committed JSON; add the output path to nothing in git), `ui/settings/AboutDialog.kt`, `ui/settings/LicensesDialog.kt`, `ui/settings/SettingsRegistry.kt` (Help entries), `SettingsScreen.kt` / actions wiring; Tests: `AboutDialogTest`, `LicensesDialogTest`, settings search test, screenshot captures (About, Licenses, Licenses with FFmpeg open, empty search; light/dark).
- [ ] Tests first for each behaviour and motion row; implement; run `--tests '*About*' --tests '*Licenses*' --tests '*Settings*'` + screenshots (open each PNG); full jvmTest once; commit `feat(settings): about and open-source licenses`.
