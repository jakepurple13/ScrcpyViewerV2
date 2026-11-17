package com.programmersbox.scrcpyviewer.telemetry

import com.programmersbox.scrcpyviewer.APP_VERSION
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.AdbException
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent.AppOpened
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent.DeviceConnected
import com.programmersbox.scrcpyviewer.telemetry.TelemetryEvent.UpdateInstalled
import com.programmersbox.scrcpyviewer.trackLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app, device and connection events whose call sites have no test class of their own. */
class AppEventsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val telemetry = FakeTelemetry()
    private val payloads = Channel<String>(Channel.UNLIMITED)

    @AfterTest
    fun tearDown() = scope.cancel()

    /** adb with a scripted device list; each serial answers the info command with [infos]. */
    private inner class FakeClient(private val infos: Map<String, String>) : AdbClient({ error("no real adb in tests") }) {
        override fun trackDevices(): Flow<String> = payloads.receiveAsFlow()
        override suspend fun startServer() = Unit
        override suspend fun shell(serial: String, cmd: String): String = infos.getValue(serial)
    }

    private val usb = "R5CX12GDWPZ device product:a15 model:SM_A156U1 transport_id:3"
    private val wifi = "192.168.1.31:5555 device product:husky model:Pixel_8_Pro transport_id:5"
    private val emulator = "emulator-5554 device product:sdk_gphone64 model:sdk_gphone64 transport_id:7"
    private val unauthorized = "ZY22H8KLMN unauthorized transport_id:9"

    private val infos = mapOf(
        "R5CX12GDWPZ" to "SM-A156U1\n14\n34\nR5CX12GDWPZ\n",
        "192.168.1.31:5555" to "Pixel 8 Pro\n8.1.0\n27\n39121FDJH004AB\n",
        "emulator-5554" to "sdk_gphone64_arm64\n16\n36\nEMULATOR36X1\n",
        "ZY22H8KLMN" to "Pixel 7\nBaklava\n36\nZY22H8KLMN\n",
    )

    private suspend fun awaitEvents(n: Int) = withTimeout(5_000) { while (telemetry.events.size < n) delay(5) }

    @Test
    fun deviceConnectedOncePerAppearanceWithItsConnection() = runBlocking<Unit> {
        DeviceTracker(FakeClient(infos), scope, telemetry = telemetry)
        payloads.send(usb)
        awaitEvents(1)
        payloads.send("$usb\n$wifi")
        awaitEvents(2)
        // A refresh of the same list is not a new appearance.
        payloads.send("$usb\n$wifi")
        payloads.send("$usb\n$wifi\n$emulator")
        awaitEvents(3)
        delay(100)
        assertEquals(
            listOf<TelemetryEvent>(DeviceConnected("usb", 14, 1), DeviceConnected("wifi", 8, 2), DeviceConnected("emulator", 16, 3)),
            telemetry.events,
        )
    }

    @Test
    fun everyListedSerialIsRememberedAndNoneIsSent() = runBlocking<Unit> {
        DeviceTracker(FakeClient(infos), scope, telemetry = telemetry)
        payloads.send("$usb\n$unauthorized\n$wifi")
        awaitEvents(2)
        assertTrue(
            telemetry.serials.containsAll(listOf("R5CX12GDWPZ", "ZY22H8KLMN", "192.168.1.31:5555")),
            "remembered: ${telemetry.serials}",
        )
        val values = telemetry.events.flatMap { it.props().values }.map { it.toString() }
        for (secret in listOf("R5CX12GDWPZ", "192.168.1.31", "SM-A156U1", "Pixel", "39121FDJH004AB")) {
            assertTrue(values.none { secret in it }, "$secret sent in $values")
        }
    }

    @Test
    fun unparseableAndroidVersionSendsNoEvent() = runBlocking<Unit> {
        val tracker = DeviceTracker(FakeClient(infos), scope, telemetry = telemetry)
        // ZY22H8KLMN reports "Baklava": listed as online so its info is fetched.
        payloads.send("ZY22H8KLMN device transport_id:9\n$usb")
        awaitEvents(1)
        withTimeout(5_000) { while (tracker.devices.value.any { it.info == null }) delay(5) }
        assertEquals(listOf<TelemetryEvent>(DeviceConnected("usb", 14, 2)), telemetry.events)
    }

    @Test
    fun launchSendsAppOpenedAndUpdateInstalledOnlyAfterAnUpdate() {
        trackLaunch(telemetry, firstLaunch = true, Settings(themeStyle = "Glass"), updatedFrom = null)
        assertEquals(listOf<TelemetryEvent>(AppOpened(firstLaunch = true, themeStyle = "Glass")), telemetry.events)
        telemetry.events.clear()
        trackLaunch(telemetry, firstLaunch = false, Settings(themeStyle = "gone"), updatedFrom = "1.25.0")
        assertEquals(
            listOf(AppOpened(firstLaunch = false, themeStyle = "Material"), UpdateInstalled("1.25.0", APP_VERSION)),
            telemetry.events,
        )
    }

    @Test
    fun adbExceptionMessageIsNotSent() {
        // adb's text can name the device, so AdbException is not a SafeMessage: only its type and frames are sent.
        val scrubber = Scrubber("/h").also { it.rememberSerial("R5CX12GDWPZ") }
        val p = exceptionProperties(AdbException("device 'R5CX12GDWPZ' not found"), handled = true, scrubber)
        val value = p.getValue("\$exception_list").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content
        assertEquals("", value)
    }
}
