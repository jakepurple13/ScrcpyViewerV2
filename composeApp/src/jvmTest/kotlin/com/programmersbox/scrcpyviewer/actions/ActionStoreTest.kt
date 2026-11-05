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

    private fun user(id: String, name: String = id) = RunOnceAction(id, name, command = "ls")
    private fun ai(id: String, name: String = id) = RunOnceAction(id, name, command = "ls", source = ActionSource.Ai)

    @Test
    fun `moveInGroup only counts same-source actions`() {
        val s = ActionStore(file)
        s.replaceAll(listOf(user("u1"), ai("a1"), user("u2"), ai("a2")))
        s.moveInGroup("u2", 0)
        assertEquals(listOf("u2", "u1", "a1", "a2"), s.actions.value.map { it.id })
        s.moveInGroup("a1", 5)
        assertEquals(listOf("u2", "u1", "a2", "a1"), ActionStore(file).actions.value.map { it.id })
    }

    @Test
    fun `markAsUser keeps position`() {
        val s = ActionStore(file)
        s.replaceAll(listOf(user("u1"), ai("a1"), ai("a2")))
        s.markAsUser("a1")
        assertEquals(listOf("u1", "a1", "a2"), s.actions.value.map { it.id })
        assertEquals(ActionSource.User, s.actions.value[1].source)
        assertEquals(ActionSource.Ai, s.actions.value[2].source)
    }

    @Test
    fun `applyInbox adds new as ai at end`() {
        val s = ActionStore(file)
        s.replaceAll(listOf(user("u1")))
        val result = s.applyInbox(listOf(user("bluetooth", "Bluetooth")))
        assertEquals(listOf(InboxChange.Added("Bluetooth")), result)
        assertEquals(ActionSource.Ai, s.actions.value.last().source)
        assertEquals("bluetooth", s.actions.value.last().id)
        assertEquals(s.actions.value, ActionStore(file).actions.value)
    }

    @Test
    fun `applyInbox update keeps position and source`() {
        val s = ActionStore(file)
        s.replaceAll(listOf(user("u1"), user("u2")))
        val result = s.applyInbox(listOf(RunOnceAction("u1", "U1 new", command = "new", source = ActionSource.Ai)))
        assertEquals(listOf(InboxChange.Updated("U1 new")), result)
        val first = s.actions.value[0] as RunOnceAction
        assertEquals("u1", first.id)
        assertEquals(ActionSource.User, first.source)
        assertEquals("new", first.command)
    }

    @Test
    fun `applyInbox name clash on new gets suffix`() {
        val s = ActionStore(file)
        s.replaceAll(listOf(user("x", "Bluetooth")))
        val result = s.applyInbox(listOf(user("bt", "Bluetooth")))
        assertEquals(listOf(InboxChange.Added("Bluetooth (2)")), result)
        assertEquals("Bluetooth (2)", s.actions.value.last().name)
    }

    @Test
    fun `applyInbox duplicate ids within one call`() {
        val s = ActionStore(file)
        val result = s.applyInbox(listOf(user("dup", "One"), user("dup", "Two")))
        assertEquals(2, result.size)
        assertTrue(result.all { it is InboxChange.Added })
        assertEquals(2, s.actions.value.size)
        assertEquals("dup", s.actions.value[0].id)
        assertNotEquals("dup", s.actions.value[1].id)
    }
}
