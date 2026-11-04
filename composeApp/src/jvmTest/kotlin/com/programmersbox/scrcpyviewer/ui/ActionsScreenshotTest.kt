package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.ActionImportPlan
import com.programmersbox.scrcpyviewer.actions.ActionTemplates
import com.programmersbox.scrcpyviewer.actions.MatchMode
import com.programmersbox.scrcpyviewer.actions.OnWhen
import com.programmersbox.scrcpyviewer.actions.OutputTarget
import com.programmersbox.scrcpyviewer.actions.RunOnceAction
import com.programmersbox.scrcpyviewer.actions.ToggleAction
import com.programmersbox.scrcpyviewer.actions.Variable
import com.programmersbox.scrcpyviewer.actions.VariableKind
import com.programmersbox.scrcpyviewer.ui.actions.ActionDraft
import com.programmersbox.scrcpyviewer.ui.actions.ActionImportDialogContent
import com.programmersbox.scrcpyviewer.ui.actions.ActionImportUiState
import com.programmersbox.scrcpyviewer.ui.actions.ActionType
import com.programmersbox.scrcpyviewer.ui.actions.ActionsContent
import com.programmersbox.scrcpyviewer.ui.actions.ActionsIntroDialog
import com.programmersbox.scrcpyviewer.ui.actions.ActionsUiState
import com.programmersbox.scrcpyviewer.ui.actions.IconPickerContent
import com.programmersbox.scrcpyviewer.ui.actions.TemplatePickerContent
import com.programmersbox.scrcpyviewer.ui.actions.TestResult
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class ActionsScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val stayAwake = ToggleAction(
        "awake", "Stay awake", icon = "battery_full", on = "svc power stayon true", off = "svc power stayon false",
        read = "settings get global stay_on_while_plugged_in", onWhen = OnWhen(MatchMode.Equals, "7"),
    )
    private val clearData = RunOnceAction(
        "clear", "Clear app data", icon = "cleaning_services", command = "pm clear {package}", confirm = true,
        variables = mapOf("package" to Variable("App to clear", VariableKind.App)),
    )
    private val logcat = RunOnceAction("logcat", "Logcat errors", icon = "terminal", command = "logcat *:E", output = OutputTarget.Shell)

    private val draft = ActionDraft(
        id = "clear", type = ActionType.RunOnce, name = "Clear app data", icon = "cleaning_services",
        command = "pm clear {package}", variables = clearData.variables, confirm = true,
    )

    private val state = ActionsUiState(
        actions = listOf(stayAwake, clearData, logcat),
        selectedId = "clear",
        draft = draft,
        devices = listOf("R5CT123ABC", "emulator-5554"),
        testDevice = "R5CT123ABC",
        test = TestResult(running = false, output = "Success", exitCode = 0, note = null),
    )

    private fun shot(name: String, mode: ThemeMode, height: Int = 800, content: @Composable () -> Unit) {
        val out = outDir.resolve(name)
        renderToPng(1280, height, out, settleMillis = 1_000) {
            ScrcpyViewerTheme(mode) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() } }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Composable
    private fun screen(state: ActionsUiState) = ActionsContent(state, onEvent = {}, panes = screenshotPanes())

    @Test
    fun `actions list and editor light`() = shot("actions-list-editor.png", ThemeMode.Light) { screen(state) }

    @Test
    fun `actions list and editor dark`() = shot("actions-list-editor-dark.png", ThemeMode.Dark) { screen(state) }

    @Test
    fun `actions toggle editor with save error`() = shot("actions-toggle-banner.png", ThemeMode.Light) {
        screen(
            state.copy(
                selectedId = "awake",
                draft = ActionDraft(
                    id = "awake", type = ActionType.Toggle, name = "Stay awake", icon = "battery_full",
                    on = "svc power stayon true", off = "svc power stayon false",
                    read = "settings get global stay_on_while_plugged_in", matchValue = "7",
                ),
                dirty = true,
                devices = emptyList(),
                testDevice = null,
                test = TestResult(running = false, output = "7", exitCode = 0, note = "Counts as on", isRead = true, readOn = true),
                persistenceError = "actions.json is corrupt and was backed up",
            ),
        )
    }

    @Test
    fun `actions toggle editor read state sentence`() = shot("actions-toggle-editor.png", ThemeMode.Light) {
        screen(
            state.copy(
                selectedId = "awake",
                draft = ActionDraft(
                    id = "awake", type = ActionType.Toggle, name = "Stay awake", icon = "battery_full",
                    on = "svc power stayon true", off = "svc power stayon false",
                    read = "settings get global stay_on_while_plugged_in", matchValue = "7",
                ),
                test = TestResult(running = false, output = "7", exitCode = 0, note = "Counts as on", isRead = true, readOn = true),
            ),
        )
    }

    @Test
    fun `actions export selection`() = shot("actions-export-select.png", ThemeMode.Light) {
        screen(state.copy(exportSelection = setOf("awake", "logcat")))
    }

    @Test
    fun `actions intro dialog`() = shot("actions-intro.png", ThemeMode.Light) {
        Box(Modifier.fillMaxSize()) {
            screen(state)
            ActionsIntroDialog(page = 0, onPage = {}, onFinish = {})
        }
    }

    @Test
    fun `actions intro dialog page 2`() = shot("actions-intro-page2.png", ThemeMode.Light) {
        Box(Modifier.fillMaxSize()) {
            screen(state)
            ActionsIntroDialog(page = 1, onPage = {}, onFinish = {})
        }
    }

    @Test
    fun `actions intro dialog page 3`() = shot("actions-intro-page3.png", ThemeMode.Light) {
        Box(Modifier.fillMaxSize()) {
            screen(state)
            ActionsIntroDialog(page = 2, onPage = {}, onFinish = {})
        }
    }

    @Test
    fun `actions intro dialog short window`() = shot("actions-intro-short.png", ThemeMode.Light, height = 600) {
        Box(Modifier.fillMaxSize()) {
            screen(state)
            ActionsIntroDialog(page = 2, onPage = {}, onFinish = {})
        }
    }

    @Test
    fun `actions empty`() = shot("actions-empty.png", ThemeMode.Light) { screen(ActionsUiState()) }

    @Test
    fun `actions template picker`() = shot("actions-template-picker.png", ThemeMode.Light, height = 900) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TemplatePickerContent(onPick = {}, onDismiss = {}) }
    }

    @Test
    fun `actions template picker search`() = shot("actions-template-picker-search.png", ThemeMode.Light, height = 900) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TemplatePickerContent(onPick = {}, onDismiss = {}, initialQuery = "broad")
        }
    }

    /** The picker after a click on the Intents chip, once the filter animation has settled. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `actions template picker filtered`() = onEdt {
        val out = outDir.resolve("actions-template-picker-filtered.png")
        val scene = ImageComposeScene(1280, 900, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TemplatePickerContent(onPick = {}, onDismiss = {}) }
                }
            }
        }
        try {
            scene.render(0)
            // The Intents chip (see actions-template-picker.png).
            val chip = Offset(471f, 249f)
            scene.sendPointerEvent(PointerEventType.Press, chip, type = PointerType.Mouse, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, chip, type = PointerType.Mouse, button = PointerButton.Primary)
            var image = scene.render(16_000_000)
            for (frame in 2..63) image = scene.render(frame * 16_000_000L) // ~1 s of frames
            Files.createDirectories(out.parent)
            Files.write(out, checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        } finally {
            scene.close()
        }
        assertTrue(Files.size(out) > 10_000, "the filtered picker should not be blank")
    }

    @Test
    fun `actions editor from template`() {
        val grant = ActionTemplates.all.first { it.name == "Grant permission" }.action as RunOnceAction
        shot("actions-from-template.png", ThemeMode.Light) {
            screen(
                state.copy(
                    selectedId = null,
                    draft = ActionDraft(
                        id = null, type = ActionType.RunOnce, name = grant.name, icon = grant.icon, command = grant.command,
                        variables = grant.variables, output = grant.output, fromTemplate = grant.name,
                    ),
                    dirty = true,
                    test = null,
                ),
            )
        }
    }

    @Test
    fun `actions icon picker`() = shot("actions-icon-picker.png", ThemeMode.Light) {
        Box(Modifier.fillMaxSize()) {
            screen(state)
            // Where the popover opens under the editor's icon button.
            IconPickerContent(selected = "cleaning_services", onPick = {}, onDismiss = {}, modifier = Modifier.padding(start = 358.dp, top = 188.dp))
        }
    }

    private val importState = ActionImportUiState(
        ActionImportPlan.plan(
            existing = listOf(stayAwake),
            incoming = listOf(
                stayAwake.copy(id = "x", on = "svc power stayon usb"),
                clearData.copy(id = "y"),
                RunOnceAction("z", "Reboot to bootloader", command = "reboot bootloader", confirm = true),
            ),
        ),
    )

    @Test
    fun `actions import`() = shot("actions-import.png", ThemeMode.Light) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.width(640.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text("Import 3 actions", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                    ActionImportDialogContent(importState, { _, _ -> }, { _, _ -> })
                }
            }
        }
    }
}
