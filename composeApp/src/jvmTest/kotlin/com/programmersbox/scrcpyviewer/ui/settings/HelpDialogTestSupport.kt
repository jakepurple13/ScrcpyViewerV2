package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared by the About and licenses dialog tests: a themed offscreen scene, node lookup, clicks, keys, frame diffs. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun helpScene(width: Int = 900, height: Int = 760, content: @Composable () -> Unit): SceneDriver =
    SceneDriver(ImageComposeScene(width, height, Density(1f)) {
        ScrcpyViewerTheme(ThemeMode.Light) { Surface(Modifier.fillMaxSize()) { content() } }
    })

/** Every semantics node in the scene, dialog layers included. */
internal fun SceneDriver.nodes(): List<SemanticsNode> = buildList {
    fun walk(n: SemanticsNode) {
        add(n)
        n.children.forEach(::walk)
    }
    scene.semanticsOwners.forEach { walk(it.rootSemanticsNode) }
}

/** The node whose text is exactly [text] (a button's merged node holds its label). */
internal fun SceneDriver.node(text: String): SemanticsNode? =
    nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

/** The node whose text contains [text]. */
internal fun SceneDriver.nodeContaining(text: String): SemanticsNode? =
    nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { text in it.text } == true }

internal fun SceneDriver.click(at: Offset) {
    scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
    scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
    scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
    frames(3)
}

internal fun SceneDriver.click(text: String) = click(assertNotNull(node(text), "no $text").boundsInRoot.center)

@OptIn(InternalComposeUiApi::class)
internal fun SceneDriver.key(key: Key) {
    for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
        scene.sendKeyEvent(KeyEvent(key, type))
        frame()
    }
    frames(2)
}

private val keySource = java.awt.Canvas()

@OptIn(InternalComposeUiApi::class)
private fun typedKey(id: Int, code: Int, char: Char): KeyEvent {
    val typed = id == AwtKeyEvent.KEY_TYPED
    val awt = AwtKeyEvent(keySource, id, 0L, 0, if (typed) AwtKeyEvent.VK_UNDEFINED else code, char)
    val type = when (id) {
        AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
        AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
        else -> KeyEventType.Unknown
    }
    return KeyEvent(Key(if (typed) AwtKeyEvent.VK_UNDEFINED else code), type, codePoint = char.code, nativeEvent = awt)
}

/** Types [text] into the focused field, a frame per character. */
internal fun SceneDriver.type(text: String) = text.forEach { char ->
    typeChar(char)
    frame()
}

/** How much the frame changed from [a] to [b], from row [fromY] down. */
internal fun diff(a: PixelMap, b: PixelMap, fromY: Int = 0): Long {
    var sum = 0L
    for (y in fromY until a.height) for (x in 0 until a.width) {
        val p = a[x, y]
        val q = b[x, y]
        sum += ((abs(p.red - q.red) + abs(p.green - q.green) + abs(p.blue - q.blue)) * 255).toLong()
    }
    return sum
}

/** How much each of [frames] frames changed (from row [fromY] down) after [change], from the settled frame before it. */
internal fun SceneDriver.stepsAfter(frames: Int = 45, fromY: Int = 0, change: () -> Unit): List<Long> {
    var last = snapshot()
    change()
    Snapshot.sendApplyNotifications()
    return List(frames) {
        val next = snapshot()
        diff(last, next, fromY).also { last = next }
    }
}

/** Types [char] into the focused field without running a frame. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun SceneDriver.typeChar(char: Char) {
    val code = AwtKeyEvent.getExtendedKeyCodeForChar(char.code)
    for (id in listOf(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.KEY_TYPED, AwtKeyEvent.KEY_RELEASED)) {
        scene.sendKeyEvent(typedKey(id, code, char))
    }
}

/** Moves over at least 4 frames, and no one frame carries most of the change. */
internal fun assertMoves(steps: List<Long>, what: String) {
    val total = steps.sum()
    assertTrue(total > 0, "$what: nothing changed")
    val moving = steps.count { it > total / 100 }
    assertTrue(moving >= 4, "$what: changed in only $moving frames: $steps")
    assertTrue(steps.max() <= total * 3 / 4, "$what: one frame jumps ${steps.max() * 100 / total}% of the change: $steps")
}

/** Lands within a 150 ms fade (10 frames, plus the two before it starts). */
internal fun assertLandsQuickly(steps: List<Long>, what: String) {
    val total = steps.sum()
    assertTrue(total > 0, "$what: nothing changed")
    val late = steps.drop(13).sum()
    assertTrue(late <= total / 100, "$what: still changing after 150 ms: $steps")
}
