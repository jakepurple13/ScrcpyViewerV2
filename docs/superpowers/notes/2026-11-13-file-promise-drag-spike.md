# File-promise drag-out spike (macOS)

Date: 2026-11-13. Backend: Tao (`nucleus.decorated-window-tao:2.5.18`), Compose Multiplatform 1.12.2, macOS on Apple
silicon. Device for the real pull: SM-A156U1 (R5CX12GDWPZ).

Question: can the Files tab start the OS drag at once and pull the file only after the drop, so a big file no longer
holds the button down for the whole pull (today's `DragOutController`: pull first, then Tao's outbound drag)?

Answer on macOS: **yes**, with `NSFilePromiseProvider`, driven over JNA (the app's `ObjC` helper) from a runtime-built
ObjC class. Tao's own outbound drag is not used for it. Windows and Linux were not tried (they would need
`CFSTR_FILEDESCRIPTOR`/`CFSTR_FILECONTENTS` virtual files and XDS respectively).

## How it works

1. `objc_allocateClassPair(NSObject, "SpikeFilePromiseDelegate")` with JNA `Callback` IMPs for:
   - `filePromiseProvider:fileNameForType:` (`@@:@@`): the file's name; called on the main thread at the drop.
   - `filePromiseProvider:writePromiseToURL:completionHandler:` (`v@:@@@?`): pulls into the URL Finder gives, then
     calls the block (`invoke` at offset 16: `invoke(block, NSError * or nil)`).
   - `operationQueueForFilePromiseProvider:` (`@@:@`): our own `NSOperationQueue` (QoS user-initiated), so writes run
     off the main thread, one thread per file, in parallel.
   - `draggingSession:sourceOperationMaskForDraggingContext:` (`Q@:@q`): `NSDragOperationCopy`.
   - `draggingSession:endedAtPoint:operation:` (`v@:@{CGPoint=dd}Q`): JNA's by-value `CGPoint` in a callback works.
   - Protocols `NSFilePromiseProviderDelegate` and `NSDraggingSource` added.
2. One `NSFilePromiseProvider` per file (`initWithFileType:@"public.data" delegate:`), `userInfo` = a key into a
   Kotlin map of promises, wrapped in an `NSDraggingItem` with `NSWorkspace iconForFileType:` as its image.
3. `[nsWindow.contentView beginDraggingSessionWithItems:event:source:]` with `NSApp.currentEvent`, called
   synchronously from Compose's drag-start callback.
4. Optional: an `NSProgress` (kind file, `NSProgressFileURLKey` = the destination, operation kind copying) published
   during each write.

## Results (hand test)

| Check | Result |
|---|---|
| Compose's pointer callbacks run on the AppKit main thread | Yes (`NSThread isMainThread` = true, `Thread-0`) |
| `NSApp.currentEvent` inside the drag callback | `NSEventTypeLeftMouseDragged` (6); `beginDraggingSession` accepts it |
| OS drag starts at once | Yes, ~2 ms after the drag slop; no file exists yet |
| Drop ends the drag at once | Yes, the session ends 5 to 70 ms after the drop, `operation=1` (copy); the write runs on |
| Fake 300 MB, ~10 s write | Whole file in `~/Downloads`, 10.9 s |
| Two files in one drag | Finder asks for both at once; written in parallel on two threads; both whole |
| Name clash | Finder picks the name itself (`spike-big 2.bin`, `3`, …) and passes it in the URL |
| Cancel (Esc / dropped nowhere) | `operation=0`; `fileNameForType` and `writePromiseToURL` never called: nothing pulled |
| Compose sees the pointer-up after the drag | Yes, after both a drop and a cancel (usually within 1 to 2 s; once 4.7 s) |
| Real pull: `adb exec-out cat` of a 355 MB file into the promised URL | Whole file in 23.8 s; md5 matches the device |

Finder's progress bar (hand test in the app, 2026-11-13):

| Write | Finder bar |
|---|---|
| Our process writes the file right after the drop (spike boxes A, B) | Yes |
| `adb pull` writes it (another process, file made once the queue runs it) | No |
| Our process streams `adb exec-out cat` (file made after a short delay) | No |
| We create the empty file first, then `adb pull` writes it | Yes |
| A folder (`public.folder`), `adb pull` makes it | Yes (indeterminate) |

So the app creates an empty file at Finder's path before queueing the pull, and removes it if the pull never ran
(cancelled or failed while queued). Passing the size up front alone did not bring the bar back.

A job cancelled from the tray while still queued leaves the queue's list (no `Cancelled` state): the promise treats a
vanished job as a cancel and hands Finder `NSUserCancelledError`, so no alert.

## Notes for the real feature

- The drag no longer needs the "pull first, button still held" rule or the drag folder: the pull writes straight into
  Finder's destination. `DragCache` stays for Windows and Linux.
- A promise must not block the main thread before the drag starts: the harness's `adb shell stat` cost ~130 ms. The
  Files tab already has the size from the listing.
- Reset the row-drag state on `draggingSession:endedAtPoint:operation:`, not on Compose's pointer-up (it can lag).
- The pull should go through the transfer queue (a job per file) so it shows progress and can be cancelled; a failed
  pull calls the completion block with an `NSError`, and Finder removes its placeholder.
- Folders: a promise is one file name. A folder row needs either one promise that writes a directory at the URL (to be
  tried) or the current pull-first flow.
- Keep the IMP callbacks and the delegate strongly reachable for the app's lifetime (JNA frees a collected callback's
  trampoline).

## Harness

Git-ignored, in `.superpowers/spike/file-promise/` (`src/spike/MacFilePromiseDrag.kt`, `src/spike/FilePromiseSpike.kt`,
`spike.init.gradle`). Run from the worktree root:

```bash
./gradlew -I .superpowers/spike/file-promise/spike.init.gradle :composeApp:runFilePromiseSpike --no-configuration-cache \
  -Pspike.devicePath=/sdcard/Download/<big file> -Pspike.adb=$(which adb) -Pspike.serial=<serial>
```

The run task uses the jvmTest classes but not its resources: `TestMainDispatcherFactory` (`loadPriority =
Int.MAX_VALUE`, Swing) would replace Tao's main dispatcher and the window never shows.
