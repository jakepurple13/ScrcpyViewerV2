package com.programmersbox.scrcpyviewer.links

import com.programmersbox.scrcpyviewer.apps.DeviceApps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkModelTest {
    @Test
    fun kinds() {
        assertEquals(LinkKind.Web to "Web link", linkKind("https://example.com/a"))
        assertEquals(LinkKind.Web to "Web link", linkKind("  HTTP://example.com "))
        assertEquals(LinkKind.Scheme to "myapp: link", linkKind("myapp://settings/x"))
        assertEquals(LinkKind.Scheme to "mailto: link", linkKind("mailto:a@b.c"))
        assertEquals(LinkKind.Intent to "Intent URI", linkKind("intent://x#Intent;scheme=a;end"))
        assertEquals(LinkKind.NotALink to "Not a link", linkKind("example.com"))
        assertEquals(LinkKind.NotALink to "Not a link", linkKind("   "))
        assertEquals(LinkKind.NotALink to "Not a link", linkKind("://x"))
    }

    @Test
    fun plainCommand() {
        assertEquals(
            "am start -W -d https://example.com",
            deviceCommand(LinkRequest(link = "https://example.com")),
        )
    }

    @Test
    fun packageActionCategoryExtra() {
        val r = LinkRequest(
            link = "https://example.com",
            packageName = "com.android.chrome",
            action = "android.intent.action.VIEW",
            category = "android.intent.category.BROWSABLE",
            extra = Extra(ExtraType.String, "k", "v"),
        )
        assertEquals(
            "am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE " +
                "-d https://example.com --es k v com.android.chrome",
            deviceCommand(r),
        )
    }

    @Test
    fun extraTypes() {
        fun cmd(t: ExtraType) = deviceCommand(LinkRequest("a://b", extra = Extra(t, "k", "1")))
        assertTrue(" --es k 1" in cmd(ExtraType.String))
        assertTrue(" --ei k 1" in cmd(ExtraType.Int))
        assertTrue(" --ez k 1" in cmd(ExtraType.Bool))
    }

    @Test
    fun clearTaskAndForceStop() {
        val clear = deviceCommand(LinkRequest("a://b", packageName = "p.q", before = Before.ClearTask))
        assertEquals("am start -W -d a://b --activity-clear-task p.q", clear)
        val stop = deviceCommand(LinkRequest("a://b", packageName = "p.q", before = Before.ForceStop))
        assertEquals("am force-stop p.q && am start -W -d a://b p.q", stop)
    }

    @Test
    fun forceStopNeedsPackage() {
        assertEquals(
            "am start -W -d a://b",
            deviceCommand(LinkRequest("a://b", before = Before.ForceStop)),
        )
    }

    @Test
    fun intentUriDropsActionAndCategory() {
        val r = LinkRequest(
            link = "intent://x#Intent;scheme=a;end",
            action = "android.intent.action.VIEW",
            category = "android.intent.category.BROWSABLE",
        )
        assertEquals("am start -W 'intent://x#Intent;scheme=a;end'", deviceCommand(r))
    }

    @Test
    fun intentUriIsPositionalAndIgnoresPackage() {
        val uri = "intent://www.example.com/#Intent;scheme=https;package=com.android.chrome;end"
        val r = LinkRequest(uri, packageName = "other.app", before = Before.ForceStop, extra = Extra(ExtraType.Bool, "k", "true"))
        assertEquals("am start -W --ez k true '$uri'", deviceCommand(r))
        assertEquals(
            "am start -W --activity-clear-task '$uri'",
            deviceCommand(LinkRequest(uri, before = Before.ClearTask)),
        )
    }

    @Test
    fun parseAndroid16UnknownState() {
        val out = """
            Starting: Intent { act=android.intent.action.VIEW dat=https://www.example.com/... pkg=com.android.chrome }
            Status: ok
            LaunchState: UNKNOWN (0)
            Activity: com.android.chrome/org.chromium.chrome.browser.document.ChromeLauncherActivity
            WaitTime: 189
            Complete
        """.trimIndent()
        val r = parseAmStart(out)
        assertTrue(r.ok)
        assertNull(r.launchState)
        assertNull(r.totalMs)
        assertEquals(189L, r.waitMs)
        assertEquals("com.android.chrome", r.packageName)
        assertEquals("Opened in Chrome · 189 ms", r.summary("Chrome"))
    }

    @Test
    fun summaries() {
        assertEquals("Opened in X · cold start 412 ms", LaunchResult(true, launchState = "COLD", totalMs = 412, waitMs = 420).summary("X"))
        assertEquals("Opened in X · hot start 9 ms", LaunchResult(true, launchState = "HOT", waitMs = 9).summary("X"))
        assertEquals("Opened in X", LaunchResult(true).summary("X"))
    }

    @Test
    fun hostileValuesAreQuoted() {
        val link = "https://x.com/a b?q=it's \$(id);rm -rf /&y=`z`"
        val cmd = deviceCommand(LinkRequest(link, extra = Extra(ExtraType.String, "k'; reboot", "v \$HOME")))
        assertTrue("-d 'https://x.com/a b?q=it'\\''s \$(id);rm -rf /&y=`z`'" in cmd, cmd)
        assertTrue("--es 'k'\\''; reboot' 'v \$HOME'" in cmd, cmd)
    }

    @Test
    fun hostilePackageIsQuoted() {
        val cmd = deviceCommand(LinkRequest("a://b", packageName = "p;reboot", before = Before.ForceStop))
        assertEquals("am force-stop 'p;reboot' && am start -W -d a://b 'p;reboot'", cmd)
    }

    @Test
    fun previewQuotesForLocalShell() {
        val r = LinkRequest("https://x.com/it's;rm")
        val preview = previewCommand("R5CX12", r)
        assertEquals("adb -s R5CX12 shell " + DeviceApps.quote(deviceCommand(r)), preview)
    }

    @Test
    fun quotingOnlyWhereNeeded() {
        val link = "https://example.com/?a=1&b=2"
        assertEquals("am start -W -d 'https://example.com/?a=1&b=2'", deviceCommand(LinkRequest(link)))
        // A package-only token stays bare.
        assertEquals("am start -W -d a://b com.android.chrome", deviceCommand(LinkRequest("a://b", packageName = "com.android.chrome")))
    }

    @Test
    fun previewReadsNaturally() {
        val r = LinkRequest("https://example.com/?a=1&b=2", packageName = "com.android.chrome", action = "android.intent.action.VIEW")
        assertEquals(
            "adb -s R5CX12GDWPZ shell \"am start -W -a android.intent.action.VIEW -d 'https://example.com/?a=1&b=2' com.android.chrome\"",
            previewCommand("R5CX12GDWPZ", r),
        )
    }

    @Test
    fun previewFallsBackToSingleQuotesForDoubleQuoteSpecials() {
        for (link in listOf("https://x.com/?q=\"a\"", "https://x.com/?q=\$HOME", "https://x.com/?q=`id`", "https://x.com/?q=a\\b", "https://x.com/?q=a!b")) {
            val r = LinkRequest(link)
            assertEquals("adb -s d shell " + DeviceApps.quote(deviceCommand(r)), previewCommand("d", r), link)
        }
    }

    @Test
    fun parseOk() {
        for (state in listOf("COLD", "WARM", "HOT")) {
            val out = """
                Starting: Intent { act=android.intent.action.VIEW dat=https://example.com/... pkg=com.android.chrome }
                Status: ok
                LaunchState: $state
                Activity: com.android.chrome/com.google.android.apps.chrome.Main
                TotalTime: 412
                WaitTime: 420
                Complete
            """.trimIndent().replace("\n", "\r\n")
            val r = parseAmStart(out)
            assertTrue(r.ok)
            assertEquals("com.android.chrome", r.packageName)
            assertEquals(state, r.launchState)
            assertEquals(412L, r.totalMs)
            assertNull(r.errorLine)
        }
    }

    @Test
    fun parseOlderThisTime() {
        val r = parseAmStart("Status: ok\nActivity: a.b/.C\nThisTime: 10\nTotalTime: 12\nWaitTime: 15\nComplete")
        assertTrue(r.ok)
        assertEquals(12L, r.totalMs)
        assertNull(r.launchState)
    }

    @Test
    fun parseUnresolved() {
        val out = """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://settings/... }
            Error: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW dat=myapp://settings/... flg=0x10000000 }
        """.trimIndent()
        val r = parseAmStart(out)
        assertFalse(r.ok)
        assertTrue(r.errorLine!!.startsWith("Error: Activity not started, unable to resolve Intent"))
        assertNull(r.packageName)
    }

    @Test
    fun parseEmpty() {
        val r = parseAmStart("")
        assertFalse(r.ok)
        assertNull(r.errorLine)
        assertNull(r.totalMs)
    }
}
