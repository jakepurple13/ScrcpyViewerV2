package com.programmersbox.scrcpyviewer.logcat

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class LogcatExportSnapshotTest {
    private val host = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)

    private fun output(characteristics: String = "phone") = """
        ro.product.manufacturer=samsung
        ro.product.model=SM-A156U1
        ro.build.version.release=16
        ro.build.version.sdk=36
        ro.build.characteristics=$characteristics
        date=2026-11-16-0700
    """.trimIndent()

    private val device = ExportDevice(
        "R5CX12GDWPZ", "samsung", "SM-A156U1", "16", 36, "HANDHELD",
        LocalDate.of(2026, 11, 16), ZoneOffset.ofHours(-7),
    )

    private fun line(date: String, time: String) =
        LogcatLine(date, time, 1, 1, LogLevel.Info, "T", "m")

    @Test
    fun `parses full output`() {
        assertEquals(device, parseExportDevice("R5CX12GDWPZ", output(), host))
    }

    @Test
    fun `watch and tv characteristics`() {
        assertEquals("WEAR", parseExportDevice("X", output("nosdcard,watch"), host).type)
        assertEquals("TV", parseExportDevice("X", output("tv"), host).type)
    }

    @Test
    fun `empty output falls back to host date`() {
        val d = parseExportDevice("X", "", host)
        assertEquals("", d.manufacturer)
        assertEquals(0, d.sdk)
        assertEquals(LocalDate.of(2026, 1, 2), d.today)
        assertEquals(ZoneOffset.UTC, d.offset)
    }

    @Test
    fun `parses the device time zone`() {
        assertEquals(ZoneId.of("America/Denver"), parseExportDevice("X", output() + "\ntz=America/Denver", host).zone)
        assertEquals(null, parseExportDevice("X", output() + "\ntz=", host).zone)
        assertEquals(null, parseExportDevice("X", output() + "\ntz=Not/AZone", host).zone)
    }

    @Test
    fun `epoch uses the zone's offset on the line's own date across DST`() {
        val denver = ZoneId.of("America/Denver")
        // Today (03-10) is MDT, -06:00; the line (03-07) was before the switch on 03-08, so MST, -07:00.
        val i = epochOf(line("03-07", "12:00:00.000"), LocalDate.of(2026, 3, 10), ZoneOffset.ofHours(-6), denver)
        assertEquals(Instant.parse("2026-03-07T19:00:00Z"), i)
        val same = epochOf(line("03-10", "12:00:00.000"), LocalDate.of(2026, 3, 10), ZoneOffset.ofHours(-6), denver)
        assertEquals(Instant.parse("2026-03-10T18:00:00Z"), same)
    }

    @Test
    fun `epoch same year`() {
        val i = epochOf(line("11-16", "06:46:01.112"), LocalDate.of(2026, 11, 16), ZoneOffset.ofHours(-7))
        assertEquals(Instant.parse("2026-11-16T13:46:01.112Z"), i)
    }

    @Test
    fun `epoch year rollover`() {
        val i = epochOf(line("12-31", "23:59:59.000"), LocalDate.of(2027, 1, 1), ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-12-31T23:59:59Z"), i)
    }

    @Test
    fun `epoch feb 29 in non-leap year`() {
        val i = epochOf(line("02-29", "10:00:00.000"), LocalDate.of(2027, 3, 1), ZoneOffset.UTC)
        assertEquals(Instant.parse("2027-02-28T10:00:00Z"), i)
    }

    @Test
    fun `file name`() {
        val now = LocalDateTime.of(2026, 11, 16, 6, 46, 0)
        assertEquals("samsung-SM-A156U1-Android-16_2026-11-16_064600.logcat", exportFileName(device, now, "logcat"))
        val blank = device.copy(manufacturer = "", model = "", release = "")
        assertEquals("device-unknown-Android-unknown_2026-11-16_064600.txt", exportFileName(blank, now, "txt"))
        assertEquals(
            "samsung-Pixel_9_Pro-Android-16_2026-11-16_064600.txt",
            exportFileName(device.copy(model = "Pixel 9/Pro"), now, "txt"),
        )
    }
}
