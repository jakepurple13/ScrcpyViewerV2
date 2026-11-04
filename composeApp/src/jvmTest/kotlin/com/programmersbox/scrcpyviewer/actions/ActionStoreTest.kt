package com.programmersbox.scrcpyviewer.actions

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActionStoreTest {
    private val file = Files.createTempDirectory("actions").resolve("actions.json")
    private val a = ToggleAction("a", "Stay awake", on = "svc power stayon true", off = "svc power stayon false")
    private val b = RunOnceAction("b", "Clear", command = "pm clear {package}")
    private val c = RunOnceAction("c", "Third", command = "ls")

    @Test
    fun `persists and reloads`() {
        val s = ActionStore(file)
        s.upsert(a)
        s.upsert(b)
        s.upsert(a.copy(off = "x"))
        assertEquals(listOf(a.copy(off = "x"), b), ActionStore(file).actions.value)
    }

    @Test
    fun `move reorders and persists`() {
        val s = ActionStore(file)
        listOf(a, b, c).forEach(s::upsert)
        s.move("c", 0)
        assertEquals(listOf("c", "a", "b"), ActionStore(file).actions.value.map { it.id })
        s.move("c", 99)
        assertEquals(listOf("a", "b", "c"), s.actions.value.map { it.id })
    }

    @Test
    fun `duplicate gets new id and unique name`() {
        val s = ActionStore(file)
        s.upsert(a)
        val dup = s.duplicate("a")
        assertEquals("Stay awake (2)", dup.name)
        assertNotEquals("a", dup.id)
        assertEquals(listOf(a, dup), ActionStore(file).actions.value)
        assertEquals("Stay awake (3)", s.duplicate("a").name)
    }

    @Test
    fun `delete and replaceAll`() {
        val s = ActionStore(file)
        s.replaceAll(listOf(a, b))
        s.delete("a")
        assertEquals(listOf(b), ActionStore(file).actions.value)
    }

    @Test
    fun `isNameTaken ignores case and excepted id`() {
        val s = ActionStore(file)
        s.upsert(a)
        assertTrue(s.isNameTaken("STAY AWAKE"))
        assertTrue(!s.isNameTaken("stay awake", exceptId = "a"))
    }

    @Test
    fun `corrupt file loads empty and is backed up before overwrite`() {
        Files.writeString(file, "garbage")
        val s = ActionStore(file)
        assertEquals(emptyList(), s.actions.value)
        s.upsert(a)
        assertEquals("garbage", Files.readString(file.resolveSibling("actions.json.bak")))
        assertEquals(listOf(a), ActionStore(file).actions.value)
    }

    @Test
    fun `failed backup sets persistenceError and keeps original`() {
        Files.writeString(file, "garbage")
        val bak = file.resolveSibling("actions.json.bak")
        Files.createDirectory(bak)
        Files.writeString(bak.resolve("keep"), "x")
        val s = ActionStore(file)
        s.upsert(a)
        assertEquals("garbage", Files.readString(file))
        assertNotNull(s.persistenceError.value)
    }
}
