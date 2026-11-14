# Markup Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dialog to mark up a screenshot (pen, arrow, box, pixelate, undo, four colors) and copy or save it, opened from the thumbnail's Edit button or right after a screenshot.

**Architecture:** A pure `MarkupDocument` model (marks, undo, geometry, Skia render to a bitmap, save naming) first; then the `MarkupEditor` dialog composable and its wiring into the thumbnail, the setting and `MirrorWindow`.

**Tech Stack:** Kotlin 2.3 JVM, Compose Multiplatform 1.12 Material 3, Skia (org.jetbrains.skia), kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-13-markup-editor-design.md`

## Global Constraints

- Copy (exact): "Edit", "Open editor", "Edit screenshot", "Pen", "Arrow", "Box", "Pixelate", "Undo", "Red", "Yellow", "Blue", "Black", "Draw on the screenshot. Pixelate hides private text such as emails or order numbers.", "Copy", "Copied", "Save", "Close", "Saved <file name>", "Could not save: <reason>", "Could not copy the image: <reason>", "Discard your marks?", "Your marks aren't saved or copied yet.", "Discard", "Keep editing", "EDITED". The editor Copy button label reads "Copied" while its check shows.
- Colors `#E53935` (default), `#FBC02D`, `#1E88E5`, `#111111`. Stroke 0.8% of shorter side (min 3 px); arrow head 4× stroke, 26°; pixel block 2.5% of shorter side (min 8 px); min drag 2 px.
- Save never overwrites: `<name>-edited.png`, `-edited-2`, …
- `ImageClipboard.copyPng` blocks up to 5 s: call on Dispatchers.IO only. Bitmaps are native: close what you create; never draw a closed one.
- Motion table in the spec is binding; nothing snaps; `motion` is a parameter, tests pass `motion = true` explicitly (CI macOS/Windows runners report reduce motion ON). No absolute px thresholds on text widths. Paths in tests must work on Windows.
- Tests `./gradlew :composeApp:jvmTest --tests '<pattern>'` (+ `-Pscreenshots`). One Gradle build at a time; if the Kotlin daemon OOMs add `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx4g`.

## Review Focus

1. Pixelating the same area twice, or overlapping a pen stroke, never reveals original pixels beyond one pass. Task 1.
2. Very large screenshots (e.g. 1440×3200): rendering a mark while dragging stays smooth (no full re-render of every mark per pointer move if avoidable; at least no allocation leak). Task 2.
3. Window closed with the editor open: bitmaps released, no save half-written. Task 2.
4. Save while the original file was deleted or the folder is read-only: error snackbar, editor stays open with marks. Task 1/2.
5. Ctrl/Cmd+Z and Esc while typing nowhere else: handled by the editor, not forwarded to the device. Task 2.

---

### Task 1: Markup model and render

**Files:** Create `ui/mirror/shots/markup/MarkupDocument.kt` (marks, tools, colors, undo, geometry), `ui/mirror/shots/markup/MarkupRender.kt` (draw marks onto a Skia canvas; render to a new bitmap; encode PNG; `editedFile(original)` naming); Tests `MarkupDocumentTest`, `MarkupRenderTest`.
**Produces:** `enum class MarkupTool { Pen, Arrow, Box, Pixelate }`; `enum class MarkupColor(val argb: Int, val label: String)`; `sealed interface Mark`; `class MarkupDocument(width, height)` with `marks`, `add(mark)`, `undo()`, `canUndo`, `dirty`; geometry helpers `strokeWidth(w,h)`, `blockSize(w,h)`, `arrowHead(...)`; `fun drawMarks(canvas, original: Bitmap, marks)`; `fun renderMarked(original: ImageBitmap, marks): ImageBitmap`; `fun editedFile(original: Path): Path`; `fun saveMarked(...)`.
- [ ] Tests first (Review Focus 1 and 4 here); implement; run `--tests '*Markup*'`; commit `feat(markup): marks, geometry, render and save`.

### Task 2: Editor dialog and wiring

**Files:** Create `ui/mirror/shots/markup/MarkupEditor.kt`; Modify `state/AfterScreenshot.kt` (OpenEditor), settings row (three options), `ui/mirror/shots/ScreenshotThumbnail.kt` (Edit button), `ScreenshotThumbnailState.kt` (hold while editing; show edited file), `ui/mirror/MirrorWindow.kt` (open editor by setting and from Edit; dispose); Tests `MarkupEditorTest`, thumbnail/setting tests, `MirrorScreenshotTest` captures (editor light/dark with marks).
**Produces:** `MarkupEditor(shot, motion, onCopy, onSave, onClose)` composable and an editor-open state in the window.
- [ ] Tests for every spec behavior and motion row; implement; run `--tests '*Markup*' --tests '*Screenshot*' --tests '*Settings*' --tests '*AppSettings*'` + screenshots (open each PNG); full jvmTest once; commit `feat(markup): screenshot markup editor`.
