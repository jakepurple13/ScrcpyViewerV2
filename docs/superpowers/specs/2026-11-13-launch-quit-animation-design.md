# Launch and quit animations

Date: 2026-11-13
Status: design, directions picked from the mockup at https://claude.ai/artifact/JKYh6cH9ABtr1ynwdU5mHe (version 1,
variants A and C)

## Intent

The main window appears and disappears with no motion today: it pops in on launch and vanishes on quit. Every other
window change in the app already animates (mirror windows grow out of the Mirror button and their screen sleeps to a
line on close; theme changes reveal in a circle; navigation uses shared-axis transitions). Launch and quit are the last
two unanimated moments, and they are the first and last thing a user sees.

The user picked two directions from the mockup and wants both, as a choice in Settings:

- **Icon unfold** (variant A, the default). The app icon appears where the window will open, its back phone slides out
  from behind the front one and the front phone's screen lights teal, then the window grows out of that screen. Quit
  runs it backwards: the window folds into the phone screen, the phones stack, and the icon shrinks away.
- **Screen on** (variant C). A teal scan line sweeps down the window's area and paints the window in behind it, like a
  mirror's first frame arriving. Quit is an old-TV power-off: the window squashes to a bright line, then to a dot, then
  disappears.
- **Off**: today's behaviour.

## Goals

- A new setting, "Launch & quit animation", with three choices: Icon unfold (default), Screen on, Off.
- Both animations on macOS, Windows, and Linux, looking the same on each.
- Quit plays the animation only after the app's existing shutdown work is done (sessions closed, recordings finished),
  directly before the process exits.
- With the OS's reduce-motion preference on, both animations become a 150 ms fade.

## Non-goals

- Animating mirror windows. They keep their own motion and their own switch (`animateMirrorWindows`).
- Animating minimize, restore, maximize, or full screen.
- Animating the "Finishing recording" window or the onboarding guide.
- Variants B and D from the mockup.
- A preview of the animation inside Settings. Choosing a value takes effect on the next launch or quit.

## Anti-goals

- A flicker, a blank frame, or a visible jump at the hand-off between the overlay and the real window. If the hand-off
  cannot be made clean on a platform, that platform falls back to the fade instead of shipping a flicker.
- Making quit feel slow. The quit animation never runs longer than 420 ms, and it never delays a pending update's
  installer by more than that.
- Changing how the main window behaves the rest of the time: its resize edges, snapping, shadow, Glass mode, and focus
  stay exactly as they are.
- Stealing focus or clicks. The overlay never takes keyboard focus, never shows in the Dock, taskbar, or Alt-Tab /
  Cmd-Tab, and never receives a click.
- Launch blocking on the animation. The app's startup work (Koin, update check, adb) runs in parallel; the animation is
  only what the user sees.

## Constraints

- The window backend is Nucleus 2.5.18 on Tao (`nucleus.decorated-window-tao`). There is no AWT. A Tao window can only
  be made see-through when it is created (see `AppWindow`, where `transparent` is fixed by `remember`).
- The app has only ever created see-through windows on macOS (Glass mode). Whether a see-through, borderless,
  click-through, always-on-top window works on Windows and on Linux (X11 and Wayland, with a compositor) under Tao is
  unproven.
- `AppShutdown` owns quitting. Its `run()` closes sessions, releases the macOS quit hook, runs `install` on the IO
  dispatcher, then calls `exit`. `install` ends the process with `System.exit` when an update is pending, so the quit
  animation must run before `install`, not after it.
- macOS Reduce Motion is already read in `MirrorWindowMotion.kt` (`accessibilityDisplayShouldReduceMotion`). Windows
  (`SPI_GETCLIENTAREAANIMATION`) and Linux (GNOME `org.gnome.desktop.interface enable-animations`) have no reader yet.
- Settings persist through `Settings` in `AppSettings.kt` (kotlinx.serialization JSON). Enum-like settings are stored as
  strings so a removed value never corrupts the file (`themePalette`, `themeStyle`).
- The project's motion vocabulary: M3 emphasized curves (`0.2,0,0,1`; decelerate `0.05,0.7,0.1,1`; accelerate
  `0.3,0,0.8,0.15`), already used in `MirrorWindowMotion.kt`.
- Testing: unit tests run in CI on macOS, Windows, and Ubuntu (`jvmTest`). What the animation looks like on Windows and
  Linux can only be checked by the user on real machines.

## Approaches

1. **Overlay window (picked).** On launch and on quit, a short-lived window opens exactly over the main window's bounds:
   see-through, borderless, always on top, not focusable, ignoring clicks, absent from the taskbar. The animation is
   drawn in it, using the icon and a picture of the main window. At the end of launch the real window is shown and the
   overlay closed in the same frame; on quit a picture of the real window is taken, the real window is hidden, and the
   overlay animates the picture. The main window stays an ordinary solid window, so nothing about its everyday behaviour
   changes on any OS. The cost is the hand-off between two windows, which must land on the same frame.
2. **Main window see-through all the time.** Animate the live content with Compose clips and transforms. Least code, but
   the window is see-through for its whole life: on Windows that risks losing the native shadow and resize edges, and on
   Linux without a compositor it can render black. This is why Glass mode is macOS-only today. Rejected for the
   everyday cost of a two-second effect.
3. **Animate the native window** (alpha and frame, as mirror windows do). Mostly exists already, but it cannot draw the
   clip shapes both directions need: growing out of a phone screen, or squashing to a line, would re-lay out the app at
   a tiny size. It can only give fades and resizes. Rejected; it is not what the user picked. It remains the fallback
   for the 150 ms fade.

## Design

### Setting

- `Settings.launchQuitAnimation: String = "IconUnfold"`, read through an enum `LaunchQuitAnimation { IconUnfold,
  ScreenOn, Off }` with `fromName` falling back to `IconUnfold` for unknown values.
- Settings → Appearance, next to "Animate mirror windows": a row "Launch & quit animation" with a three-option control
  (segmented buttons or a dropdown, matching the nearest existing three-way control in Settings). Supporting text:
  "Plays when Scrcpy Vwr opens and quits."
- Launch reads the value saved by the previous run. Changing it takes effect at the next quit.

### Launch

1. The main window is composed hidden (`visible = false`) at its normal bounds.
2. The overlay opens over those bounds and starts the chosen animation at once, so there is motion on screen as soon as
   possible.
3. Once the main window has drawn its first frame, a picture of it is taken for the overlay to reveal. Until the
   picture exists, the overlay draws the window as a plain shape in the theme's surface color with the title bar
   color on top.
4. At the animation's end the real window is shown, and the overlay closes on the frame after the real window has
   drawn. The device cards then fade up in a 40 ms stagger inside the real window (Icon unfold only), as in the mockup.

Timings, from the mockup:

- Icon unfold, 720 ms: icon scales 0.6 → 1 (0–260, decelerate); back phone slides out and screen flashes teal
  (140–500); window grows from the phone screen as a rounded clip (360–700, emphasized); card stagger (520–720).
- Screen on, 650 ms: a 3 px teal line with a glow sweeps from the window's top to its bottom, revealing the window
  behind it (0–520, `0.4,0,0.2,1`); the cards get a one-frame brightness flash (520–650).

### Quit

**The main window while shutdown work runs (user's decision).** Today `Main.kt` hides the main window as soon as quit
is requested (`visible = shuttingDown == null`), and `FinishingRecordingWindow` shows alone while recorders finish.
With Icon unfold or Screen on active (and not falling back to the fade), this changes:

- No mirror sessions open: the animation starts at once; there is no work to wait for.
- Sessions open or a recording finishing: the main window stays visible, dimmed (a scrim over its content, inputs
  blocked), with the "Finishing recording" message shown inside it instead of in the separate
  `FinishingRecordingWindow`. When the work is done the animation plays from that window.

With Off, or where the fade fallback applies, quit keeps today's flow unchanged (window hidden at request,
`FinishingRecordingWindow` while recorders finish) and then exits; the fade, where it applies, runs at request time.
*(Builder's call, recorded here: the new in-window wait only exists to give the animation a window to start from.)*

`AppShutdown.run()` gains an `animateOut` step between `quitHook.release()` and `install`:

1. Take a picture of the main window (it is still visible, see above; the dimming scrim is removed on the frame
   before the picture).
2. Open the overlay over the main window's bounds showing that picture, then hide the main window on the frame the
   overlay has drawn.
3. Play the chosen animation, then return. `install` and `exit` follow as today.

Timings:

- Icon unfold, 420 ms: window folds into the phone-screen rect (0–260, accelerate); icon appears, then shrinks and fades
  toward the window's bottom centre (160–420).
- Screen on, 380 ms: squash to a 1 px bright line (0–220), line to a dot (220–320), fade out (320–380), on the curve
  `0.5,0,0.75,0`.

If the overlay cannot be created, or `animateOut` throws, quit proceeds without animation. `animateOut` is bounded by a
1 s timeout so a stuck animation never blocks exit.

### Reduce motion and fallback

- When the OS asks for reduced motion, both directions use a 150 ms opacity fade of the real window (native alpha, as
  mirror windows do on macOS; where native alpha is unavailable, no animation).
- A platform where the overlay spike fails (see Risks) uses the same fade for the chosen setting, and Settings still
  shows the choice.
- **Glass with desktop blur (macOS) uses the fade (user's decision).** A picture of a Glass window has clear pixels
  where AppKit's blur shows, so the overlay could not match the real window. Glass without blur, and every other
  style, get the chosen animation.
- Which platforms use the overlay is one switch per platform in code (`macOS`, `Windows`, `Linux X11`,
  `Linux Wayland`). macOS is on. Windows and both Linux sessions start off (fade) and are turned on only after the
  user confirms the spike on that platform. *(Builder's call.)*

### Where it lives

A new package `ui/window/launch/` holds the overlay window, the two animations as composables driven by one progress
value, and the hand-off. `Main.kt` wires launch; `AppShutdown` gets the `animateOut` suspend function injected the way
`install` is today, so its tests keep using a no-op.

## Risks

- **See-through overlay on Windows and Linux under Tao is unproven.** The first step of the plan is a throwaway spike
  that opens such a window on each OS and shows a moving shape over the desktop, with clicks passing through. The user
  checks it by hand on a Windows machine and a Linux machine (X11 and Wayland sessions checked separately). The plan
  stops after the spike and waits for the user's verdict per platform; the outcome sets the per-platform switch above.
  *(Builder's call.)*
- **Hand-off flicker.** The spike also checks showing the real window and closing the overlay in one frame on each OS.
- **Multiple monitors and HiDPI.** The overlay must use the same screen and scale as the main window.

## Left to the builder

- How the picture of the window is taken (Compose `GraphicsLayer.toImageBitmap` on the window's root is the likely route).
- How the overlay is created within Nucleus/Tao (a Nucleus window type or a raw Tao window), and how click-through and
  taskbar hiding are set per OS.
- The Windows and Linux reduce-motion readers.
- Exact easing per sub-step where the timings above leave it open.
- Tests: the setting's persistence and fallback, the shutdown ordering (`animateOut` runs after sessions close and
  before `install`, and its failure or timeout does not block exit), and the animation's progress-to-frame mapping.
