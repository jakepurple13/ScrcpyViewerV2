package com.programmersbox.scrcpyviewer.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrubberTest {
    @Test fun homePathBecomesTilde() = assertEquals("~/x/y.txt", Scrubber(home = "/Users/jake").scrub("/Users/jake/x/y.txt"))

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
