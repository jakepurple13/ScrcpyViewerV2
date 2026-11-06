package com.programmersbox.scrcpyviewer.cdm

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CdmInstanceTest {
    @Test
    fun `concurrent copies get different names`() {
        val dir = Files.createTempDirectory("cdm-instance")
        val first = CdmInstance.claim(dir)
        val second = CdmInstance.claim(dir)
        assertEquals("scrcpy-vwr", first.appName)
        assertEquals("scrcpy-vwr-2", second.appName)
    }
}
