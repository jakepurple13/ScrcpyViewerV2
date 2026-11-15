# GIF export

Date: 2026-11-15
Status: approved 2026-11-15 (mockup https://claude.ai/artifact/A2tFCsQdDEsrvVvG8MNur4)

## Goal

Turn a screen recording into a GIF: trim it, pick a size and frame rate, export. Builds on the screenshot thumbnail
(#56) and the markup editor window's motion (#59).

## Decisions made with the user

- Recordings get a thumbnail like screenshots instead of only the "Recording saved to …" snackbar: the clip's first
  frame, its length ("0:18") over a play glyph, **Export GIF** and **Show in folder**, the same 6 s countdown (held
  while hovered, restarted by an action), close ring. The "After a screenshot" setting does not apply to recordings.
  Clicking the image opens the recording with the default app.
- Export GIF opens its own window, titled "Export GIF · <device name>", grown out of the GIF button exactly like the
  markup editor window (reuse `MirrorWindowMotion`: grow, then the preview wakes; close: sleep, then shrink into the
  thumbnail, or in place and fade with no thumbnail). The thumbnail holds and dims with "MAKING GIF" while it is open.
  One window per recording thumbnail; Export GIF again focuses it.
- Window content: a looping preview of the trimmed part at the chosen fps (left); right: title "Export GIF"; **Trim**:
  a film strip of 12 frames with two handles (start/end; minimum 0.5 s apart), dimmed outside, a moving playhead, and
  "0:03.0   9.0 s   0:12.0"; **Width** segmented 320 px / 480 px / 640 px (default 480); **Frame rate** 10 fps /
  15 fps (default 15); "About <size> · <w> × <h>" estimate; actions **Close** and **Export**.
- Export: progress row "Making the palette…" then "Encoding…" with a percentage; Export becomes **Cancel**. Done: a
  green row with a check, "<file name>" and "<size> · <w> × <h> · <len> s", plus **Show in folder**; the button reads
  **Export again**. The window stays open.
- File: next to the recording, `<recording name>.gif`, then `-2`, …; never overwrites. Cancel stops FFmpeg and deletes
  the partial file and the palette; snackbar "Export cancelled. The partial GIF was deleted." Failure keeps the window
  with "Couldn't make the GIF: <reason>".
- Clips longer than 60 s: Export is disabled until the trimmed part is 60 s or less; the range line says "Trim to 60 s
  or less to export" in the error colour.
- Uses the FFmpeg already in the app: the `ffmpeg` executable in the `org.bytedeco:ffmpeg` native jar
  (`Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)` gives its path), run as a separate process, two passes:
  1. `-ss S -t D -i in -vf "fps=F,scale=W:-2:flags=lanczos,palettegen=stats_mode=diff" pal.png`
  2. `-ss S -t D -i in -i pal.png -lavfi "fps=F,scale=W:-2:flags=lanczos[x];[x][1:v]paletteuse=dither=sierra2_4a"
     -progress pipe:1 out.gif`
  Progress from `out_time_us` against D. Argv lists, never shell strings. Temp files in the app's cache dir.
- The app ships an FFmpeg licence notice (LGPL v3 or later, the bytedeco javacpp-presets 1.5.14 source link) in
  `bundled/` next to the adb NOTICE and in Settings > About (or the existing licences place).

## Size estimate

`bytes ≈ D × F × W × H × k` with `k` fitted so a 9 s, 15 fps, 480 px screen clip estimates within ±50% (the estimate
is labelled "About"). H keeps the recording's aspect ratio, even.

## Motion

| Change | Motion |
|---|---|
| Recording stops | thumbnail slides in from the right 40 dp + scale .9 → 1 + fade, 350 ms emphasized decelerate |
| Window opens | grow from the GIF button (`MIRROR_ZOOM_IN_MILLIS`), then the preview wakes (`SCREEN_WAKE_MILLIS`) |
| Trim handle dragged | dimmed sides, frame and readout follow the pointer; estimate pulses accent 450 ms |
| Width / rate picked | pill glides 350 ms; estimate pulses |
| Export starts / ends | progress row unfolds / folds 400 ms; button label crossfades |
| Done | green row unfolds 400 ms; check pops (scale 0 → 1.12 → 1, 340 ms) and draws |
| Window closes | preview sleeps (`SCREEN_SLEEP_MILLIS`), then shrink into the thumbnail (`MIRROR_ZOOM_OUT_MILLIS`) |
| Reduced motion | 150 ms fades only; no grow, shrink, line or pop |

## Tests

Argv building (trim, fps, width, palette path, progress) incl. paths with spaces; progress parsing; cancel kills the
process and deletes partial files; file naming and no overwrite; >60 s gating; size estimate; recording thumbnail
(appears on stop, timer, actions, opens file); window lifecycle (one per thumbnail, focus again, close into thumbnail
or in place, mirror window close closes it); motion rows sampled with `motion = true`; a real two-pass run on a tiny
generated clip using the bundled binary (skip if the binary can't run on the CI OS, with a log line); screenshots of the
thumbnail and window states (idle, exporting, done) light/dark.

## Out of scope

GIF from screenshots or files, MP4 trimming/export, more sizes or rates, looping options.
