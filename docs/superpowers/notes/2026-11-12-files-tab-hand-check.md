# Files tab: macOS hand check

Run this on the Samsung SM-A156U1 (`R5CX12GDWPZ`), connected by USB, with `cdm acquire --android --device R5CX12GDWPZ`
first. Run the app from the worktree (`./gradlew :composeApp:run`, with an isolated `-PdataDir` if you like), open a
mirror window, open the sheet and pick the Files tab (order: Actions, Apps, Shell, Files).

Legend: **[auto]** verified by a test or an adb run on 2026-11-12; **[hands]** needs you.

## Verified without hands

- **[auto]** `DeviceFilesDeviceTest` (2 tests) and `FileTransferDeviceTest` (1 test) pass on the Samsung
  (`./gradlew :composeApp:jvmTest -PdeviceTests --tests '*DeviceFilesDeviceTest' --tests '*FileTransferDeviceTest' -PdeviceSerials=R5CX12GDWPZ`):
  mkdir, list, rename, move, delete of a name with quotes and `$`; clash errors and replace; replace never deletes an
  item moved onto itself; push, media scan and delete.
- **[auto]** The listing command (`listCommand`) run through `adb shell` on `/sdcard/Download` returns stat records
  (exit 0).
- **[auto]** Screenshots at the sheet's 440 dp width in Material light and dark (selection, empty, blocked, delete
  dialog, running transfer) and the selection in Glass and Retro, light and dark. Looked at all of them.
- **[auto]** Compose-level behavior (selection, keys, menus, drop targets, drag-out controller, tray) is covered by the
  unit and scene tests in `ui/mirror/files/`.

Finding: on this Samsung (Android 16, shell user in group `ext_data_rw`) `/sdcard/Android/data` **is listable** from
`adb shell` (`ls -A` exits 0), so the Blocked state does not appear there. See item 9 below.

## A. Browsing

1. Open Files. It starts in a folder; the breadcrumb, free space and column header show. Click a folder: it slides in.
   Back, Up and the crumbs navigate. Sort by each column header; the arrow flips.
2. Type in the filter; Esc clears it. Hidden-files toggle in the overflow menu shows `.nomedia`-style names.
3. A folder with thousands of files (for example a camera folder) scrolls smoothly.
4. **Wi-Fi device:** connect the phone over Wi-Fi adb and repeat 1 to 3, then a push and a pull. Listing and transfers
   should work as over USB.

## B. Push

5. **Drop on the open list:** drag a file from Finder onto empty list space. The list outlines; dropping pushes into
   the open folder, and the tray shows progress.
6. **Drop on a folder row:** hover a folder row while dragging from Finder. The row lights up and a line fills its
   bottom edge. Drop pushes into that folder. This checks the reflection-based drag position under Tao: the row
   under the pointer must be the row that lights up, also near the top and bottom of the list and after scrolling.
7. **Drop on a breadcrumb:** drop on a parent crumb; the file lands in that folder.
8. **Spring-open:** hold a drag over a folder row until the line fills; the folder opens and the drag goes on. Drop
   inside.
9. **Push button:** Push files here (toolbar and the empty-folder button) opens the picker and pushes.

## C. Pull

10. Pull by the selection bar button, by the context menu ("Pull to Downloads", "Pull to…"), and by double-click on a
    file. Each ends in the tray with Done and the file on disk.
11. **Open on computer:** use the menu item or double-click so the file opens in its **default app** on macOS. The
    Compose `UriHandler` under Tao is unverified: the file must actually open, and the snackbar then says "Opened
    <name> on your computer". If nothing opens, the snackbar must say it could not.
12. Pull a folder: it arrives with its contents.

## D. Drag out to Finder

Open the Files tab on a folder with a small file, a large file (100 MB or more) and a folder. Keep a Finder window open
next to the app.

13. **Small file, drag to Finder.** Press a small file row, drag left out of the sheet onto the stage, and keep holding. Expected:
    - The ghost sits at the sheet's left edge with a progress line.
    - The OS drag starts almost at once, showing the Finder file icon and no pill.
    - Drag onto Finder and release. The file appears in Finder.
    - The device file and the app list are unchanged. No snackbar says "Pulled".
    - After the drop, move the mouse over rows: no ghost, nothing moves.
    - Click a row: it selects normally (this checks for no stale "pressed" state).
14. **Large file, release early.** Drag the large file out, see the progress line fill, and release before it is done. Expected:
    - The ghost goes and no OS drag starts.
    - `ls $TMPDIR/scrcpy-vwr/<serial>/drag/` is empty within about a second of the cancelled pull ending.
15. **Large file, come back.** Drag it out, then back over a folder row before the pull finishes. Expected:
    - The progress line goes and the chip says "Move into X".
    - Releasing moves the file on the device. The drag dir is gone.
16. **Large file, hold until ready.** Drag it out and hold still, even outside the window. Expected:
    - When the pull finishes, the OS drag starts by itself. Note where the icon appears relative to the cursor.
    - Dropping on Finder copies the file.
    - The drag dir is deleted about 30 s later.
17. **Folder.** Drag a folder out. Expected: the progress line is an indeterminate sweep, and the drop creates the folder with its contents.
18. **Several selected rows.** Select 2 files and drag them out. Expected: the ghost says "2 items" and both land in Finder.
19. **Esc and disconnect.** Press Esc while pulling, and separately unplug the device or turn off Wi-Fi adb while pulling. Expected: each cancels, leaves no drag dir, and starts no OS drag.
20. **Window close and app start.**
    - Start a large drag-out, release outside Finder, and close the mirror window within 30 s: `drag/` for that serial is gone.
    - Kill the app with `kill -9` during a pull, restart it, and check that `$TMPDIR/scrcpy-vwr/*/drag` is gone.

If the OS drag misbehaves under Tao, run the spike harness (git-ignored, in `.superpowers/spike/drag-out/`) to
separate the harness from the app; its questions and boxes are in `2026-11-12-tao-drag-out-spike.md`:

```bash
./gradlew -I .superpowers/spike/drag-out/spike.init.gradle :composeApp:runDragOutSpike --no-configuration-cache
```

## E. Move, rename, delete, new folder

21. **Move by drag:** select rows and drag them onto a folder row or a parent crumb inside the tab. They move on the
    device; Esc during the drag ends it with nothing moved.
22. **Name clash:** move (or rename) onto an existing name. The clash dialog offers Skip / Keep both / Replace (Skip is focused); each choice does what it says, and Esc cancels.
23. **Rename:** F2 or the menu starts the field with the stem selected. Enter commits. An invalid or clashing name
    shows the error beside the field. **Click-away:** clicking elsewhere (another row, the toolbar, the filter) commits
    the name (blur commit); Esc cancels.
24. **New folder:** the toolbar button or Cmd+Shift+N adds a row in rename mode. Enter creates it; click-away commits;
    Esc removes the pending row without creating anything.
25. **Delete:** Delete or the menu opens the confirmation (folders warn about their contents). Cancel and Esc leave
    everything; Delete removes the items on the device.

## F. Menus and keys

26. **Right-click menus:** right-click a row, empty list space, and the selection bar's overflow. Menus open at the
    pointer and items work. **Esc while a menu is open** closes only the menu (not the selection, not the sheet).
    Popup focus under Tao is unverified: keys must still reach the list after the menu closes.
27. Keyboard: arrows, Shift+arrows, Cmd+A, Enter, Cmd+Up (parent), Cmd+Down (open), F2 (rename), Backspace or Delete (delete dialog), Cmd+R (refresh); Esc steps out of the
    innermost thing: menu, dialog, rename, filter, selection, then the sheet.

## G. States and failure

28. **Blocked folder:** open `Android/data`. Expected on a device that blocks it: the lock state with "Go up". This
    Samsung lists it (see above), so try `/sdcard/Android/obb`, or another device (Pixel on Android 13+) to see the
    Blocked state.
29. **Disconnect mid-transfer:** unplug USB during a large push or pull. The tray row fails with a reason, the list
    dims with the "Device disconnected" banner, and actions are disabled. Replug: the browser recovers on Refresh.
30. A failed listing (for example a folder deleted by another process) shows the retry state; Retry works.

## Needs a Windows or Linux machine

- Drag-out to Explorer / the file manager (OS drag under Tao on each platform; copy semantics; icon).
- Drop from Explorer / the file manager onto list, row and crumb (reflection-based drag position under Tao).
- "Open on computer" in the default app (Windows `UriHandler`, Linux `xdg-open`).
- Right-click menus and Esc with a menu open (popup focus).
- Shortcuts with Ctrl instead of Cmd (Ctrl+A, Ctrl+R, Ctrl+Shift+N), and F2 for rename.
- The temp drag folder cleanup (`%TEMP%` / `$TMPDIR`) on window close and on app restart after a kill.
- Windows: file names with characters Windows forbids but Android allows (`:` `?`) on pull and drag-out.
