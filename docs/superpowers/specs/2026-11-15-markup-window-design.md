# Markup editor window

Date: 2026-11-15
Status: approved 2026-11-15 (mockup https://claude.ai/artifact/Kyi7Wz3AXdnTkCbAeKhhey)

## Goal

Move the screenshot markup editor (#57) out of the dialog over the mirror window into its own window, which opens and
closes with the mirror window's motion: it grows out of the button pressed, then the screenshot switches on like the
device screen.

## Decisions made with the user

- Its own window, titled "Edit screenshot · <device name>", resizable and movable.
- Opening: grows out of the button pressed (the thumbnail's Edit button, or the mirror window's camera button when
  "After a screenshot" is "Open editor"), with `animateWindowBounds(..., MIRROR_ZOOM_IN_MILLIS, grow = true, ...)`.
  Then the screenshot switches on with the mirror window's wake (`wakeFrame`, `SCREEN_WAKE_MILLIS`, emphasized
  decelerate): black canvas, a bright line widening 60% → 100%, then it opens while the white fades.
- Closing (Save, Discard, close with no marks): the screenshot switches off (wake backwards, `SCREEN_SLEEP_MILLIS`,
  emphasized accelerate), then the window shrinks into the thumbnail with `MIRROR_ZOOM_OUT_MILLIS`, `grow = false`. With
  no thumbnail on screen, it shrinks to `MIRROR_SHRINK_IN_PLACE` around its center and fades, as a mirror window does.
- Layout for a window: a top toolbar with Pen, Arrow, Box, Pixelate | Undo | the four swatches, then Save (text button)
  and Copy (filled) at its right. The screenshot fills the rest, centred at its aspect ratio, and scales with the window.
  A footer shows the hint "Pixelate hides private text such as emails or order numbers." and the image size
  ("1080 × 2340").
- Closing is the window's close button or Esc; with marks not saved or copied it asks "Discard your marks?" ("Your
  marks aren't saved or copied yet.", Keep editing / Discard) inside the window first.
- While the editor is open the thumbnail dims with an "EDITING" label over its image (250 ms fade) and its countdown
  holds. Edit again brings the open editor window to the front (no second window).
- Closing the mirror window also closes its editor, asking first when there are unsaved marks (cancelling keeps both
  open).
- Unchanged: tools, colors, geometry, Copy keeps the window open, Save writes `-edited.png` and the thumbnail shows it
  with the EDITED tag, the "Open editor" setting, Ctrl/Cmd+Z, keys never reach the device.

## Size

Opens at the screenshot's aspect ratio for the canvas plus the toolbar and footer, the canvas as tall as 80% of the
usable screen height allows (min window 420 × 480 dp), centred on the mirror window's screen. Minimum size 360 × 420 dp.

## Reduced motion

The window appears at its size and fades in (150 ms); the screenshot fades in (150 ms); closing fades out (150 ms). No
growing, shrinking or line. The setting "animateMirrorWindows" off also skips the grow/shrink (as for mirror windows).

## Tests

Layout (toolbar order, footer, canvas fits at aspect ratio on resize); thumbnail EDITING state and timer hold; one
editor per mirror window (Edit again focuses); close paths (Save, Discard, no marks, Esc, close button with/without
marks, mirror window closing with/without marks, cancel keeps both); motion: origin chosen (Edit button vs camera
button vs none → shrink in place), wake progress sampled per frame with `motion = true`, reduced motion fades; existing
markup editor tests kept passing (moved to the window content).
