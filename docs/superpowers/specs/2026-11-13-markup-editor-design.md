# Screenshot markup editor

Date: 2026-11-13
Status: approved 2026-11-13 (mockup https://claude.ai/artifact/Cb4xTBnN6pHGeqeBJ9e9fX)

## Goal

Draw on a screenshot before sharing it: pen, arrow, box and pixelate (to hide private text), then copy or save it.
Builds on the screenshot thumbnail (#56).

## Entry points

- The thumbnail gets an **Edit** button between Copy and Show in folder (pen icon, tooltip "Edit").
- `AfterScreenshot` gains **OpenEditor** ("Open editor"): after a screenshot, the editor opens at once (no thumbnail).
  The Settings row becomes "Show thumbnail" / "Copy" / "Open editor".
- The thumbnail's timer holds while the editor is open and restarts at 6 s when it closes.

## Editor

A dialog over the mirror window (scrim), titled "Edit screenshot":

- Left: the screenshot, scaled to fit (at most 62% of the window height, at least 320 dp tall), drawn on directly.
- Right: tool buttons **Pen**, **Arrow**, **Box**, **Pixelate** (one selected, tooltips), then **Undo** (also
  Ctrl/Cmd+Z; disabled when nothing to undo). Four color swatches: Red `#E53935` (default), Yellow `#FBC02D`, Blue
  `#1E88E5`, Black `#111111`. Hint: "Draw on the screenshot. Pixelate hides private text such as emails or order
  numbers."
- Actions: **Copy** (filled), **Save**, then **Close** at the right. Esc = Close.

Drawing (in image pixels, so a mark looks the same at any zoom): stroke width = 0.8% of the image's shorter side
(min 3 px), round caps and joins. Pen follows the pointer; Arrow is a line with a filled head (length 4× stroke, 26°
each side); Box is a stroked rectangle from drag start to end; Pixelate replaces the dragged rectangle with blocks of
2.5% of the shorter side (min 8 px), each the average color of the original pixels under it (always from the original,
so pixelating twice never reveals anything). A drag shorter than 2 px adds nothing.

- **Copy**: renders the marked image, copies it as PNG (`ImageClipboard.copyPng` on IO via a temp file), the button's
  icon turns to a check for 1.5 s ("Copied"). The editor stays open. Failure: snackbar "Could not copy the image: <reason>".
- **Save**: writes the marked image as a new file next to the original, `<original name>-edited.png` (then `-edited-2`,
  …), never overwriting the original; closes the editor; the thumbnail crossfades to the edited file with a small
  "EDITED" tag at its top-left (accent fill) and its timer restarts at 6 s; snackbar "Saved
  <file name>". Failure keeps the editor open with snackbar "Could not save: <reason>".
- **Close** with marks not saved or copied asks "Discard your marks?" ("Your marks aren't saved or copied yet.") with **Discard** / **Keep editing**. Without
  marks it closes at once.

## Motion

| Change | Motion |
|---|---|
| Editor opens | scrim fades in 300 ms; dialog scale .88 → 1 + rise 14 dp + fade, 450 ms emphasized decelerate |
| Editor closes | dialog scale → .95 + fade 200 ms; scrim fades 200 ms |
| Tool / swatch selected | selection background crossfades 200 ms; selected swatch scales to 1.12 with its ring, 250 ms |
| Undo | the removed mark fades out 150 ms |
| Copy done | icon crossfades copy → check 200 ms, back after 1.5 s |
| Discard prompt | standard dialog enter / exit |
| Reduced motion | 150 ms fades only; no scale or rise |

Drawing itself follows the pointer with no animation.

## Tests

Geometry (stroke width, arrow head, block size, min drag); pixelate from the original (a second pass over the same
area equals one pass); undo stack; render output size equals the original; save naming (`-edited`, `-edited-2`) and no
overwrite; setting OpenEditor round trip and fallback; editor composable (tools, swatches, undo enabled state, Copy
check, Close with/without marks, Esc, Ctrl/Cmd+Z), motion rows sampled with `motion = true`; thumbnail Edit button
opens it and holds the timer; screenshots of the editor light and dark with marks.

## Out of scope

Text tool, redo, crop, choosing stroke width, GIF export.
