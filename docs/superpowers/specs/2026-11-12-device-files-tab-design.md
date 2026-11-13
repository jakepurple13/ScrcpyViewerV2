# Files tab in the mirror window

Date: 2026-11-12
Status: design, approved through the mockup at https://claude.ai/artifact/XQFtYPCQrNg9To2FQWs9oE (version 1)

## Intent

Today the only way to move files to the device is to drop them on the mirror (or use Push file…), and they always land
in the push target (`/sdcard/Download/` by default). There is no way to see what is on the device, pull anything back,
or tidy up. The quick actions sheet gains a third tab, **Files**, beside Actions and Shell: a small file explorer for
the mirrored device's shared storage that behaves like the file explorer on the user's computer.

## Goals

- Browse the device's shared storage (`/sdcard`) folder by folder.
- Pull files and folders to the computer: by button, menu, double-click (pull and open), and by dragging rows out of
  the window onto Finder, Explorer, or the desktop.
- Push files and folders from the computer: by the push button (file picker), and by dragging them onto the list (into
  the current folder) or onto a folder row (into that folder).
- Move, rename, and delete files and folders, and create folders.
- Every state change animates, in the sheet's existing motion language.

## Non-goals

- Root access, or anything outside `/sdcard`. Folders Android hides from adb (`Android/data`, `Android/obb` on
  Android 11+) show a "blocked" state; the explorer does not try to work around it.
- Copy (duplicate) on the device. Move, rename, and delete only.
- Thumbnails or previews. Rows show a kind icon; opening a file pulls it and hands it to the computer's default app.
- Editing files in place, search across folders (the filter covers the current folder only), or a trash/undo for
  delete.
- A grid view. The list is the only view.
- Changing what a drop on the mirror image does. It still pushes to the push target, or installs APKs, as today.

## Anti-goals

- Deleting anything without a confirmation step.
- A transfer the user cannot see. Every pull, push, and install the tab starts shows in the transfers tray, with a way to
  cancel it.
- The list jumping: no row appears, disappears, or changes place without motion.
- A file name reaching the device shell unquoted. Names with spaces, quotes, `$`, backticks, or newlines are treated as
  data, never as shell syntax.

## The sheet

### Tabs

The segmented control under the header becomes three segments: **Actions**, **Shell**, **Files**. The highlight glides
between thirds with today's timing (open timing when moving right, close timing when moving left). Shell keeps its red
"command running" dot. Files shows a small count badge (accent color) while any transfer from the window's queue is
running, so a transfer started from the Files tab stays visible from the other tabs. The badge scales in and out.

`SheetTab` gains `Files`. The tab switch (`slideTransform`) slides by tab order rather than "to Shell or not": moving to a
tab on the right enters from the right with the open timing; moving left enters from the left with the close timing.

The header subtitle follows the tab: "Runs adb shell on this device" on Actions and Shell, "Browse and move files on
this device" on Files.

### Width

Files has its own width, default and minimum **440 dp**, saved as `filesWidth` beside `quickActionsWidth` and
`shellWidth`, with the same drag bar, clamping, reset, and window-growth rules as the other two tabs.
`SheetWidths` gains `files`.

### Esc

Esc goes to the innermost thing that can use it, in this order: an open menu, an open dialog, a rename field (cancels
the rename), the filter field (clears it), the selection (clears it). Only an Esc nothing used closes the sheet, as
today.

## Files tab layout

From top to bottom:

1. **Toolbar row.** Back (folder history), Up, the breadcrumb path, Refresh, New folder, Push files here (file picker),
   and a ⋮ menu. The breadcrumb starts at "Internal storage" (`/sdcard`) and scrolls horizontally, kept scrolled to its
   end. Each crumb is a button that opens that folder.
2. **Filter row.** A rounded filter field ("Filter in Download") and the device's free space (a thin meter and
   "41.2 GB free", from `df` on `/sdcard`).
3. **Column header.** Name, Size, Modified. Clicking a column sorts by it; clicking again reverses. The arrow rotates.
   Folders always sort before files.
4. **List.** One 40 dp row per entry: kind icon, name (ellipsized, full name in the tooltip), size (files) or item
   count (folders), and modified date and time (`Nov 8, 2:32 PM`). Hidden entries (dot names) are dimmed when shown.
5. **Selection bar.** Floats over the bottom of the list while anything is selected (see Selection).
6. **Transfers tray.** Docked at the bottom of the tab, collapsible (see Transfers).

A snackbar for results ("Deleted 2 items", "Moved notes.txt to Documents" with an Open action) shows at the bottom of
the tab, above the tray.

### Kind icons

Folder (accent color), image, video, APK, audio, and document, from the file extension. Each non-folder kind has a tinted
icon tile; the tints need checking against every palette and style (Glass, Soft, Retro), the way the rest of the sheet's
colors are.

### The ⋮ menu

- Show hidden files (check item).
- Sort by Name, Size, Modified (check items; the current one shows its direction).
- Copy folder path.

Sort and Show hidden files are saved in the app settings (`filesSort`, `filesShowHidden`) and apply to every device.

### Where it opens

The first time the tab opens for a device in this app run, it shows the push target folder (`/sdcard/Download/` by
default), so the explorer starts where dropped files land. After that the tab remembers each device's folder (and its
back history) for the life of the app, not across restarts, the same way the sheet remembers its tab.

## Browsing

- Double-click a folder, or press Enter (or ⌘↓) on it, to open it. Up (⌘↑) opens the parent. Back returns to the
  previous folder in this tab's history.
- Refresh lists the current folder again, keeping the filter. The tab also refreshes the current folder after every
  operation it runs and after each transfer into it finishes. It does not poll.
- While a listing runs, a thin indeterminate bar shows at the top of the list. The previous rows stay until the new
  ones are ready (no blank flash).
- Item counts for folders arrive in a second step after the list shows; until then the Size cell is empty, and the
  count fades in.

### States

- **Empty folder.** Open-folder icon, "Documents is empty", "Drag files here from your computer, or push them from a
  file picker.", and a Push files button.
- **Filter matches nothing.** "Nothing matches “abc”" and "The filter only searches this folder."
- **Blocked folder** (`Permission denied` or `Operation not permitted` from the listing). Lock icon, "Android blocks this
  folder", "Apps keep private files in /sdcard/Android/data. adb can’t list it without root.", and a Go up button. Drops
  onto a blocked folder are refused.
- **Device disconnected.** The list stays, dimmed, with a banner "Device disconnected"; every action is disabled until
  the session reconnects, then the folder refreshes. (Not in the mockup; it follows how the rest of the sheet behaves
  when the session drops.)
- **Listing failed** for any other reason: the adb error line and a Retry button.

## Selection

- Click selects one row. Shift-click extends from the anchor. ⌘-click (Ctrl-click on Windows and Linux) toggles a row.
  ⌘A selects every visible row. Click on empty space clears the selection.
- Up and Down move the selection; with Shift they extend it. The list is one focus stop; the focused row shows a
  focus ring.
- While anything is selected, the selection bar slides up over the bottom of the list (inverse surface colors): Clear,
  "2 selected", **Pull**, Rename (one item only), Delete, and ⋮ (the item menu). It slides away when the selection
  clears.

## Item menu (right-click)

On a row (the row joins or becomes the selection first):

| Item | When | Does |
| --- | --- | --- |
| Open | one folder | Opens it |
| Open on computer | one file | Pulls to a temp folder, then opens with the default app |
| Install on this device | one `.apk` | `adb install -r` of the device-side file (pulled to temp first) |
| Pull to Downloads | always | Pulls the selection to the computer's Downloads folder |
| Pull to… | always | Folder picker on the computer, then pulls there |
| Rename (F2) | one item | Rename in place |
| Move to… | always | Submenu: Internal storage, Download, DCIM/Camera, Documents, Pictures, Movies (minus the current folder) |
| Copy path / Copy paths | always | Device paths, one per line |
| Delete (⌫) | always | Delete confirmation |

On empty space: New folder (⇧⌘N), Push files here…, Refresh (⌘R), Copy folder path.

On a blocked or empty folder there is no item menu; empty space still offers New folder and Push files here (not on a
blocked folder).

## Operations

All device-side commands run through the existing `adb shell` path for this serial, with every path single-quoted by
one shared quoting function (unit tested with spaces, quotes, `$`, backticks, newlines, and non-ASCII names).

| Operation | Command (shape) | After |
| --- | --- | --- |
| List | a listing that yields type, size, mtime, and name per entry (builder's choice, see Constraints) | — |
| Item counts | one command for all child folders of the listing | counts fade in |
| Free space | `df` on `/sdcard` | — |
| New folder | `mkdir` | row grows in |
| Rename | `mv` within the folder | row updates in place, list re-sorts with motion |
| Move | `mv` into the target folder | rows collapse out; snackbar with Open |
| Delete | `rm -rf` | rows collapse out; snackbar |
| Pull | `adb pull` | file appears on the computer |
| Push | `adb push` into the chosen folder | row grows in if the folder is showing |
| Install | `adb install -r` | snackbar |

After push, move, rename, and delete, the tab sends the existing media scan (`ControlMessage.ScanFile`) for the affected
folder, so the device's gallery catches up, as today's push does.

### New folder

New folder adds a row named "New folder" (or "New folder (1)" and so on) at its sorted place, grows it in, selects it,
and puts it straight into rename with the whole name selected. Enter (or clicking away) creates it with that name
(`mkdir` runs then, not before). Esc removes the row again with the collapse motion; nothing runs on the device.

### Rename

F2, Enter on a selected file, the selection bar, or the menu turns the name into a text field, with the name selected up
to the extension (whole name for folders). Enter or clicking away commits; Esc cancels. An empty name, a name with `/`,
or a name already in the folder is refused in place: the field shakes and an inline note says why ("Name already
used", "No “/” in names"). The field stays open.

Enter on a single selected **file** renames it (as in Finder); Enter on a single selected **folder** opens it, since
browsing folders is the more common action. ⌘↓ opens either.

### Delete

Delete (⌫ or Delete key), the selection bar, or the menu opens a dialog inside the sheet:

- Title: "Delete “notes.txt”?" or "Delete 3 items?"
- A list of up to four names (folders with a trailing `/`), then "and N more".
- "Folders are deleted with everything inside." when a folder is included, then "This removes it from the phone and
  can’t be undone."
- Cancel, and a filled error-color **Delete** button. Focus starts on Delete; Esc or a click on the scrim cancels.

### Name clashes on push and move

When pushed or moved items have names already in the target folder, one dialog covers the whole batch: "“notes.txt” is
already in Download" or "2 files are already in Download", with **Skip**, **Keep both** (adds " (1)", " (2)" before the
extension), and **Replace**. Items without a clash go ahead whatever the answer.

A folder cannot be moved into itself or its own subfolders; those items are left out silently from a drop and disabled
in Move to….

## Drag and drop

### Into the tab (push)

Files and folders dragged from the computer:

- **Over a folder row:** the row lights up (primary container, outlined; its icon grows slightly) and a chip at the
  bottom of the list says "Push into Camera". Dropping pushes into that folder.
- **Holding over a folder row for 800 ms** springs it open (a thin progress line fills along the row's bottom edge
  first); the drag continues in the opened folder.
- **Over empty space or a file row:** the whole list gets an outline and tint, and the chip says "Push to
  /sdcard/DCIM/Camera/". Dropping pushes into the current folder.
- **Over a breadcrumb:** the crumb lights up; dropping pushes into that folder.
- APKs dropped on the Files list are pushed like any file, not installed. (Dropping on the mirror image still installs
  them.)
- Drops are copies, never moves, using the existing copy-only rule (`copyDropAction`), so Explorer never deletes the
  originals.

### Inside the tab (move)

Dragging selected rows onto a folder row or a breadcrumb moves them there ("Move into Camera" chip). Dragged rows dim
while the drag runs. Dropping on empty space in the same folder does nothing.

### Out of the window (pull)

Dragging selected rows out of the window onto Finder, Explorer, a file manager, or the desktop copies them there.

The Tao backend's outbound drag (`TaoDragAndDropManager`, `OutboundRequest(files: List<File>, …)`) only carries files
that already exist on the computer, so the pull has to happen before the OS drag starts:

1. The drag starts inside the window as an in-app drag (the same gesture as a move), with a ghost pill: download icon
   and "1 file" / "3 items".
2. At the same moment, the selection is pulled into a per-session drag cache
   (`<temp>/scrcpy-vwr/<serial>/drag/<random>/`). The ghost shows the pull's progress.
3. When the pointer leaves the window **and** the pull has finished, the app hands the cached files to the OS drag, and
   the OS drop copies them wherever the user releases.
4. If the pointer leaves before the pull has finished, the ghost stays at the window edge showing progress, and the OS
   drag starts as soon as the files are ready, if the button is still held.
5. Releasing before the files are ready cancels the drag and the pull, and leaves nothing on the computer.
6. The cache is deleted when the window closes and at app start (leftovers from a crash).

Whether Tao lets an outbound drag begin while the pointer is already outside the window, and on all three platforms, is
the first thing to prove (see Left to the builder). If it cannot, the fallback is: the OS drag starts the moment the
pull finishes, wherever the pointer is; small files feel immediate, large ones show the ghost's progress first.

Pull to Downloads, Pull to…, and the selection bar's Pull work without any drag.

### The mirror image

Unchanged: files dropped on the mirror image push to the push target (APKs install), with today's overlay.

## Transfers

The tab uses the window's existing transfer queue (`FileTransferQueue` via `rememberFileTransfers`), so today's drops on
the mirror and the Files tab's work share one queue, one progress label, and one set of results. The queue grows to
carry what the tab needs:

- `TransferKind.Pull`, with a host destination.
- A device folder per push request (today every push goes to the session's single `pushTarget`).
- Installing a device-side APK (pull to temp, then install).
- Cancelling one request: the running one (its adb process killed, partial files removed) or a queued one.
- Byte progress where it can be measured (the growing local file for a pull; the source size and elapsed time for a
  push). Where it cannot, the row shows an indeterminate bar.

### The tray

A strip docked at the bottom of the Files tab: "Transfers · 2 running" and a collapse chevron. Each transfer is a row:
direction icon (up for push, down for pull, Android for install, open-in-new for Open on computer), name, "· To Download"
or "· To Downloads", a progress bar, "48.2 MB of 184.5 MB · 34.8 MB/s", and a cancel button. A finished row turns its bar
green, shows "done" and a check, and collapses out after about 2.5 seconds. Failures stay with the adb error line until
dismissed. With nothing transferring, the tray collapses to its header line: "Nothing transferring. Pulls, pushes and
installs show here."

The tray also shows transfers started by drops on the mirror image.

## Keyboard

| Key | Does |
| --- | --- |
| ↑ / ↓, ⇧↑ / ⇧↓ | Move / extend the selection |
| Enter | Open a folder; rename a file |
| ⌘↓ | Open (folder: open; file: open on computer; APK: install) |
| ⌘↑ | Up one folder |
| F2 | Rename |
| ⌫ / Delete | Delete (confirmation) |
| ⌘A | Select all |
| ⇧⌘N | New folder |
| ⌘R | Refresh |
| Esc | See "Esc" above |
| ↓ in the filter | Moves focus to the list |

Ctrl replaces ⌘ on Windows and Linux.

## Motion

All timings use the existing tokens: open 260 ms emphasized-decelerate, close 200 ms emphasized-accelerate, unless
noted. Every one honors the app's reduced-motion handling.

| Change | Motion |
| --- | --- |
| Switch to or from Files | Existing tab slide (by tab order), highlight glides, sheet width animates |
| Open a folder | Shared X axis: old list slides 32 dp left and fades (150 ms linear); new list enters from 32 dp right (open timing, 50 ms delay); up to 14 rows stagger in 16 ms apart, rising 6 dp |
| Up / Back / crumb | The same, mirrored |
| New crumb | Slides in 10 dp from the right and fades in |
| Sort, filter, rename, show hidden | Rows that stay glide from their old place to the new one (FLIP), 300 ms decelerate; rows that appear fade in |
| Row added (push done, new folder, move into view) | Height 0 → 40 dp with fade (open timing), then a primary-container highlight that fades out over 1.4 s |
| Row removed (delete, move out, cancelled new folder) | Height to 0, fade, slide 16 dp left (close timing), 30 ms stagger |
| Selection bar | Slides up 70 dp and fades in (open timing); out with close timing |
| Drop target row | Background and outline fade in; icon scales to 112% |
| Spring-open | Line fills the row's bottom edge over 800 ms, then the folder opens with the forward motion |
| Drop chip | Rises 8 dp and fades in |
| Drag ghost | Follows the pointer; shows the drag cache pull's progress |
| Menu | Scales from 92% at the pointer and fades (150 ms decelerate); out 110 ms |
| Dialog | Scrim fades; dialog scales from 92% and rises 8 dp (open timing); out 150 ms |
| Rename refused | Field shakes 4 dp (180 ms) |
| Transfer row in / out | Rises 8 dp and fades in; collapses out (close timing) |
| Tray collapse | Height animates (open timing); chevron rotates |
| Files badge | Scales in and out |
| Refresh button | Icon spins once (500 ms decelerate) |
| Folder counts arrive | Fade in |
| Snackbar | Rises 16 dp and fades in; out the same way |

## Constraints from the existing code

- `QuickActionsPanel`, `SheetTabs`, and `slideTransform` assume two tabs (`toShell`). The tab order, the highlight's
  third-width math, and the width per tab (`SheetWidths`, `clampSheetWidth`, `fromSettings`, the window growth in
  `MirrorWindow`) all need the third tab.
- `FileTransferQueue` has one fixed `pushTarget`, no pull, no per-request cancel, and only index/total progress
  (see Transfers).
- The window's drop target (`CopyOnlyFileDropTarget`) covers the mirror; the Files list needs its own drop targets
  (list, rows, crumbs) that take precedence while the pointer is over the sheet, without breaking the mirror's.
- The Tao backend has no AWT toolkit. Opening a pulled file with the default app, and the drag-out, must go through
  what the Tao backend offers, not `java.awt.Desktop` or AWT `DragSource`.
- Listing output differs across Android versions (toybox `ls`, `stat`, and `find` flags changed between Android 7 and
  15). Parsing lives in one pure function with fixtures from several versions, including names with spaces, newlines,
  and non-ASCII characters, symlinks (shown as their target's kind, with a link badge left to the builder), and
  `Permission denied` lines.
- Styles: the tab's surfaces, rows, menus, dialogs, and chips go through the same styled controls and tokens as the rest
  of the sheet, so Glass, Soft, Retro, and every palette work.

## Testing

- Unit: path quoting; the listing parser (fixtures per Android version); size and date formatting; sort order; the
  selection model (click, shift, ⌘, keyboard, select all); unique names for Keep both and New folder; rename
  validation; move-into-self exclusion; the clash split (clashing vs. not).
- Queue: pull, per-folder push, cancel running and queued, device-side install, results and progress, with a fake
  `AdbClient`.
- UI (Compose tests): tab switch to Files and its width; folder navigation and history; empty, blocked, and failed
  states; selection bar appearing; delete dialog flow; rename and new-folder commit/cancel; Esc order.
- Manual, on the Samsung test device (`R5CX12GDWPZ`, via `cdm acquire --android --device R5CX12GDWPZ`): push by drop
  onto list, row, and crumb; spring-open; pull by button, menu, double-click, and drag to Finder; move by drag; rename;
  delete; new folder; `Android/data` blocked state; disconnect mid-transfer. Windows and Linux drag-out need a hand check
  too.

## Left to the builder

- **Spike first:** the drag-out under Tao on macOS (then Windows and Linux): start an `OutboundRequest` with cached files
  from a drag that began in-window, decide between "start at the window edge" and "start when ready", and confirm the
  OS treats the drop as a copy. Report back before building the rest of drag-out.
- The exact listing and item-count commands, given the parser constraints above.
- How byte progress is measured for push.
- Whether the "Device disconnected" banner reuses an existing component.
