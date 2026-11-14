# Screenshot Extras Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a toolbar screenshot, a thumbnail in the mirror window's corner to copy (PNG image), reveal or drag out, or, by setting, copy straight away.

**Architecture:** Platform helpers (`ImageClipboard`, `revealCommand`) and a setting first; then a `ScreenshotThumbnailState` timer holder and a `ScreenshotThumbnail` composable wired into `MirrorWindow.takeScreenshot`.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, JNA/ObjC helpers, kotlinx.serialization, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-13-screenshot-extras-design.md`

## Global Constraints

- Copy (exact): "After a screenshot", "Show thumbnail", "Copy", "Show in folder", "Copied", "Screenshot saved and copied", "Saved, but could not copy the image: <reason>", "Install wl-clipboard or xclip to copy images".
- Timer 6 s; hover pauses; resume with ≥ 2 s; interaction restarts at 6 s.
- Motion table in the spec is binding; nothing snaps; reduced motion via `systemReducesMotion()`, passed in as a `motion` parameter so tests set it explicitly (CI macOS/Windows runners report reduce motion ON).
- No AWT toolkit except the Windows clipboard path. Commands are argv lists, never shell strings.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`. Avoid absolute px thresholds on text widths (fonts differ per OS).

## Review Focus

1. File names with spaces, quotes or `#`: reveal and open keep them one argument. Task 1.
2. Linux without wl-copy/xclip: copy fails with the install message, app does not hang (process timeout 5 s). Task 1.
3. Rapid screenshots: one thumbnail, latest image, earlier bitmaps released (no native leak). Task 2.
4. Window closed while the thumbnail shows: timer and bitmap disposed. Task 2.
5. Hover then leave just before expiry: thumbnail stays ≥ 2 s more. Task 2.

---

### Task 1: Setting, clipboard image, reveal

**Files:** Modify `state/AppSettings.kt` (`afterScreenshot`, `AfterScreenshot` enum with `fromName`), `ui/settings/SettingsRegistry.kt` (row "After a screenshot", segmented), `ui/common/OpenExternally.kt` (`revealCommand`, `revealInFolder`); Create `ui/common/ImageClipboard.kt`; Tests AppSettingsTest, `ui/common/OpenExternallyTest` (or existing), `ui/common/ImageClipboardTest` (Linux tool choice via injected env/which/run), settings screen test for the row.
**Produces:** `enum class AfterScreenshot { Thumbnail, Copy; companion fun fromName(String?) }`, `val Settings.afterShot: AfterScreenshot`; `object ImageClipboard { fun copyPng(file: Path): Result<Unit> }` (internal overload with platform/env/run injectable); `internal fun revealCommand(file: String, platform: OpenPlatform): List<String>`; `fun revealInFolder(file: Path): Boolean`.
- [ ] Tests first; implement; run `--tests '*AppSettingsTest*' --tests '*OpenExternally*' --tests '*ImageClipboard*' --tests '*Settings*'`; commit `feat(shots): after-screenshot setting, image copy and reveal`.

### Task 2: Thumbnail

**Files:** Create `ui/mirror/shots/ScreenshotThumbnailState.kt`, `ui/mirror/shots/ScreenshotThumbnail.kt`; Modify `ui/mirror/MirrorWindow.kt` (`takeScreenshot` by setting, flash overlay, thumbnail overlay in the video area, dispose on close); reuse `ComposeDragOutStarter`/`dragOutSource` from `ui/mirror/files/DragOut.kt` for the drag; Tests `ui/mirror/shots/ScreenshotThumbnailStateTest`, `ScreenshotThumbnailTest`, `MirrorScreenshotTest` captures (thumbnail light/dark).
**Produces:** `class ScreenshotThumbnailState(scope, clock)` with `shown: StateFlow<Shot?>` (`Shot(file, bitmap, id)`), `show(file, bitmap)`, `hover(on)`, `touch()`, `dismiss()`, `dispose()`, `remainingFraction`; composable `ScreenshotThumbnail(state, motion, onCopy, onReveal, onOpen, dragStarter)`; `ScreenshotFlash(trigger, motion)`.
- [ ] Tests for every timer rule and each motion row (sampled per frame, `motion = true`; reduced-motion run); Copy mode shows no thumbnail; replaced bitmaps closed.
- [ ] Implement; run `--tests '*Screenshot*' --tests '*MirrorWindow*'` + screenshots (open each PNG); commit `feat(shots): screenshot thumbnail`.
