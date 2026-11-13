package com.programmersbox.scrcpyviewer.logcat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LogcatLineTest {
    @Test
    fun `parses a threadtime line`() {
        val l = assertNotNull(parseThreadtime("11-13 09:24:01.123  14211  14230 W OkHttp  : <-- 503 (2011ms)"))
        assertEquals("11-13", l.date)
        assertEquals("09:24:01.123", l.time)
        assertEquals("11-13 09:24:01.123", l.timestamp)
        assertEquals(14211, l.pid)
        assertEquals(14230, l.tid)
        assertEquals(LogLevel.Warn, l.level)
        assertEquals("OkHttp", l.tag)
        assertEquals("<-- 503 (2011ms)", l.message)
    }

    @Test
    fun `tag with spaces and colon keeps the first separator`() {
        val a = assertNotNull(parseThreadtime("11-13 09:24:01.123  1  2 I Finsky: [123]: hello: there"))
        assertEquals("Finsky", a.tag)
        assertEquals("[123]: hello: there", a.message)
        val b = assertNotNull(parseThreadtime("11-13 09:24:01.123  1  2 I My Tag  : x"))
        assertEquals("My Tag", b.tag)
        assertEquals("x", b.message)
    }

    @Test
    fun `empty message`() {
        val a = assertNotNull(parseThreadtime("11-13 09:24:01.123  1  2 D Tag: "))
        assertEquals("Tag", a.tag)
        assertEquals("", a.message)
        val b = assertNotNull(parseThreadtime("11-13 09:24:01.123  1  2 D Tag:"))
        assertEquals("Tag", b.tag)
        assertEquals("", b.message)
    }

    @Test
    fun `F maps to Assert`() {
        assertEquals(LogLevel.Assert, LogLevel.fromLetter('F'))
        assertEquals(LogLevel.Assert, LogLevel.fromLetter('A'))
        assertNull(LogLevel.fromLetter('X'))
        assertEquals(LogLevel.Assert, parseThreadtime("11-13 09:24:01.123  1  2 F Tag: boom")?.level)
    }

    @Test
    fun `beginning-of markers and junk are null`() {
        assertNull(parseThreadtime("--------- beginning of crash"))
        assertNull(parseThreadtime(""))
        assertNull(parseThreadtime("hello"))
    }

    @Test
    fun `splitter joins chunks and strips CR`() {
        val s = LineSplitter()
        assertEquals(listOf("a"), s.feed("a\r\nb"))
        assertEquals(listOf("bc"), s.feed("c\nd"))
        assertEquals("d", s.flush())
        assertNull(s.flush())
    }

    @Test
    fun `non-ASCII survives`() {
        val l = assertNotNull(parseThreadtime("11-13 09:24:01.123  1  2 I Tag: Größe 😀"))
        assertEquals("Größe 😀", l.message)
    }
}
