package com.programmersbox.scrcpyviewer.cdm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a device's lease stands. */
sealed interface HoldState {
    /** Asking CDM; [position] is the queue position once CDM reports one (null: not queued, or not yet known). */
    data class Waiting(val position: Int? = null) : HoldState

    /** The device may be used. [managed] is false when CDM is not running and the app goes ahead without a lease. */
    data class Held(val managed: Boolean) : HoldState

    /** CDM took the device back (released from its window, or the hold expired). */
    data object Lost : HoldState

    /** CDM said no: [reason] is its message. */
    data class Refused(val reason: String) : HoldState
}

/** Thrown by [CdmHolds.awaitReady] when the device may not be used. */
class DeviceNotGrantedException(message: String) : Exception(message)

/**
 * Leases on devices through Claude Device Manager, one CDM holder per physical device. A lease starts with [lease]
 * and ends with [release]; leases of the same device share one CDM hold (counted), so a short Wi-Fi switch never queues
 * behind this app's own mirror window. While held, the hold is renewed every [renewIntervalMs]; a renew answered
 * `NONE` marks it [HoldState.Lost]. When CDM is not running every lease is [HoldState.Held] with `managed = false`.
 *
 * Holder IDs are `<appName>:<device>`, see [holderFor].
 */
class CdmHolds(
    private val client: CdmClient,
    /** `scrcpy-vwr`, or `scrcpy-vwr-<n>` for the n-th concurrently running copy (see [CdmInstance]). */
    val appName: String,
    private val scope: CoroutineScope,
    private val renewIntervalMs: Long = RENEW_INTERVAL_MS,
    private val acquireTimeoutSec: Int = CdmClient.DEFAULT_ACQUIRE_TIMEOUT_SEC,
    /** Runs the blocking HTTP calls; tests pass an Unconfined one so a lease without CDM is granted synchronously. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Lease(val holder: String, val device: String) {
        var count = 0
        var job: Job? = null
        val serials = HashSet<String>()
    }

    private val lock = Any()
    private val leases = HashMap<String, Lease>()
    private val serialHolder = HashMap<String, String>()
    /** Holders whose release request has not finished yet (the shutdown hook sends it again). */
    private val releasing = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, HoldState>>(emptyMap())
    /** Each leased holder's state, by holder ID. */
    val states: StateFlow<Map<String, HoldState>> = _states.asStateFlow()

    /** The CDM holder ID for [device] (hardware or adb serial), reduced to characters CDM shows cleanly. */
    fun holderFor(device: String): String = "$appName:${device.replace(Regex("[^A-Za-z0-9._-]"), "-")}"

    /** True when [holderId] (from CDM's device list) is one of this running copy's holders. */
    fun isOurs(holderId: String): Boolean = holderId.startsWith("$appName:")

    /** The holder [serial]'s lease uses (look its state up in [states]), or null when it has no lease. */
    fun holderOf(serial: String): String? = synchronized(lock) { serialHolder[serial] }

    /**
     * Starts (or joins) the lease for [serial], the adb serial the app will use; [device] is what CDM is asked for
     * (the hardware serial when known, which is the same over USB and Wi-Fi).
     */
    fun lease(serial: String, device: String = serial) {
        synchronized(lock) {
            val holder = holderFor(device)
            serialHolder[serial] = holder
            val lease = leases.getOrPut(holder) { Lease(holder, device) }
            lease.serials += serial
            lease.count++
            if (lease.count == 1) startAcquire(lease)
        }
    }

    /** Asks CDM again for [serial]'s device after it was [HoldState.Lost] or [HoldState.Refused]. */
    fun retry(serial: String) {
        synchronized(lock) {
            val lease = serialHolder[serial]?.let(leases::get) ?: return
            val state = _states.value[lease.holder]
            if (state is HoldState.Waiting || state is HoldState.Held) return
            startAcquire(lease)
        }
    }

    /**
     * Ends one lease of [serial]; the last one for its device cancels the wait or releases the CDM hold. Returns the
     * release request (join it to know CDM has the device back), or null when the device is still leased.
     */
    fun release(serial: String): Job? {
        val ended = synchronized(lock) {
            val holder = serialHolder[serial] ?: return null
            val lease = leases[holder] ?: return null
            lease.count--
            if (lease.count > 0) return null
            leases -= holder
            lease.serials.forEach { if (serialHolder[it] == holder) serialHolder -= it }
            lease.job?.cancel()
            _states.update { it - holder }
            val job = scope.launch(io + NonCancellable, start = CoroutineStart.LAZY) {
                try {
                    client.release(holder)
                } finally {
                    synchronized(lock) { if (releasing[holder] === coroutineContext[Job]) releasing -= holder }
                }
            }
            releasing[holder] = job
            job
        }
        ended.start()
        return ended
    }

    /**
     * Suspends until [serial] may be used. Returns at once when it has no lease. Throws [DeviceNotGrantedException]
     * when CDM refused it or took it back.
     */
    suspend fun awaitReady(serial: String) {
        val holder = synchronized(lock) { serialHolder[serial] } ?: return
        when (val s = states.map { it[holder] }.first { it == null || it !is HoldState.Waiting }) {
            null, is HoldState.Held -> Unit
            is HoldState.Refused -> throw DeviceNotGrantedException(s.reason)
            HoldState.Lost -> throw DeviceNotGrantedException("Claude Device Manager took this device back")
            is HoldState.Waiting -> Unit
        }
    }

    /** Runs [block] under a lease of [serial] (shared with a mirror window's lease of the same device). */
    suspend fun <T> withHold(serial: String, device: String = serial, block: suspend () -> T): T {
        lease(serial, device)
        try {
            awaitReady(serial)
            return block()
        } finally {
            release(serial)
        }
    }

    /**
     * Releases holds a killed earlier run of this copy left behind: every hold in CDM whose holder starts with
     * `<appName>:`. Call once at startup, before the first [lease].
     */
    fun releaseStale() {
        val snapshot = client.devices() ?: return
        snapshot.devices.mapNotNull { it.holder?.id }.filter(::isOurs).distinct().forEach { client.release(it) }
    }

    /** Releases every hold still out or still being released, blocking; for the JVM shutdown hook. */
    fun releaseAllNow() {
        val holders = synchronized(lock) {
            leases.values.filter { _states.value[it.holder] == HoldState.Held(managed = true) }.map { it.holder } +
                releasing.keys
        }
        holders.forEach { runCatching { client.release(it) } }
    }

    /** Installs [releaseAllNow] as a JVM shutdown hook (normal exit, Ctrl+C, SIGTERM). */
    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread(::releaseAllNow, "cdm-release"))
    }

    /** Caller holds [lock]. */
    private fun startAcquire(lease: Lease) {
        lease.job?.cancel()
        _states.update { it + (lease.holder to HoldState.Waiting()) }
        // A release of the same holder still in flight must land first, or it would drop the new hold.
        val pendingRelease = releasing[lease.holder]
        lease.job = scope.launch(io) {
            try {
                pendingRelease?.join()
                if (!acquire(lease)) return@launch
                renewLoop(lease)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never block the device on our own bug: go ahead unmanaged.
                set(lease, HoldState.Held(managed = false))
            }
        }
    }

    /** Acquires (re-asking on TIMEOUT); true when granted by CDM, so the hold must be renewed. */
    private suspend fun acquire(lease: Lease): Boolean {
        while (true) {
            val outcome = client.acquire(lease.holder, lease.device, timeoutSec = acquireTimeoutSec) { position ->
                set(lease, HoldState.Waiting(position))
            }
            when (outcome) {
                is AcquireOutcome.Granted -> {
                    set(lease, HoldState.Held(managed = true))
                    return true
                }
                AcquireOutcome.NotRunning -> {
                    set(lease, HoldState.Held(managed = false))
                    return false
                }
                is AcquireOutcome.Refused -> {
                    set(lease, HoldState.Refused(outcome.reason))
                    return false
                }
                AcquireOutcome.Timeout -> Unit
            }
        }
    }

    private suspend fun renewLoop(lease: Lease) {
        while (true) {
            delay(renewIntervalMs)
            when (withContext(io) { client.renew(lease.holder) }) {
                true -> set(lease, HoldState.Held(managed = true))
                // CDM did not answer: it was closed. Keep going without a lease.
                null -> set(lease, HoldState.Held(managed = false))
                false -> {
                    if (_states.value[lease.holder] == HoldState.Held(managed = false)) {
                        // CDM came back after a restart and knows nothing of us: lease again.
                        set(lease, HoldState.Waiting())
                        if (!acquire(lease)) return
                    } else {
                        set(lease, HoldState.Lost)
                        return
                    }
                }
            }
        }
    }

    private fun set(lease: Lease, state: HoldState) {
        synchronized(lock) {
            if (leases[lease.holder] !== lease) return
            _states.update { it + (lease.holder to state) }
        }
    }

    companion object {
        const val RENEW_INTERVAL_MS = 60_000L
    }
}
