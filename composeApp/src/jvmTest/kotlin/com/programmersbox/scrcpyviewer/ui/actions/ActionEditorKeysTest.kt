package com.programmersbox.scrcpyviewer.ui.actions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.actions.ActionStore
import com.programmersbox.scrcpyviewer.actions.CustomAction
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OnWhen
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.actions.VariableKind
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import com.programmersbox.scrcpyviewer.ui.onEdt
import com.programmersbox.scrcpyviewer.ui.presets.PresetFileDialogs
import com.programmersbox.scrcpyviewer.ui.screenshotPanes
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.awt.Canvas
import java.nio.file.Files
import java.nio.file.Path
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Typing and deleting in the Actions editor's fields on the real Actions screen, with the view model on the app's
 * Swing dispatcher. Each step runs as one EDT event, so the work the view model dispatches runs between steps, as
 * it does between AWT events. Keys sent in one step model keys queued while the EDT was busy, or key repeat.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ActionEditorKeysTest {
    private class NoDevices : AdbClient(adbPath = { error("no adb") }) {
        override suspend fun startServer() {}
        override fun trackDevices(): Flow<String> = flow { awaitCancellation() }
    }

    private object NoFiles : PresetFileDialogs {
        override fun chooseExportFile(suggestedName: String): Path? = null
        override fun chooseImportFile(): Path? = null
    }

    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val vmScope = defaultViewModelScope()
    private val store = ActionStore(Files.createTempDirectory("actions-keys").resolve("actions.json"))
    private val vm = ActionsViewModel(store, DeviceTracker(NoDevices(), trackerScope), NoDevices(), vmScope)
    private val source = Canvas()
    private lateinit var driver: SceneDriver

    @AfterTest
    fun tearDown() {
        onEdt { driver.scene.close() }
        vmScope.cancel()
        trackerScope.cancel()
    }

    private fun open(action: CustomAction) {
        store.upsert(action)
        onEdt {
            driver = SceneDriver(ImageComposeScene(900, 1000, Density(1f)) {
                ScrcpyViewerTheme(ThemeMode.Light) {
                    Surface(Modifier.fillMaxSize()) {
                        ActionsScreen(vm, NoFiles, introSeen = true, onIntroSeen = {}, panes = screenshotPanes(detailOpen = true))
                    }
                }
            })
        }
        onEdt { vm.select(action.id) }
        repeat(3) { onEdt { driver.frames(20) } }
    }

    private fun key(id: Int, code: Int, char: Char = AwtKeyEvent.CHAR_UNDEFINED): KeyEvent {
        val typed = id == AwtKeyEvent.KEY_TYPED
        val awt = AwtKeyEvent(source, id, 0L, 0, if (typed) AwtKeyEvent.VK_UNDEFINED else code, char)
        val type = when (id) {
            AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
            AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
            else -> KeyEventType.Unknown
        }
        return KeyEvent(Key(if (typed) AwtKeyEvent.VK_UNDEFINED else code), type, codePoint = char.code, nativeEvent = awt)
    }

    /** One key press as AWT delivers it, then a frame, as the window renders between events. */
    private fun press(code: Int, char: Char = AwtKeyEvent.CHAR_UNDEFINED) {
        driver.scene.sendKeyEvent(key(AwtKeyEvent.KEY_PRESSED, code, char))
        if (char != AwtKeyEvent.CHAR_UNDEFINED) driver.scene.sendKeyEvent(key(AwtKeyEvent.KEY_TYPED, code, char))
        driver.scene.sendKeyEvent(key(AwtKeyEvent.KEY_RELEASED, code, char))
        driver.frame()
    }

    private fun type(text: String) = text.forEach { press(AwtKeyEvent.getExtendedKeyCodeForChar(it.code), it) }

    private fun backspace(times: Int) = repeat(times) { press(AwtKeyEvent.VK_BACK_SPACE, '\b') }

    /** Lets the view model's dispatched work and a few frames run. */
    private fun settle() = repeat(3) { onEdt { driver.frames(3) } }

    /** Clicks the field at ([x], [y]) and puts the caret at its end. */
    private fun focus(x: Float, y: Float) {
        onEdt {
            driver.scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Mouse)
            driver.scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse, button = PointerButton.Primary)
            driver.scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse, button = PointerButton.Primary)
            driver.frames(3)
        }
        onEdt { press(AwtKeyEvent.VK_END) }
        settle()
    }

    /**
     * In the field at ([x], [y]) holding [start]: Backspace once, type "zz" and Backspace twice in quick succession,
     * then Backspace until empty. [read] is the field's value in the view model's draft.
     */
    private fun checkField(field: String, x: Float, y: Float, start: String, read: () -> String) {
        focus(x, y)
        assertEquals(start, read(), "$field before")
        onEdt { backspace(1) }
        settle()
        assertEquals(start.dropLast(1), read(), "$field after one Backspace")
        onEdt { type("zz") }
        settle()
        assertEquals(start.dropLast(1) + "zz", read(), "$field after typing zz")
        onEdt { backspace(2) }
        settle()
        assertEquals(start.dropLast(1), read(), "$field after two quick Backspaces")
        onEdt { backspace(start.length + 2) }
        settle()
        assertEquals("", read(), "$field after Backspace to empty")
    }

    private val draft get() = vm.state.value.draft!!

    @Test
    fun `Backspace edits every run-once field`() {
        open(
            RunOnceAction(
                "r", "Abc", command = "echo {x} {y}",
                variables = mapOf(
                    "x" to Variable(label = "Xyz", default = "def"),
                    "y" to Variable(label = "Yy", kind = VariableKind.List, choices = listOf("p", "q")),
                ),
            ),
        )
        checkField("Name", 650f, 156f, "Abc") { draft.name }
        checkField("Label", 590f, 352f, "Xyz") { draft.variables.getValue("x").label }
        checkField("Default", 620f, 424f, "def") { draft.variables.getValue("x").default }
        checkField("List label", 590f, 524f, "Yy") { draft.variables.getValue("y").label }
        // Choices are kept as typed in the field; the draft holds the parsed list.
        val choices = { draft.variables.getValue("y").choices }
        focus(620f, 596f)
        onEdt { backspace(1) }
        settle()
        assertEquals(listOf("p"), choices(), "Choices after one Backspace")
        onEdt { type("zz") }
        settle()
        assertEquals(listOf("p", "zz"), choices(), "Choices after typing zz")
        onEdt { backspace(2) }
        settle()
        assertEquals(listOf("p"), choices(), "Choices after two quick Backspaces")
        onEdt { backspace(4) }
        settle()
        assertEquals(emptyList(), choices(), "Choices after Backspace to empty")
        // Last: emptying the command removes the variable rows above.
        checkField("Command", 620f, 232f, "echo {x} {y}") { draft.command }
    }

    @Test
    fun `Backspace edits every toggle field`() {
        open(ToggleAction("t", "Abc", on = "on1", off = "off1", read = "rd", onWhen = OnWhen(MatchMode.Equals, "7x")))
        checkField("Name", 650f, 156f, "Abc") { draft.name }
        checkField("Turn on", 620f, 232f, "on1") { draft.on }
        checkField("Turn off", 620f, 308f, "off1") { draft.off }
        checkField("On when value", 550f, 564f, "7x") { draft.matchValue }
        // Last: emptying the read command hides the On when rows below it.
        checkField("Read state", 620f, 384f, "rd") { draft.read }
    }
}
