# Tao drag-out spike (Task 0)

Date: 2026-11-12. Backend: `dev.nucleusframework:nucleus.decorated-window-tao:2.5.18`, Compose Multiplatform 1.12.2.

This spike answers five questions about outbound file drags under the Tao backend, for the Files tab's "Out of the
window (pull)" (see `docs/superpowers/specs/2026-11-12-device-files-tab-design.md`). Task 12 builds on it.

Method: the Tao jar was unpacked and read with `javap -p -c`. The macOS native library
(`nucleus/native/darwin-aarch64/libnucleus_tao_dnd.dylib`) was read with `otool`. No physical drag was performed, so
Q2 to Q4 are code readings that still need the hand test at the end of this note.

## Summary

| # | Question | Answer | Status |
|---|----------|--------|--------|
| 1 | Which public API starts an outbound drag with `List<File>`? | `Modifier.dragAndDropSource { DragAndDropTransferData(DragAndDropTransferable(<AWT Transferable with javaFileListFlavor>), …) }`. To start it later, from our own code: `DragAndDropSourceModifierNode { … }` + `node.requestDragAndDropTransfer(offset)`. | Proven from code; compiles |
| 2 | Can it start after a delay, during an in-app drag, while the button is held? | Probably yes on macOS. The native code does not need a fresh event: it reuses the last cached left-mouse-down. | Needs hand test |
| 3 | Can it start once the pointer is outside the window? | Nothing in the native start checks the pointer position. Whether Compose still sees the pointer (moves, press state) outside the window is the open part. | Needs hand test |
| 4 | Does Finder copy and leave the source in place? | Tao sends `NSDragOperationCopy` for `DragAndDropTransferAction.Copy`. With only `Copy` advertised, Finder can only copy. | Needs hand test (Finder); Windows/Linux not tried |
| 5 | Is a custom drag image (`drawDragDecoration`) shown? | No. Tao stores it in `OutboundRequest` but nothing reads it. macOS shows the Finder icon of the file, Linux shows the GTK default icon, and Windows shows no image. | Proven from code |

## Q1. The API that starts an outbound drag with files

### Call chain (proven)

1. `Modifier.dragAndDropSource(transferData: (Offset) -> DragAndDropTransferData)`, in
   `androidx.compose.foundation.draganddrop`. An overload adds `drawDragDecoration`. The default start detector fires
   after the drag slop.
2. Compose calls `PlatformDragAndDropManager.requestDragAndDropTransfer`. Under Tao this is
   `TaoDragAndDropManager.requestDragAndDropTransfer-Uv8p0NA(source, offset)`.
   `isRequestDragAndDropTransferRequired()` returns `true`.
3. The manager calls `source.startDragAndDropTransfer(scope, offset, isTransferStarted)`. Our `transferData` lambda
   runs **synchronously** here. Then `scope.startDragAndDropTransfer-12SF9DM(data, decorationSize, drawDragDecoration)`
   runs (`TaoDragAndDropManager$requestDragAndDropTransfer$scope$1`):
   - `awtTransferable(data)`, which is `TaoTransferableAccess.toAwt(data.transferable)`. It only accepts an
     `AwtDragAndDropTransferable`, which is what `DragAndDropTransferable(java.awt.datatransfer.Transferable)` builds.
     Anything else logs `startDragAndDropTransfer skipped — non-AWT transferable` and returns `false`.
   - `extractFiles(t)`: `t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>`. `extractText(t)` reads
     `DataFlavor.stringFlavor`. If both are empty, it logs `skipped — no exportable data`.
   - `new OutboundRequest(files, text, data.supportedActions.toList(), decorationSize, drawDragDecoration)`, then
     `outboundLauncher.launch(request, onEnd)`.
4. On macOS the launcher is `TaoComposeSceneHost.launchMacOsOutboundDrag`. It calls
   `TaoSceneDnD.launchOutboundDrag(request, COPY=1, MOVE=2, LINK=4, startDrag)`. That builds a `String[]` of
   `File.getAbsolutePath()` and a bit mask from `supportedActions`, then calls
   `NativeTaoMacOsDndBridge.nativeStartDrag(nsViewHandle, paths, text, mask, OutboundDragPump)`.
   Windows uses `NativeTaoWindowsDndBridge.nativeStartDrag` (OLE `DoDragDrop`). Linux uses
   `NativeTaoLinuxDndBridge.nativeStartDrag` (`gtk_drag_begin_with_coordinates`, in `libnucleus_tao.so`).
5. **The drag is synchronous.** `nativeStartDrag` calls `beginDraggingSessionWithItems:event:source:`. It then spins
   `nextEventMatchingMask:untilDate:inMode:dequeue:` / `sendEvent:` in a nested loop until the session ends. A
   `CFRunLoopTimer` calls `DragPump.pump()`, which runs `requestFrame()` and `TaoMainDispatcher.pump()`, so Compose keeps
   drawing and main-dispatcher coroutines keep running during the drag. When the drag returns, the drop result is mapped
   back to `Copy` / `Move` / `Link` / `null` and passed to `DragAndDropTransferData.onTransferCompleted`.

The files must exist and be complete before `transferData` returns. The request carries only paths. The pasteboard
items are `NSURL fileURLWithPath:`, so Finder reads the file when the drop happens.

### Code shape for Task 12 (compiles against 1.12.2 + Tao 2.5.18)

The stock modifier needs the files ready when the drag slop is crossed. Task 12 starts the OS drag later, after the
pull finishes, so it needs a source node that it triggers itself:

```kotlin
@file:OptIn(ExperimentalComposeUiApi::class)

/** java.awt.datatransfer is plain data: no AWT toolkit needed. */
class FileListTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
}

fun filesTransfer(files: List<File>, onEnd: (DragAndDropTransferAction?) -> Unit) = DragAndDropTransferData(
    transferable = DragAndDropTransferable(FileListTransferable(files)),
    supportedActions = listOf(DragAndDropTransferAction.Copy), // copy only: the drop must never move our cache file
    onTransferCompleted = onEnd,
)

class DragOutController {
    internal var node: DragAndDropSourceModifierNode? = null
    internal var pending: DragAndDropTransferData? = null
    internal var accepted: Boolean? = null

    /** Blocks in the native drag loop until the drop ends; returns false/null if Tao refused to start. */
    fun start(data: DragAndDropTransferData, offset: Offset): Boolean? {
        val n = node ?: return null
        pending = data; accepted = null
        n.requestDragAndDropTransfer(offset)
        pending = null
        return accepted
    }
}

private class DragOutNode(var controller: DragOutController) : DelegatingNode() {
    private val dnd = delegate(DragAndDropSourceModifierNode { _ ->
        val data = controller.pending ?: return@DragAndDropSourceModifierNode
        controller.accepted = startDragAndDropTransfer(data, Size(96f, 96f)) { /* ignored by Tao, see Q5 */ }
    })
    override fun onAttach() { controller.node = dnd }
    override fun onDetach() { if (controller.node === dnd) controller.node = null }
}

private class DragOutElement(val controller: DragOutController) : ModifierNodeElement<DragOutNode>() {
    override fun create() = DragOutNode(controller)
    override fun update(node: DragOutNode) { node.controller = controller }
    override fun equals(other: Any?) = other is DragOutElement && other.controller === controller
    override fun hashCode() = System.identityHashCode(controller)
}

fun Modifier.dragOutSource(controller: DragOutController) = this then DragOutElement(controller)
```

Use it as follows. Put `Modifier.dragOutSource(controller)` on the row list, next to the in-app drag's `pointerInput`.
When the pull is done and the start rule holds, call `controller.start(...)` from `scope.launch { … }`, not from inside
the pointer handler. The call blocks until the drop. `DragAndDropSourceModifierNode` and `DragAndDropTransferData` need
`@OptIn(ExperimentalComposeUiApi::class)`. `dragAndDropSource` needs `ExperimentalFoundationApi`.

`dev.nucleusframework.window.tao.dnd.TaoFilesTransferable(files)` is public and does the same as
`FileListTransferable`. Our own class avoids depending on a Tao type.

Debugging: `TaoDnDDiagnostics.lastMessage` (a `MutableState<String>`) and its `requests` / `transfers` counters show
why a start was skipped.

## Q2. Delayed start, from an in-app drag, with the button held

What the code implies (macOS):

- On first registration, `libnucleus_tao_dnd` installs `NSEvent addLocalMonitorForEventsMatchingMask: 0x2`
  (`NSEventMaskLeftMouseDown`). The monitor caches the latest left-mouse-down event in a lock-protected global.
- `nativeStartDrag` uses that cached mouse-down. Without one, it uses `NSApp.currentEvent`. With neither, it fails.
  **It does not check that the button is still down or that the event is recent.**
- So a start 2 s after the press, from a coroutine, gets a valid event for `beginDraggingSessionWithItems`. AppKit
  should then track the held button as usual.
- Danger: the cached mouse-down outlives the release. If we start after the button is up, AppKit gets a session with no
  button held. It may cancel at once, follow the cursor until the next click, or drop where the pointer is. On Windows,
  `DoDragDrop` with the button already up usually ends at once as a drop at the cursor. **Task 12 must check "button
  still held" in Compose right before calling `start`, and must never start after a release.**
- Compose may never see the release that ends the OS drag, because the native loop consumes it. After `start` returns,
  Task 12 must reset its own gesture state ("drag over") and not wait for a pointer-up.

Status: **needs hand test** (harness box B, "start when ready").

## Q3. Start once the pointer is outside the window

What the code implies:

- The native start takes no position and checks no bounds. The dragging item frame is `(0, 0, icon.width, icon.height)`
  in the scene view (32×32 when there is no icon). AppKit places the image relative to the cached mouse-down location.
  So the image may first appear offset from the cursor (near the window corner) rather than under it.
- Detecting "the pointer left the window, button still held" needs Compose to keep getting pointer moves outside the
  window. AppKit sends `mouseDragged` to the window that got the mouse-down, even outside it. Whether Tao forwards those
  moves to Compose with out-of-bounds positions is not visible in the jar.

Status: **needs hand test** (harness box B, "ready + outside").

**Fallback (from the spec), to use if Q3 fails or is unreliable on any platform:** start the OS drag the moment the pull
finishes, wherever the pointer is, as long as the button is still held. Small files feel immediate. Large ones show the
ghost's progress first. Because of the "never after release" rule in Q2, releasing before the files are ready still
cancels both the drag and the pull. My expectation: Q2 passes on macOS. Q3 passes only if Tao forwards out-of-window
drags. Task 12 should therefore build the fallback rule as the default and add "wait until outside" only if the hand
test confirms it.

## Q4. Finder copies, source stays

What the code implies:

- The action mask that `nativeStartDrag` builds from Tao's bits (Copy=1, Move=2, Link=4) is
  `Copy→NSDragOperationCopy(1)`, `Move→NSDragOperationMove(16)`, `Link→NSDragOperationLink(2)`. An empty mask becomes
  Copy. `draggingSession:sourceOperationMaskForDraggingContext:` returns that mask (`NucleusDraggingSource._allowed`).
- With `supportedActions = listOf(Copy)`, Finder is only allowed to copy, so the source file stays. If Move were also
  advertised, a same-volume Finder drop could move our cache file away. Do not advertise Move.
- `onTransferCompleted` gets the operation that was performed (`Copy`), or `null` if the drop was cancelled or refused.
  Task 12 can use `null` to mean "nothing was dropped".

Status: **needs hand test** on Finder. Explorer and Nautilus were not tried (no machine). Windows uses OLE `DoDragDrop`
with `DROP_EFFECT_*` and the same bit mapping (`TaoSceneDnD.launchOutboundDrag`). Linux uses GTK.

## Q5. Custom drag image

**No (proven).** `OutboundRequest.getDrawDragDecoration` and `getDecorationSize` are never called anywhere in the jar.
`grep -a -l getDrawDragDecoration` matches only `TaoDragAndDropManager$OutboundRequest.class`. None of the three
`nativeStartDrag` signatures takes an image: `(long view, String[] paths, String text, int mask, DragPump)`. What each
platform shows:

- macOS: `NSWorkspace.sharedWorkspace iconForFile:` for each file, set with `NSDraggingItem setDraggingFrame:contents:`.
- Linux: `gtk_drag_set_icon_default`.
- Windows: no drag image at all (no `IDragSourceHelper`), only the OLE cursor.

Effect on the spec: the "ghost pill" is drawn by us inside the window before the OS drag starts. After the OS drag
starts, the user sees the platform's file icon or cursor, not our pill.

## Hand test

The harness is not committed. It lives in the git-ignored
`.superpowers/spike/drag-out/` (`src/.../spike/DragOutSpike.kt` + `spike.init.gradle`). The init script adds the
source to `:composeApp`'s `jvmTest` compilation and registers `runDragOutSpike`, only when passed with `-I`. The repo's
build files are unchanged. Run it from the worktree root:

```bash
./gradlew -I .superpowers/spike/drag-out/spike.init.gradle :composeApp:runDragOutSpike --no-configuration-cache
```

The window has two boxes, an event log, and the Tao diagnostics line. Files go to `$TMPDIR/scrcpy-vwr-drag-spike/`.

1. **Box A (stock `dragAndDropSource`, 5 MB file that already exists).** Drag it to a Finder window or the Desktop.
   - Q1: a file appears at the drop.
   - Q5: is the image the magenta square, or the Finder file icon? The code says the file icon.
   - Q4: with "Copy only", check that the log shows `onTransferCompleted: Copy; source exists=true`. Repeat with
     "Copy + Move" and record what Finder does (move or copy) and whether the source still exists.
2. **Box B, "start when ready" (Q2).** Press in B and keep moving inside the window, holding the button. After about
   2 s the log shows `file ready`, then `starting OS drag`. Check that an OS drag follows the cursor and drops into
   Finder. Also:
   - Hold still (no movement) until ready. Does the drag still start?
   - Release before 2 s. The log must show `released before start … cancel`, and no file is left in the temp dir.
3. **Box B, "ready + outside" (Q3).** Press in B, drag out of the window, and keep holding. Then:
   - Does the status line keep updating with `outside=true` while you are outside? This shows whether Tao forwards
     out-of-window moves.
   - After the file is ready, does the OS drag start while you are outside, and does the drop work?
   - Note where the drag image first appears relative to the cursor.
4. After each B drag, note whether the log shows `Compose saw the release after the OS drag started`. If it does not,
   Compose never got the pointer-up, and Task 12 has to reset its gesture state by itself.
5. If a Windows or Linux machine is available, repeat steps 1 to 3 there. On Windows, watch for an immediate drop when
   the drag starts.

Record the results in this note under the matching question and change "needs hand test" to yes or no.
