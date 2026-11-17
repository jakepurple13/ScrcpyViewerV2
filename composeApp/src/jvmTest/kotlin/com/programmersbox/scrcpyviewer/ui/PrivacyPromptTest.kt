package com.programmersbox.scrcpyviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.actions.AiSkillInstaller
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.telemetry.Telemetry
import com.programmersbox.scrcpyviewer.ui.common.LocalReduceMotion
import com.programmersbox.scrcpyviewer.ui.onboarding.OnboardingDialog
import com.programmersbox.scrcpyviewer.ui.onboarding.PrivacyChoices
import com.programmersbox.scrcpyviewer.ui.onboarding.PrivacyPromptDialog
import com.programmersbox.scrcpyviewer.ui.onboarding.onboardingPageCount
import com.programmersbox.scrcpyviewer.ui.onboarding.showPrivacyPrompt
import com.programmersbox.scrcpyviewer.ui.settings.SettingsAdb
import com.programmersbox.scrcpyviewer.ui.settings.SettingsViewModel
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The consent page: the last onboarding page in a build with a key, and the one-time prompt for existing users. */
class PrivacyPromptTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("privacy-prompt")
    private val settings = AppSettings(dir.resolve("settings.json"))

    private object KeyedTelemetry : Telemetry by Telemetry.Noop {
        override val enabled = true
    }

    private fun vm(telemetry: Telemetry) = SettingsViewModel(
        settings, PresetStore(dir.resolve("presets.json"), scope),
        object : SettingsAdb {
            override fun bundledAdb(): Path = dir.resolve("adb")
            override suspend fun version(path: Path): String? = null
            override fun restartTracker() = Unit
        },
        AiSkillInstaller(dir.resolve("home"), dir.resolve("inbox"), dir.resolve("actions.json")), telemetry, scope,
    )

    private fun choices(s: Settings = Settings(), onUsage: (Boolean) -> Unit = {}, onCrash: (Boolean) -> Unit = {}) =
        PrivacyChoices(usage = s.usageStats, crash = s.crashReports, onUsage = onUsage, onCrash = onCrash)

    @Test
    fun keylessBuildHasFourPagesAndNeverSetsPromptSeen() {
        assertEquals(4, onboardingPageCount(null))
        val vm = vm(Telemetry.Noop)
        settings.update { it.copy(onboardingSeen = false) }
        vm.finishOnboarding()
        assertTrue(settings.settings.value.onboardingSeen)
        assertFalse(settings.settings.value.privacyPromptSeen, "a keyless build never marks the prompt seen")
        vm.finishPrivacyPrompt()
        assertFalse(settings.settings.value.privacyPromptSeen)
        assertFalse(showPrivacyPrompt(settings.settings.value, telemetryEnabled = false, otherDialogShowing = false))
        // The keyless guide ends on the mirror page with Get started, and has no consent page.
        scene({ OnboardingDialog(page = 3, onPage = {}, onFinish = {}, privacy = null) }) {
            assertTrue(text("Get started").isNotEmpty(), "Get started on the last page")
            assertTrue(text("Help improve Scrcpy Vwr").isEmpty(), "no consent page")
        }
    }

    @Test
    fun keyedBuildHasFivePagesAndFinishSetsPromptSeen() {
        assertEquals(5, onboardingPageCount(choices()))
        val vm = vm(KeyedTelemetry)
        settings.update { it.copy(onboardingSeen = false) }
        vm.finishOnboarding()
        assertTrue(settings.settings.value.onboardingSeen)
        assertTrue(settings.settings.value.privacyPromptSeen, "finishing (or skipping) the guide counts as seeing the prompt")
        assertFalse(showPrivacyPrompt(settings.settings.value, telemetryEnabled = true, otherDialogShowing = false))

        var page by mutableStateOf(3)
        var finished = 0
        scene({ OnboardingDialog(page = page, onPage = { page = it }, onFinish = { finished++ }, privacy = choices()) }) {
            // The mirror page is no longer last: Next leads to the consent page.
            click("Next")
            assertEquals(4, page)
            frames(60)
            assertTrue(text("Help improve Scrcpy Vwr").isNotEmpty(), "the consent page title")
            assertTrue(text(BODY).isNotEmpty(), "the consent page body")
            assertTrue(text("Skip").isEmpty(), "no Skip on the last page")
            assertTrue(text("Back").isNotEmpty(), "Back on the last page")
            click("Done")
            assertEquals(1, finished)
        }
    }

    @Test
    fun existingUserSeesStandalonePromptOnce() {
        settings.update { it.copy(onboardingSeen = true, privacyPromptSeen = false) }
        assertTrue(showPrivacyPrompt(settings.settings.value, telemetryEnabled = true, otherDialogShowing = false))
        assertFalse(
            showPrivacyPrompt(settings.settings.value, telemetryEnabled = true, otherDialogShowing = true),
            "waits while another one-time dialog is up",
        )
        val notYetOnboarded = settings.settings.value.copy(onboardingSeen = false)
        assertFalse(showPrivacyPrompt(notYetOnboarded, telemetryEnabled = true, otherDialogShowing = false), "the guide carries the page")

        val vm = vm(KeyedTelemetry)
        var done = 0
        scene({ PrivacyPromptDialog(choices(), onDone = { done++; vm.finishPrivacyPrompt() }) }) {
            assertTrue(text("Help improve Scrcpy Vwr").isNotEmpty(), "the prompt title")
            assertTrue(text("Back").isEmpty(), "the prompt has no Back")
            click("Done")
        }
        assertEquals(1, done)
        assertTrue(settings.settings.value.privacyPromptSeen)
        assertFalse(showPrivacyPrompt(settings.settings.value, telemetryEnabled = true, otherDialogShowing = false), "shown once")
    }

    @Test
    fun promptStartsAtDefaults() {
        val defaults = Settings()
        assertFalse(defaults.usageStats)
        assertTrue(defaults.crashReports)
        val calls = mutableListOf<Pair<String, Boolean>>()
        val prompt = choices(defaults, onUsage = { calls += "usage" to it }, onCrash = { calls += "crash" to it })
        scene({ PrivacyPromptDialog(prompt, onDone = {}) }) {
            assertEquals(ToggleableState.Off, toggle("Usage statistics").config[SemanticsProperties.ToggleableState])
            assertEquals(ToggleableState.On, toggle("Crash reports").config[SemanticsProperties.ToggleableState])
            click("Usage statistics")
            click("Crash reports")
        }
        assertEquals(listOf("usage" to true, "crash" to false), calls)
    }

    // Narrow windows: the onboarding consent page and the standalone prompt from the 360 dp minimum up.

    private val widths = listOf(360, 520, 700, 1000)

    @Test
    fun consentPageFitsEveryWindowWidth() {
        for (width in widths) {
            scene({ OnboardingDialog(page = 4, onPage = {}, onFinish = {}, privacy = choices()) }, width) {
                assertFits("Done", width, "onboarding consent page at $width")
                assertFits("Usage statistics", width, "onboarding consent page at $width")
            }
        }
    }

    @Test
    fun standalonePromptFitsEveryWindowWidth() {
        for (width in widths) {
            scene({ PrivacyPromptDialog(choices(), onDone = {}) }, width) {
                assertFits("Done", width, "privacy prompt at $width")
                assertFits("Crash reports", width, "privacy prompt at $width")
            }
        }
    }

    // Scenes.

    private fun scene(content: @Composable () -> Unit, width: Int = 1000, height: Int = 700, block: SceneDriver.() -> Unit) = onEdt {
        val driver = SceneDriver(ImageComposeScene(width, height, Density(1f)) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReduceMotion provides false) { content() }
            }
        })
        try {
            driver.frames(60)
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out.filter { it.layoutInfo.isPlaced }
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    private fun SemanticsNode.has(label: String): Boolean = label in texts() || children.any { it.has(label) }
    private fun SceneDriver.text(label: String) = nodes().filter { label in it.texts() }

    /** The toggleable row holding [label]. */
    private fun SceneDriver.toggle(label: String) =
        assertNotNull(nodes().lastOrNull { SemanticsProperties.ToggleableState in it.config && it.has(label) }, "the $label row")

    private fun SceneDriver.click(label: String) {
        val node = assertNotNull(nodes().lastOrNull { SemanticsActions.OnClick in it.config && it.has(label) }, "no $label to click")
        node.config[SemanticsActions.OnClick].action?.invoke()
        frames(60)
    }

    /** [label] is laid out inside 0..[width], and no text longer than 3 characters wraps in under 40 px. */
    private fun SceneDriver.assertFits(label: String, width: Int, where: String) {
        val node = assertNotNull(text(label).firstOrNull(), "$where: $label is shown")
        val b = node.boundsInRoot
        assertTrue(b.width > 0f && b.left >= -0.5f && b.right <= width + 0.5f, "$where: $label inside the window: $b")
        val squeezed = nodes().filter { n ->
            if (n.size.width !in 1..39 || n.texts().none { it.length > 3 }) return@filter false
            val layouts = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.isEmpty() || layouts.any { it.lineCount > 1 }
        }
        assertTrue(squeezed.isEmpty(), "$where: texts squeezed under 40 px: ${squeezed.map { it.texts() }}")
    }

    private companion object {
        const val BODY = "Send anonymous statistics and crash reports. No device names, files, apps or commands are ever " +
            "included. You can change this any time in Settings › Privacy."
    }
}
