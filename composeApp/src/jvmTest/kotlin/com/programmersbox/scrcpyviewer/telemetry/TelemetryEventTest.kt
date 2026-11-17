package com.programmersbox.scrcpyviewer.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryEventTest {
    @Test fun twentyThreeEvents() {
        assertEquals(23, TelemetryEvent::class.java.permittedSubclasses.size)
    }

    @Test fun durationsAndSizesAreBucketedInProps() {
        assertEquals("15-60", TelemetryEvent.MirrorEnded(30, "closed").props()["minutes"])
        assertEquals("100-500", TelemetryEvent.FirstFrameTime(250, "usb").props()["ms"])
        assertEquals("1048576-10485760",TelemetryEvent.FileTransfer("pull", 2_000_000, "done").props()["size"])
        assertEquals("app_opened", TelemetryEvent.AppOpened(true, "x").name)
    }
}
