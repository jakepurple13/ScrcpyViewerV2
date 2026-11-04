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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.actions.ActionImportPlan
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
import com.programmersbox.scrcpyviewer.ui.actions.TestResult
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
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

    private fun shot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out, settleMillis = 1_000) {
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
                test = TestResult(running = false, output = "7", exitCode = 0, note = "Counts as on"),
                persistenceError = "actions.json is corrupt and was backed up",
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
    fun `actions empty`() = shot("actions-empty.png", ThemeMode.Light) { screen(ActionsUiState()) }

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
