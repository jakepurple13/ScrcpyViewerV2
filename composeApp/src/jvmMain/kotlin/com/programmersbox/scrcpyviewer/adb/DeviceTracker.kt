package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

private const val INFO_COMMAND = "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.serialno"
/** Caps the doubling shift so the delay cannot overflow before [DeviceTracker]'s maxInfoRetryMs caps it. */
private const val MAX_RETRY_SHIFT = 16

/** Keeps [devices] in sync with the adb server's `host:track-devices-l` stream and reconnects with backoff. */
class DeviceTracker(
    private val client: AdbClient,
    private val scope: CoroutineScope,
    private val initialBackoffMs: Long = 250,
    private val maxBackoffMs: Long = 5000,
    /** First delay before re-asking a device for its info; doubles per attempt. */
    private val infoRetryMs: Long = 500,
    /** Longest delay between info attempts; a device that never answers is asked this often while it is listed. */
    private val maxInfoRetryMs: Long = 30_000,
) {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private data class Key(val serial: String, val transportId: Long?)

    // Only touched from the tracking coroutine and the fetch coroutines, which all run on [scope]; guarded by [lock].
    private val lock = Any()
    private var raw: List<Device> = emptyList()
    private val infoCache = HashMap<Key, DeviceInfo>()
    private val inFlight = HashSet<Key>()

    private var job: Job

    init {
        job = scope.launch { run() }
    }

    /** Drops the current connection and device list, then starts tracking again (e.g. after the adb path changed). */
    fun restart() {
        synchronized(lock) {
            val old = job
            job = scope.launch {
                old.cancelAndJoin()
                reset()
                run()
            }
        }
    }

    private suspend fun run() {
        var backoff = initialBackoffMs
        while (true) {
            try {
                client.trackDevices().collect { payload ->
                    backoff = initialBackoffMs
                    _connected.value = true
                    onPayload(DeviceListParser.parse(payload))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A cancelled collector can surface as an IOException from the closed socket; stop instead of reconnecting.
                currentCoroutineContext().ensureActive()
            }
            reset()
            try {
                client.startServer()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // server may still be coming up; the next attempt will tell
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
        }
    }

    private fun reset() {
        synchronized(lock) {
            raw = emptyList()
            infoCache.clear()
            inFlight.clear()
            _devices.value = emptyList()
        }
        _connected.value = false
    }

    private fun onPayload(list: List<Device>) {
        val toFetch = synchronized(lock) {
            raw = list
            _devices.value = merged()
            list.filter { it.state == DeviceState.Device }
                .map { Key(it.serial, it.transportId) }
                .filter { it !in infoCache && inFlight.add(it) }
        }
        for (key in toFetch) scope.launch { fetchInfo(key) }
    }

    private suspend fun fetchInfo(key: Key) {
        var info: DeviceInfo? = null
        // A device that just connected over Wi-Fi may not answer yet; retry with backoff while it is still listed,
        // since its options key (ro.serialno) comes from this info. There is no last attempt: nothing else would ask
        // again until the device list changes, and an emulator waiting for its info would be given up on.
        var attempt = 0
        while (true) {
            if (attempt > 0) {
                delay((infoRetryMs shl (attempt - 1).coerceAtMost(MAX_RETRY_SHIFT)).coerceAtMost(maxInfoRetryMs))
                // Online only: an offline or unauthorized device can't answer, and going online again re-fetches.
                val stillListed = synchronized(lock) {
                    key in inFlight && raw.any { Key(it.serial, it.transportId) == key && it.state == DeviceState.Device }
                }
                if (!stillListed) break
            }
            attempt++
            info = try {
                parseInfo(client.shell(key.serial, INFO_COMMAND))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (info != null) break
        }
        synchronized(lock) {
            // Ignore the result if a reconnect cleared the in-flight marker meanwhile.
            if (!inFlight.remove(key)) return
            if (info != null) {
                infoCache[key] = info
                _devices.value = merged()
            }
        }
    }

    private fun merged(): List<Device> = raw.map { d -> infoCache[Key(d.serial, d.transportId)]?.let { d.copy(info = it) } ?: d }

    private fun parseInfo(output: String): DeviceInfo? {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return null
        return DeviceInfo(
            model = lines[0],
            androidVersion = lines[1],
            sdk = lines[2].toIntOrNull() ?: return null,
            serialNo = lines.getOrNull(3),
        )
    }
}
