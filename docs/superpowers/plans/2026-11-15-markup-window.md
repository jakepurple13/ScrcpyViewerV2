# Markup Editor Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The markup editor in its own window that grows out of the pressed button and switches on like a mirror window, with a toolbar layout.

**Architecture:** Task 1 reshapes `MarkupEditor` into window content (toolbar, canvas, footer, in-window discard prompt) and adds the thumbnail's EDITING state; Task 2 hosts it in a `MarkupEditorWindow` declared from `MirrorWindow` (like `LogcatWindow`), with open/close motion reusing `MirrorWindowMotion.kt` and the lifecycle rules.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, Nucleus Tao windows (`MaterialDecoratedWindow`), kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-15-markup-window-design.md` (the editor itself: `docs/superpowers/specs/2026-11-13-markup-editor-design.md`)

## Global Constraints

- Copy (exact): "Edit screenshot · <device name>", "EDITING", "Pixelate hides private text such as emails or order numbers.", "<w> × <h>" image size, plus all existing editor copy (Pen, Arrow, Box, Pixelate, Undo, Red, Yellow, Blue, Black, Copy, Copied, Save, "Discard your marks?", "Your marks aren't saved or copied yet.", "Keep editing", "Discard", "Saved <file name>", "Could not save: <reason>", "Could not copy the image: <reason>").
- Motion constants come from `ui/mirror/MirrorWindowMotion.kt` (`MIRROR_ZOOM_IN_MILLIS`, `SCREEN_WAKE_MILLIS`, `SCREEN_SLEEP_MILLIS`, `MIRROR_ZOOM_OUT_MILLIS`, `MIRROR_SHRINK_IN_PLACE`, `wakeFrame`, `animateWindowBounds`); reuse, don't copy. Nothing snaps; `motion` is a parameter and animation tests pass `motion = true` explicitly (CI macOS/Windows runners report reduce motion ON). No absolute px thresholds on text widths; test paths Windows-safe.
- Bitmaps are native: the window holds the shot's bitmap lease while it draws it; never draw a closed one; closing the mirror window releases everything.
- `ImageClipboard.copyPng` only on Dispatchers.IO. Keys in the editor never reach the device.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Mirror window closed while its editor is open with marks: one prompt, Cancel keeps both, Discard closes both, no leaked window or bitmap. Task 2.
2. Edit pressed twice fast: still one editor window. Task 2.
3. Thumbnail gone (timed out impossible while held, but closed by its X or replaced by a new screenshot) while editing: close shrinks in place; Save still updates/creates the thumbnail correctly. Task 2.
4. Window resized very small: canvas still fits, toolbar doesn't overlap (overflow wraps or clips cleanly). Task 1.
5. Editor window focused: Esc/Cmd+Z go to the editor; the mirror window's device keys are unaffected. Task 2.

---

### Task 1: Window-shaped editor content and EDITING thumbnail

**Files:** Modify `ui/mirror/shots/markup/MarkupEditor.kt` (content composable for a window: toolbar row, canvas, footer, in-window discard prompt; drop the scrim/dialog card), `ui/mirror/shots/ScreenshotThumbnail.kt` + state (EDITING overlay, hold); Tests: update `MarkupEditorTest`, thumbnail tests, `MirrorScreenshotTest` captures (editor window content light/dark, thumbnail EDITING).
**Produces:** `MarkupEditorContent(shot, motion, wake: () -> Float, onCopy, onSave, onClose, onCloseRequest-handled-inside, leases, modifier)` that draws the canvas through the wake (black + `wakeFrame`), and `ScreenshotThumbnailState.editing: StateFlow<Boolean>` (or equivalent) driving the overlay.
- [ ] Tests first; implement; run `--tests '*Markup*' --tests '*Screenshot*'` + screenshots (open each PNG); commit `feat(markup): editor content shaped for a window, thumbnail EDITING state`.

### Task 2: Editor window with mirror motion and lifecycle

**Files:** Create `ui/mirror/shots/markup/MarkupEditorWindow.kt`; Modify `ui/mirror/MirrorWindow.kt` (declare the window like `LogcatWindow`, origins for Edit and camera buttons, single instance, close cascade with prompt), `MirrorWindowMotion.kt` only if a helper must be shared; Tests: window motion/lifecycle tests with fakes for the window handle (follow existing mirror motion tests), close cascade.
**Produces:** `MarkupEditorWindow(...)` and the open/close orchestration.
- [ ] Tests first; implement; run `--tests '*Markup*' --tests '*Screenshot*' --tests '*MirrorWindow*' --tests '*Motion*'`; full jvmTest once; commit `feat(markup): editor in its own window, opening like a mirror window`.
