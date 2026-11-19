package com.programmersbox.scrcpyviewer.ui.tray.popup

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.ui.mirror.follow.FollowState
import kotlin.test.Test
import kotlin.test.assertEquals

class TrayPopupModelTest {
    private fun device(serial: String, state: DeviceState = DeviceState.Device) =
        Device(serial, state, "raw", null, null, null, false)

    private fun popup(serial: String, status: PopupStatus = PopupStatus.Ready) =
        PopupDevice(serial, "T-$serial", status, "raw")

    private fun mirrors(vararg serials: String) = serials.map { popup(it, PopupStatus.Mirroring) }

    @Test
    fun openMirrorMissingFromAdbIsListedAsMirroring() {
        val list = popupDevices(listOf(device("A")), setOf("A", "Z")) { "T-${it.serial}" }
        assertEquals(listOf("A", "Z"), list.map { it.serial })
        assertEquals(listOf(PopupStatus.Mirroring, PopupStatus.Mirroring), list.map { it.status })
        assertEquals("Z", list[1].title)
        assertEquals("T-A", list[0].title)
    }

    @Test
    fun unauthorizedMirrorCountsAsMirroring() {
        val list = popupDevices(listOf(device("A", DeviceState.Unauthorized)), setOf("A")) { it.serial }
        assertEquals(PopupStatus.Mirroring, list.single().status)
    }

    @Test
    fun statusFollowsAdbStateWhenNotMirroring() {
        val list = popupDevices(DeviceState.entries.map { device(it.name, it) }, emptySet()) { it.serial }
        assertEquals(
            listOf(PopupStatus.Ready, PopupStatus.Unauthorized, PopupStatus.Offline, PopupStatus.Other),
            list.map { it.status },
        )
        assertEquals("raw", list[0].rawState)
    }

    @Test
    fun stageKeepsCurrentWhenStillPresent() {
        val devices = listOf(popup("A"), popup("B", PopupStatus.Mirroring))
        assertEquals("A", stageSerial("A", "B", devices))
    }

    @Test
    fun stageStartsAtLastFocusedMirror() {
        assertEquals("C", stageSerial(null, "C", mirrors("B", "C")))
    }

    @Test
    fun stageFallsBackToFirstMirroringThenFirstDevice() {
        assertEquals("B", stageSerial(null, null, listOf(popup("A"), popup("B", PopupStatus.Mirroring))))
        assertEquals("A", stageSerial(null, "gone", listOf(popup("A"), popup("B"))))
    }

    @Test
    fun stageMovesWhenItsDeviceLeaves() {
        val devices = listOf(popup("A"), popup("C", PopupStatus.Mirroring))
        assertEquals("C", stageSerial("B", null, devices))
    }

    @Test
    fun noDevicesGivesNullStage() {
        assertEquals(null, stageSerial("A", "B", emptyList()))
    }

    @Test
    fun sendPanelHiddenWithFewerThanTwoMirrors() {
        assertEquals(SendPanel.Hidden, sendPanel("A", mirrors("A") + popup("B"), FollowState()))
    }

    @Test
    fun hiddenWhenStageNotMirroring() {
        assertEquals(SendPanel.Hidden, sendPanel("A", listOf(popup("A")) + mirrors("B", "C"), FollowState()))
        assertEquals(SendPanel.Hidden, sendPanel(null, mirrors("A", "B"), FollowState()))
    }

    @Test
    fun chooseListsOtherMirroringDevices() {
        val devices = mirrors("A", "B", "C") + popup("D")
        assertEquals(
            SendPanel.Choose(mirrors("B", "C"), setOf("B"), null),
            sendPanel("A", devices, FollowState("A", setOf("B"))),
        )
    }

    @Test
    fun chooseNamesOtherLeader() {
        assertEquals(
            SendPanel.Choose(mirrors("B", "C"), emptySet(), "C"),
            sendPanel("A", mirrors("A", "B", "C"), FollowState("C", setOf("B"))),
        )
    }

    @Test
    fun followingWhenStageFollowsAnother() {
        assertEquals(
            SendPanel.Following("C"),
            sendPanel("A", mirrors("A", "B", "C"), FollowState("C", setOf("A"))),
        )
    }

    @Test
    fun toggleAddsAndRemoves() {
        assertEquals(setOf("B", "C"), toggledFollowers("A", "C", FollowState("A", setOf("B"))))
        assertEquals(setOf("B"), toggledFollowers("A", "C", FollowState("A", setOf("B", "C"))))
        assertEquals(setOf("B"), toggledFollowers("A", "B", FollowState()))
    }

    @Test
    fun toggleFromAnotherLeaderStartsFresh() {
        assertEquals(setOf("B"), toggledFollowers("A", "B", FollowState("C", setOf("B"))))
    }

    @Test
    fun withoutFollowerDropsLeaderWhenLastFollowerLeaves() {
        assertEquals(FollowState(), withoutFollower("B", FollowState("C", setOf("B"))))
        assertEquals(FollowState("C", setOf("D")), withoutFollower("B", FollowState("C", setOf("B", "D"))))
    }
}
