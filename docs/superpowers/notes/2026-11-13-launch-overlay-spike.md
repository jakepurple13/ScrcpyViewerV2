# Launch overlay spike

Date: 2026-11-13. Throwaway test for `docs/superpowers/specs/2026-11-13-launch-quit-animation-design.md`: can a
see-through, click-through, non-focusable, always-on-top overlay window run the launch and quit animations over the
main window, and hand off to it cleanly, on each OS?

## How the overlay is made

Nucleus 2.5.18 already supports overlay windows on the Tao backend; no native code is needed. The overlay is a plain
`DecoratedWindow` with:

```kotlin
DecoratedWindow(
    onCloseRequest = {}, state = rememberWindowState(position = mainPosition, size = mainSize),
    resizable = false, focusable = false, alwaysOnTop = true,
    undecorated = true,   // no traffic lights / frame
    transparent = true,   // per-pixel alpha, creation-time only
    clickThrough = true,  // pointer events fall through
    forceX11 = isWaylandSession, // Wayland can't position a window; take an XWayland surface for this one window
) { ... }
```

`hiddenFromDock` is **not** used: on macOS it switches the whole app to the accessory policy (the app's own Dock icon
disappears). On Windows (`WS_EX_TOOLWINDOW`) and Linux X11 (skip-taskbar hint) it is per-window and should be set
there if the overlay shows in the taskbar (check below).

## Findings (macOS, Tao, Apple Silicon, Retina)

| Check | Result |
|---|---|
| See-through: desktop visible around the drawn shape | Yes (screenshots at 4× slow motion) |
| Quit picture: `GraphicsLayer.toImageBitmap()` of the main window's content | Yes, 1520×880 px for a 760×440 dp content area |
| Picture while the main window is still hidden (launch) | **Yes**: a hidden Tao window composes and draws into the layer; picture ready 130 ms after start |
| Launch hand-off: overlay ends, real window shown, real window's 2nd frame, overlay closed | 15 ms from overlay end to overlay closed; no blank frame seen |
| Quit hand-off: picture, overlay composed, main hidden on overlay's first frame | 22 ms from picture to main hidden |
| Click-through | Not exercised automatically (no click tool on this machine); the overlay logs `OVERLAY GOT A CLICK` if it ever receives a press. Check by hand. |
| Focus steal / Dock entry | No extra Dock icon (same process). Focus: check by hand. |

Notes for the build:
- `rememberGraphicsLayer()` must be called inside a window's content (it needs that window's `LocalGraphicsContext`).
- The picture is the content below the title bar; the title bar must be drawn separately or the layer must wrap
  the title bar too.

## Windows and Linux

Not run yet. To run (from the repo root, spike files committed in `docs/superpowers/notes/launch-overlay-spike/` until the feature ships):

```bash
# macOS / Linux X11
./gradlew -I docs/superpowers/notes/launch-overlay-spike/spike.init.gradle :composeApp:runOverlaySpike --no-configuration-cache -Pspike.slow=4
# Linux Wayland session
./gradlew -I docs/superpowers/notes/launch-overlay-spike/spike.init.gradle :composeApp:runOverlaySpike --no-configuration-cache -Pspike.slow=4 -Pspike.forceX11=true
# Windows
gradlew.bat -I docs\superpowers\notes\launch-overlay-spike\spike.init.gradle :composeApp:runOverlaySpike --no-configuration-cache -Pspike.slow=4
```

Add `-Pspike.auto=false` to stop the automatic quit and use the window's Quit button instead.

What to look for, per platform:

1. Teal rounded rect grows from the middle with the desktop visible around it (not black, not white).
2. The real window appears where the rect ended, with no blink.
3. While the rect is animating, clicking through it reaches the window underneath (the log never prints
   `OVERLAY GOT A CLICK`).
4. The overlay never takes keyboard focus and has no taskbar / Alt-Tab entry of its own.
5. Quit: the window squashes to a bright line, then a dot, over the desktop; the real window does not reappear.

| Platform | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| macOS | yes | yes | by hand | by hand | yes |
| Windows | | | | | |
| Linux X11 | | | | | |
| Linux Wayland (`forceX11`) | | | | | |
