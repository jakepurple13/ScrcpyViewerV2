package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.ui.SceneDriver
import com.programmersbox.scrcpyviewer.ui.onEdt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The styled [FilterChip] and [AssistChip]: semantics, the growing check, the count badge, every style. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class StyledFilterChipTest {
    private fun withScene(
        style: ThemeStyle,
        mode: ThemeMode = ThemeMode.Light,
        content: @Composable () -> Unit,
        block: SceneDriver.() -> Unit = {},
    ) {
        val driver = SceneDriver(
            ImageComposeScene(400, 200, Density(1f)) {
                ScrcpyViewerTheme(AppTheme(mode = mode, style = style)) {
                    Surface(Modifier.fillMaxSize()) { Row(Modifier.padding(16.dp)) { content() } }
                }
            },
        )
        try {
            driver.frames(5)
            driver.block()
        } finally {
            driver.scene.close()
        }
    }

    private fun SceneDriver.nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun SceneDriver.text(t: String): SemanticsNode? =
        nodes().firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == t } }

    private fun SceneDriver.tagged(tag: String): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun SceneDriver.chip(): SemanticsNode = nodes().first { it.config.getOrNull(SemanticsProperties.Selected) != null }

    private fun SceneDriver.click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse, button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse, button = PointerButton.Primary)
    }

    private fun SceneDriver.checkWidth(): Float = tagged(FilterChipCheckTag)?.boundsInRoot?.width ?: 0f

    @Test
    fun `a click toggles selected, read as a checkbox, in every style`() = onEdt {
        for (style in ThemeStyle.entries) {
            var on by mutableStateOf(false)
            withScene(style, content = { FilterChip(on, { on = !on }, label = { Text("Crashes") }) }) {
                assertFalse(chip().config[SemanticsProperties.Selected], "$style starts off")
                assertEquals(Role.Checkbox, chip().config.getOrNull(SemanticsProperties.Role), "$style role")
                click(checkNotNull(text("Crashes")))
                frames(5)
                assertTrue(on, "$style click toggles")
                assertTrue(chip().config[SemanticsProperties.Selected], "$style reads selected")
                click(checkNotNull(text("Crashes")))
                frames(30)
                assertFalse(chip().config[SemanticsProperties.Selected], "$style off again")
            }
        }
    }

    @Test
    fun `the check grows in over about 250 ms with motion`() = onEdt {
        for (style in ThemeStyle.entries) {
            var on by mutableStateOf(false)
            withScene(style, content = { FilterChip(on, { on = !on }, label = { Text("Crashes") }, motion = true) }) {
                assertEquals(0f, checkWidth(), "$style no check while off")
                on = true
                frames(5) // ~80 ms of 250
                val early = checkWidth()
                frames(30)
                val full = checkWidth()
                assertTrue(full >= 16f, "$style check reaches 16 dp, was $full")
                assertTrue(early > 0f && early < full, "$style check is mid-grow at ~80 ms: $early of $full")
                on = false
                frames(30)
                assertEquals(0f, checkWidth(), "$style check shrinks away")
            }
        }
    }

    @Test
    fun `the check snaps under reduced motion`() = onEdt {
        for (style in ThemeStyle.entries) {
            var on by mutableStateOf(false)
            withScene(style, content = { FilterChip(on, { on = !on }, label = { Text("Crashes") }, motion = false) }) {
                on = true
                frame()
                assertTrue(checkWidth() >= 16f, "$style check full on the first frame")
                on = false
                frames(2) // AnimatedVisibility drops its content the frame after an instant exit
                assertEquals(0f, checkWidth(), "$style check gone at once")
            }
        }
    }

    @Test
    fun `the trailing count sits in a badge`() = onEdt {
        for (style in ThemeStyle.entries) {
            withScene(style, content = { FilterChip(true, {}, label = { Text("All") }, trailing = { Text("142") }) }) {
                val badge = assertNotNull(tagged(FilterChipBadgeTag), "$style badge")
                val count = assertNotNull(text("142"))
                assertTrue(badge.boundsInRoot.contains(count.boundsInRoot.center), "$style count inside the badge")
                assertTrue(chip().boundsInRoot.contains(badge.boundsInRoot.center), "$style badge inside the chip")
            }
            withScene(style, content = { FilterChip(false, {}, label = { Text("All") }) }) {
                assertNull(tagged(FilterChipBadgeTag), "$style no badge without trailing")
            }
        }
    }

    @Test
    fun `every style renders on and off, light and dark, enabled and not`() = onEdt {
        for (style in ThemeStyle.entries) for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            withScene(style, mode, content = {
                FilterChip(true, {}, label = { Text("On") }, trailing = { Text("3") })
                FilterChip(false, {}, label = { Text("Off") })
                FilterChip(true, {}, label = { Text("Disabled") }, enabled = false)
                AssistChip({}, label = { Text("Assist") }, leadingIcon = { Icon(Icons.Filled.Star, null) })
                AssistChip({}, label = { Text("Assist off") }, enabled = false)
            }) {
                frames(30)
                assertNotNull(text("On"))
                assertNotNull(text("Assist off"))
            }
        }
    }

    @Test
    fun `a disabled chip ignores clicks`() = onEdt {
        for (style in ThemeStyle.entries) {
            var on by mutableStateOf(false)
            var assists = 0
            withScene(style, content = {
                FilterChip(on, { on = !on }, label = { Text("Filter") }, enabled = false)
                AssistChip({ assists++ }, label = { Text("Assist") }, enabled = false)
            }) {
                click(checkNotNull(text("Filter")))
                click(checkNotNull(text("Assist")))
                frames(5)
                assertFalse(on, style.name)
                assertEquals(0, assists, style.name)
            }
        }
    }

    @Test
    fun `an assist chip runs its action and shows its icons, in every style`() = onEdt {
        for (style in ThemeStyle.entries) {
            var clicks = 0
            withScene(style, content = {
                AssistChip(
                    { clicks++ },
                    label = { Text("Grant permission") },
                    leadingIcon = { Icon(Icons.Filled.Star, contentDescription = "lead") },
                    trailingIcon = { Icon(Icons.Filled.Star, contentDescription = "trail") },
                )
            }) {
                click(checkNotNull(text("Grant permission")))
                frames(5)
                assertEquals(1, clicks, style.name)
                val lead = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("lead") == true }
                val trail = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("trail") == true }
                val label = checkNotNull(text("Grant permission"))
                assertTrue(lead.boundsInRoot.right <= label.boundsInRoot.left, "$style leading icon before the label")
                assertTrue(trail.boundsInRoot.left >= label.boundsInRoot.right, "$style trailing icon after the label")
                assertNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) != null }, "$style assist chip isn't a toggle")
            }
        }
    }
}
