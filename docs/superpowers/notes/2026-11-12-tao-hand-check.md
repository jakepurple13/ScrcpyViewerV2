# Tao migration: macOS hand check

Run this on the Samsung SM-A156U1 (`R5CX12GDWPZ`). Use the packaged app (`packageDistributionForCurrentOS`) where noted. Use an isolated `-PdataDir`.

## A. Startup
- The packaged app launches on the JDK 25 jlinked runtime: no missing-module error, and no hang at the first window.
- The Dock icon is the app icon.
- An old settings file that still has `useSystemTitleBar` loads.

## B. Resize (the main goal)
- Drag every edge and corner of the main and mirror windows, side by side with Claude Manager.
- Look for lag, stretch, flicker and stutter. There should be none.
- Look for an edge strip in each theme: Light on a dark system, Dark, Neon, and Glass with blur. There should be none.

## C. Aspect lock (must not regress)
- Turn Fit on, then drag fast for 10 s on every edge and corner. Expect no letterbox strip and no snap on release.
- Repeat with:
  - the sheet open on the Actions tab, and on the Shell tab;
  - the sheet just closed;
  - the phone rotated;
  - the window on the second display;
  - window animation on, then off.
- With `no-window-aspect-ratio-lock` set, the window resizes freely.
- Fullscreen and maximized windows have no lock.
- A window dragged taller than the screen corrects once the drag settles.

## D. Live video while resizing (must not regress)
- With the phone screen moving, drag hard for 10 s. The video never freezes or blanks.

## E. Keyboard, on real hardware
- Typing works in default mode and in `keyboard=uhid` mode.
- Cmd+V pastes into the phone, including with `legacy-paste`.
- **Gate:** Cmd+C, X, A and Z reach the phone. Tao's native Edit menu may take them first.
- Zoom keys work. Esc closes the sheet.
- Typing in the Shell tab does not reach the phone.
- Keys pressed while a dropdown is open go where expected.
- Hold a key, then Cmd+Tab away: the phone releases the key.
- Caps Lock syncs in uhid mode.

## F. Mouse
- Click, double-click, right-click and scroll all work.
- With `mouse=uhid`, the cursor is hidden and recentred while captured.
- The cursor comes back after the release key, after focus loss and after closing the window. Check this on both displays.

## G. Quit
- Cmd+Q works with a mirror focused, including in uhid keyboard mode.
- Quit works from the Dock and from the app menu.
- The main window's close button takes the same path.
- With a recording running, the "Finishing recording…" window shows and the recorded file plays.
- The process really exits. Check in Activity Monitor.

## H. Update install
- Package with `-PupdateFeed=http://127.0.0.1:<port>`.
- With a mirror open and a recording running, install the update.
- The app shuts down, the .app is replaced and relaunches, and no process is left hanging.

## I. File drop
- Drop an APK and other files on a mirror. The Finder originals remain, because the drop copies.
- The drop overlay animates.
- A drop while the phone is disconnected is refused.

## J. File pickers
- Open each picker: Install APK, Push file, preset import and export, action import and export, adb path, capture folder, AI skill folder.
- The mirror video keeps drawing while each picker is open.
- Cancel and choose both work.

## K. Clipboard
- Copy buttons work.
- Copy and paste in text fields work.
- Phone-to-PC clipboard sync works.

## L. Window motion
- The mirror grows from the Mirror button and shrinks back into it, on both displays.
- Near the right edge of the screen, the sheet grows and shrinks without jitter.

## M. Theme
- The theme reveal plays.
- Title-bar colours follow the theme.
- Neon glows.
- Retro95 uses Tahoma.
- Glass blur shows, and stays right after switching themes.

## N. Dialogs
- These open and look right: update, onboarding, pairing, template picker, icon picker, action import.

## O. Bring to front
- A mirror's Actions request brings the main window forward, including when it is minimized.
