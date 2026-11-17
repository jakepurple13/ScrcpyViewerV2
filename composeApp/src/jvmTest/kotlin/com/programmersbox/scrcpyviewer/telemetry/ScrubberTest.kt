package com.programmersbox.scrcpyviewer.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrubberTest {
    @Test fun homePathBecomesTilde() = assertEquals("~/x/y.txt", Scrubber(home = "/Users/jake").scrub("/Users/jake/x/y.txt"))

    @Test fun windowsHomeMatchesAnyCaseAndSeparator() {
        val s = Scrubber(home = "C:\\Users\\Jake")
        assertEquals("~\\x", s.scrub("C:\\Users\\Jake\\x"))
        assertEquals("~/x", s.scrub("c:/users/jake/x"))
        assertEquals("at ~", s.scrub("at C:\\Users\\Jake"))
    }

    @Test fun homeOnlyMatchesAWholeFolder() {
        val s = Scrubber(home = "/Users/jake")
        assertEquals("/Users/jake2/x", s.scrub("/Users/jake2/x"))
        // A dot may end a sentence, so it still counts as the end of the folder.
        assertEquals("in ~.", s.scrub("in /Users/jake."))
        assertEquals("~: missing", s.scrub("/Users/jake: missing"))
    }

    @Test fun rememberedSerialIsReplaced() {
        val s = Scrubber("/h"); s.rememberSerial("R5CX12GDWPZ")
        assertEquals("dev <device> gone", s.scrub("dev R5CX12GDWPZ gone"))
    }

    @Test fun ipv4AndIpv6AreReplaced() =
        assertEquals("<ip>:5555 and <ip>", Scrubber("/h").scrub("192.168.1.20:5555 and fe80::1ff:fe23:4567:890a"))

    @Test fun timeAndVersionAreUntouched() {
        val s = Scrubber("/h")
        assertEquals("at 12:34:56 v1.25.1", s.scrub("at 12:34:56 v1.25.1"))
    }

    @Test fun loopbackIpv6IsReplaced() = assertEquals("bind <ip> ok", Scrubber("/h").scrub("bind ::1 ok"))

    @Test fun cappedAt500() = assertEquals(500, Scrubber("/h").scrub("a".repeat(900)).length)
}
