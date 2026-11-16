# GIF Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A recording thumbnail with Export GIF, opening an Export GIF window that trims, sizes and encodes with the bundled FFmpeg.

**Architecture:** `media/GifExporter.kt` (argv, two-pass process run, progress, cancel, naming) first; then the recording thumbnail and the `GifExportWindow` reusing the markup editor window's motion and lifecycle patterns.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, org.bytedeco ffmpeg 8.1.2-1.5.14 (JavaCPP `Loader`), coroutines, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-15-gif-export-design.md`

## Global Constraints

- Copy (exact): "Export GIF", "Export GIF · <device name>", "Show in folder", "MAKING GIF", "Trim", "Width", "320 px", "480 px", "640 px", "Frame rate", "10 fps", "15 fps", "About <size> · <w> × <h>", "Close", "Export", "Cancel", "Export again", "Making the palette…", "Encoding…", "Export cancelled. The partial GIF was deleted.", "Couldn't make the GIF: <reason>", "Trim to 60 s or less to export".
- FFmpeg: the bundled executable from `Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)`, argv lists only, two passes exactly as the spec, temp files in the app cache dir, `-progress pipe:1`; never block the UI thread; cancel = destroy (and descendants) + delete partial files.
- Reuse `MirrorWindowMotion` and the markup editor window's patterns (grow/wake/sleep/shrink, min size only while open, own snackbar for failures, close cascade with the mirror window); do not copy them.
- Motion table in the spec is binding; nothing snaps; `motion` parameter; animation tests pass `motion = true` explicitly (CI macOS/Windows report reduce motion ON). No absolute px thresholds on text widths; Windows-safe paths.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Recording paths and names with spaces, quotes, `#` or non-ASCII: argv stays exact; output name correct.
2. Cancel mid-pass-1 and mid-pass-2: process tree gone, no partial `.gif` or palette left, UI back to idle.
3. The bundled `ffmpeg` binary on macOS (signed app, extracted by JavaCPP), Windows (`.exe`) and Linux: found and executable; a clear error if not.
4. A recording still being written (thumbnail appears only after the file is closed) and a deleted recording: clear error, no crash.
5. Mirror window closed while exporting: the export is cancelled and cleaned up.

---

### Task 1: GifExporter and the licence notice

**Files:** Create `media/GifExporter.kt`; add the FFmpeg notice to `bundled/` (+ wherever the app lists licences); Tests `media/GifExporterTest.kt`.
**Produces:** `data class GifOptions(startMs, endMs, width, fps)`; `fun gifArgv(ffmpeg, input, palette, output, o): Pair<List<String>, List<String>>`; `fun gifFile(recording: Path): Path`; `fun estimateBytes(o, srcW, srcH): Long`; `class GifExporter(ffmpegPath: () -> Path, scope, starter)` with `export(input, o, onProgress: (Phase, Float) -> Unit): Result<Path>` (suspend, cancellable) and `enum Phase { Palette, Encode }`; `fun parseProgress(line, durationUs): Float?`.
- [ ] Tests: argv for both passes (incl. odd paths), naming (`.gif`, `-2`), estimate, progress parsing, cancel kills and deletes (fake starter), failure reason from stderr tail, >60 s rejected; one real run on a 2 s generated clip with the bundled binary (skip with a message if it can't run).
- [ ] Implement; run `--tests '*GifExporter*'`; commit `feat(gif): two-pass gif export with the bundled ffmpeg, ffmpeg notice`.

### Task 2: Recording thumbnail and Export GIF window

**Files:** Modify `ui/mirror/shots/ScreenshotThumbnail*.kt` (recording variant), `ui/mirror/MirrorWindow.kt` (recording stop → thumbnail; window declaration; close cascade); Create `ui/mirror/shots/gif/GifExportWindow.kt`, `ui/mirror/shots/gif/GifExportContent.kt`; Tests `GifExportContentTest`, `GifExportWindowTest`, thumbnail tests, `MirrorScreenshotTest` captures.
**Produces:** recording thumbnail; `GifExportWindow(...)` with trim/width/fps/estimate/progress/done per spec.
- [ ] Tests for every spec behaviour and motion row; implement; run `--tests '*Gif*' --tests '*Screenshot*' --tests '*MirrorWindow*'` + screenshots (open each PNG); full jvmTest once; commit `feat(gif): recording thumbnail and export gif window`.
