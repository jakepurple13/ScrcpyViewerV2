# Small Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four small fixes left by GIF export (#64) and the Linux FFmpeg work: honest Cancel, cleanup on hard quit, normal GIF file permissions, and Linux package dependencies.

**Architecture:** Targeted changes in `media/GifExporter.kt`, `ui/mirror/shots/gif/*`, `AppShutdown.kt`, and the Linux packaging config in `composeApp/build.gradle.kts`.

**Tech Stack:** Kotlin 2.3 JVM, coroutines, Nucleus packaging (electron-builder deb), kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-15-gif-export-design.md` (as built) and the decisions below (user, 2026-11-16).

## Global Constraints

- Existing copy stays: "Export cancelled. The partial GIF was deleted.". When a cancel arrives after the GIF is complete, the export counts as done (the done row and file), never "deleted".
- Never block the UI thread; argv lists only; tests never touch a real device; animation tests (if any) pass `motion = true` explicitly; Windows-safe paths.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'`. One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Cancel pressed at every point of an export (pass 1, pass 2, during the move into place, during the size read): the message always matches what's on disk.
2. Normal quit, quit with a slow FFmpeg kill, and JVM shutdown hook on SIGTERM: no FFmpeg process left, no `.part`/palette left; the hook never deletes a finished GIF.
3. GIF permissions follow the user's normal file mode (umask), like other files the app writes; Windows unaffected.
4. The deb's Depends is valid Debian syntax for both Ubuntu 24.04+ (`libasound2t64`) and older names (`libasound2`), and packaging still succeeds on CI.

---

### Task 1: Small follow-ups

**Files:** Modify `media/GifExporter.kt` (+ a small `FfmpegRuns` registry in `media/`), `ui/mirror/shots/gif/GifExportContent.kt` (cancel race on the size read), `AppShutdown.kt`, `composeApp/build.gradle.kts` (Linux deb dependencies), `README.md` (Linux requirements line); Tests `GifExporterTest`, `GifExportContentTest`, `AppShutdownTest`.

- [ ] **Cancel race:** once the output has been moved into place, a cancel no longer deletes it or reports deletion: `export` returns the finished file (completion wins after the move); the content's size read after a successful export can't turn into "cancelled". Tests: cancel right after the move → Result success, file present, no cancel message; cancel during pass 2 → cancelled message, nothing on disk.
- [ ] **Hard quit cleanup:** `FfmpegRuns` (process + its partial/palette paths) registered for each FFmpeg run (export and RecordingFrames). `AppShutdown.run` waits up to 2 s for running exports to clean up after `closeAll()`; a JVM shutdown hook force-kills remaining FFmpeg processes (descendants first) and deletes registered partial/palette files, never a finished GIF. Tests: registry add/remove; shutdown waits then forces; hook deletes only registered partials.
- [ ] **GIF permissions:** the finished GIF gets the user's normal permissions (rw for owner, plus group/other per umask — e.g. create the `.part` with `Files.createFile` instead of `createTempFile`, or set `rw-r--r--` after the move on POSIX, guarded for non-POSIX). Test on POSIX: mode is not owner-only.
- [ ] **Linux deb dependencies:** the deb declares `libasound2t64 | libasound2, libpulse0, libxcb1, libxcb-shm0, libdrm2, libudev1` (find how Nucleus/electron-builder exposes deb `depends`; if not exposed, note and add a README "Linux requirements" line only). README gets a Linux requirements line either way.
- [ ] Run the targeted tests, then the full jvmTest once; commit `fix(gif): honest cancel, cleanup on quit, normal file mode; linux deb dependencies`.
