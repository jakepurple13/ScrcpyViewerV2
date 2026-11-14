# Screenshot extras

Date: 2026-11-13
Status: approved (mockup https://claude.ai/artifact/AEV6zuT1JVS9XRxa4GRaQt, "14 · Screenshot extras")

## Goal

After a screenshot from the mirror window's toolbar, show a thumbnail you can copy, reveal or drag out, instead of
only a "Screenshot saved to …" snackbar.

## Decisions already made with the user

- A thumbnail is the default. It stays 6 seconds, and longer while the pointer is on it.
- Copy puts the image itself (PNG) on the clipboard, not the file path.
- Dragging the thumbnail out drops the PNG into another app.
- Out of scope for now: GIF export and the markup editor (no Edit button, no "Open editor" option).
- Every state change animates; nothing snaps.

## Setting

`Settings.afterScreenshot: String = "Thumbnail"` (an `AfterScreenshot` name: `Thumbnail`, `Copy`; unknown or missing
names fall back to Thumbnail). In Settings, next to "Screenshots and recordings folder": **After a screenshot**, a
segmented choice "Show thumbnail" / "Copy".

- Show thumbnail: save the PNG, show the thumbnail.
- Copy: save the PNG, copy the image, snackbar "Screenshot saved and copied". No thumbnail.

A failed save shows the existing "Could not save the screenshot: …" snackbar and no thumbnail. A failed copy shows
"Saved, but could not copy the image: <reason>". Recordings are unchanged.

## Thumbnail

Floats over the bottom-right corner of the mirror window's video area, 16 dp from the edges, above the video and below
dialogs.

- A card 150 dp wide: the screenshot (rounded 8 dp, its own aspect ratio, at most 260 dp tall), then a row with
  **Copy** and **Show in folder** icon buttons with tooltips.
- A close button on the card's top-right corner, ringed by the countdown (the ring empties over the 6 s).
- Clicking the image opens the PNG with the computer's default app (`openExternally`).
- Dragging the image starts the OS file drag of the PNG (copy only), through the Files tab's `ComposeDragOutStarter`.
- Copy: the button's icon turns into a check for 1.5 s (tooltip "Copied"); failure shows the snackbar above.
- Show in folder: reveals the file selected in Finder / Explorer; on Linux it opens the folder.

Timer: 6 s. Hovering pauses it; on exit it resumes with at least 2 s left. A copy, reveal or drag also counts as
interaction (timer restarts at 6 s). A new screenshot replaces the current thumbnail and restarts the timer. The window
closing disposes it.

## Platform pieces

- `ImageClipboard.copyPng(file: Path): Result<Unit>`
  - macOS: NSPasteboard `setData:forType:` with `public.png` (on the pooled pattern of `MacPasteboardClipboard`).
  - Windows: AWT `Toolkit.getDefaultToolkit().systemClipboard` with an image transferable (Windows still uses AWT's
    clipboard).
  - Linux: `wl-copy --type image/png` when `WAYLAND_DISPLAY` is set, else `xclip -selection clipboard -t image/png
    -i <file>`; when neither tool is found, a failure "Install wl-clipboard or xclip to copy images".
- `revealCommand(file, platform): List<String>`: macOS `open -R <file>`; Windows `explorer.exe /select,<file>`; Linux
  `xdg-open <parent>`. Never a shell string.

## Motion

| Change | Motion |
|---|---|
| Screenshot taken | white flash over the video, opacity .9 → 0, 450 ms |
| Thumbnail appears | slide in from the right 40 dp + scale .9 → 1 + fade, 350 ms emphasized decelerate |
| New screenshot while shown | old image crossfades to the new one, 250 ms; ring refills 300 ms |
| Countdown | ring empties linearly; pauses (holds) while hovered |
| Copy done | icon crossfades copy → check, 200 ms, back after 1.5 s |
| Thumbnail leaves (timeout, close, drag drop) | slide right 40 dp + fade, 300 ms standard accelerate |
| Reduced motion | no flash; appear/leave are 150 ms fades; ring still counts (not motion) |

## Tests

Setting default / unknown name / round trip; reveal commands per platform; Linux clipboard tool choice; thumbnail timer
(6 s, hover pause, resume ≥ 2 s, restart on interaction, replace on new screenshot) on virtual time; composable: shows
after a screenshot, actions fire, leaves after the timer, motion rows sampled frame by frame with `motion = true`
(CI runners report reduced motion), reduced motion; Copy mode shows no thumbnail; screenshots of the thumbnail light and
dark.
