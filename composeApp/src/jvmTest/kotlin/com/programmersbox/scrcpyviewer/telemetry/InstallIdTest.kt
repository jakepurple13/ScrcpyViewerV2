package com.programmersbox.scrcpyviewer.telemetry

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class InstallIdTest {
    @Test fun installIdIsStableUntilReset() {
        val d = createTempDirectory()
        val a = InstallId(d).get()
        assertEquals(a, InstallId(d).get())
        assertNotEquals(a, InstallId(d).reset())
    }

    @Test fun resetPersists() {
        val d = createTempDirectory()
        val id = InstallId(d)
        id.get()
        val b = id.reset()
        assertEquals(b, InstallId(d).get())
    }

    @Test fun nothingWrittenUntilGet() {
        val d = createTempDirectory()
        InstallId(d)
        assertFalse(d.resolve("install-id").toFile().exists())
    }

    @Test fun disabledConfigIsDisabled() = assertFalse(TelemetryConfig.Disabled.enabled)
}
