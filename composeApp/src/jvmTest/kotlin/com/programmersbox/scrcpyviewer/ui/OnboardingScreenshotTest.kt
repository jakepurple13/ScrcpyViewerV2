package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.Box
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.ui.onboarding.PrivacyChoices
import com.programmersbox.scrcpyviewer.ui.onboarding.onboardingPageCount
import com.programmersbox.scrcpyviewer.ui.onboarding.OnboardingDialog
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.withFakeAppKoin
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class OnboardingScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private fun render(mode: ThemeMode) = withFakeAppKoin { koin ->
        koin.get<AppSettings>().update { it.copy(themeMode = mode) }
        // A build with an analytics key: the consent page is last, at the default choices.
        val privacy = PrivacyChoices(usage = false, crash = true, onUsage = {}, onCrash = {})
        repeat(onboardingPageCount(privacy)) { page ->
            val name = "onboarding-${mode.name.lowercase()}-${page + 1}.png"
            val out = outDir.resolve(name)
            renderToPng(1280, 800, out, settleMillis = 500) {
                Box {
                    App()
                    ScrcpyViewerTheme(mode) { OnboardingDialog(page, {}, {}, privacy) }
                }
            }
            assertTrue(Files.size(out) > 10_000, "$name should not be blank")
        }
    }

    @Test
    fun `onboarding light`() = render(ThemeMode.Light)

    @Test
    fun `onboarding dark`() = render(ThemeMode.Dark)
}
