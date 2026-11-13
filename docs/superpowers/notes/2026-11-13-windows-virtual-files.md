# Windows virtual-file drag-out

Date: 2026-11-13. Follows the macOS file-promise drag (`2026-11-13-file-promise-drag-spike.md`). Written on macOS:
**the drag itself has not been tried on Windows.** What is checked, and how, is listed below.

## What it does

Dragging Files-tab rows out of the window on Windows offers them as **virtual files**: an OLE data object with
`CFSTR_FILEDESCRIPTORW` (names, sizes, folders) and `CFSTR_FILECONTENTS` (one `IStream` per file). Nothing is pulled
before the drop. Explorer reads each stream after the drop and the bytes come straight from the device
(`adb exec:cat`), so Explorer's own copy dialog shows real progress. Each dragged row also gets a job in the tray
(`FileTransferQueue.track`), fed by the bytes read; cancelling it closes the row's streams.

Folders are included: the drag lists each folder row's tree in one device call (`find … -exec stat`) while the button
is held (the ghost shows it working), then starts the OS drag. Paths over 259 characters, or a listing that fails,
fall back to today's pull-first drag.

## How

| Piece | File | Checked by |
|---|---|---|
| Folder tree listing | `DeviceListing.kt` (`treeCommand`, `parseTree`), `DeviceFiles.tree` | Unit tests; device test on SM-A156U1 |
| Raw device stream | `AdbClient.openExecStream` | Device test (300 KB file, md5 matches) |
| Tray jobs outside the queue | `FileTransferQueue.track` | Unit tests |
| Rows to virtual files, per-row byte counts, cancel, short reads | `VirtualFileDrag.kt` (`VirtualDragBuilder`) | Unit tests |
| Controller: list, then drag; release while listing starts nothing | `DragOut.kt` (`beginVirtual`) | Unit tests |
| FILEGROUPDESCRIPTORW bytes | `WindowsVirtualFileDrag.kt` (`fileGroupDescriptor`) | Unit tests |
| COM objects (IDataObject, IDataObjectAsyncCapability, IStream) | `WindowsVirtualFileDrag.kt` | `WindowsDataObjectTest`: runs on the Windows CI runner only, drives the vtables (descriptor, a 70 KB stream read through the MTA proxy, async mode, EndOperation) |
| SHDoDragDrop and Explorer's behavior | `WindowsVirtualFileDragStarter` | **Not tested** |

COM details:
- The data object lives on Tao's UI thread (an STA: Tao calls `OleInitialize`). `SHDoDragDrop` with no drop source
  gives the shell's default one and its drag image (through `SetData`, which keeps HGLOBAL copies).
- `IDataObjectAsyncCapability` reports async mode, so Explorer copies on its own thread after the drop and the drag
  returns at once. `EndOperation` closes the rows' tray jobs (a row started but not finished ends cancelled).
- Streams are created on our own MTA thread and handed out as proxies, so Explorer's `Read` calls run on COM's RPC
  threads and never wait on the UI thread, which a blocking device read would freeze.
- The drag never starts with the button already up (`GetAsyncKeyState`): a release not yet handled would drop at
  once.

## Known limits

- The app window does not redraw during the drag: OLE's modal loop runs on the UI thread, and Tao's own drag pump
  (which keeps Compose drawing) is internal to Tao. The drag ends at the drop, since the copy is async.
- No custom drag image: the shell draws its default one.
- Stream `Seek` only reports the position or rewinds to it: a target that seeks around a file will fail to copy it.

## Hand test on Windows

1. Drag one big file to an Explorer window. The drag should start as soon as the pointer leaves the tab, and
   Explorer's copy dialog should show progress. The tray should show the row's job.
2. Drag a folder row. It should arrive with its subfolders and files, and empty folders too.
3. Drag several rows at once, including a name Windows refuses (e.g. `a:b.txt`, which arrives as `a_b.txt`).
4. Cancel in Explorer's dialog; cancel the row in the tray. Neither should leave the app stuck; the tray job should
   end cancelled.
5. Drop on the Desktop, and onto another app that takes files (e.g. a mail draft).
6. Watch the log (`scrcpyviewer.DragOut`) for `SHDoDragDrop: hr=…`, `StartOperation`, `FileContents requested` and
   `EndOperation`.
