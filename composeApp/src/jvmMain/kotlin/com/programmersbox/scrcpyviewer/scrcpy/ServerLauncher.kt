package com.programmersbox.scrcpyviewer.scrcpy

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.options.ServerArgs
import com.programmersbox.scrcpyviewer.tools.BundledTools
import com.programmersbox.scrcpyviewer.closingOnCancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/** Hands out random 31-bit scrcpy session ids (`scid`), unique within the process. */
object ScidAllocator {
    private val used = HashSet<Int>()

    @Synchronized
    fun next(): Int {
        while (true) {
            val scid = Random.nextInt() and 0x7fffffff
            if (used.add(scid)) return scid
        }
    }
}

/** Picks local tunnel ports from 27183 upward, skipping ports this process holds and ports that cannot be bound. */
object PortAllocator {
    const val FIRST = 27183
    private val taken = HashSet<Int>()

    fun next(): Int = next(FIRST, 0xFFFF)

    @Synchronized
    fun next(first: Int, last: Int): Int {
        for (port in first..last) {
            if (port in taken || !isFree(port)) continue
            taken += port
            return port
        }
        throw IOException("No free local port in $first..$last")
    }

    @Synchronized
    fun release(port: Int) {
        taken -= port
    }

    private fun isFree(port: Int): Boolean =
        try {
            ServerSocket().use {
                it.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                true
            }
        } catch (_: IOException) {
            false
        }
}

/**
 * A connected scrcpy server. Sockets are null for streams disabled by `no-video`, `no-audio` or `no-control`
 * (including the ones ServerArgs derives). [cleanup] closes the sockets, stops the process and removes the adb
 * tunnel if it is still open; it runs once, later calls return immediately.
 */
class ServerConnection(
    val video: Socket?,
    val audio: Socket?,
    val control: Socket?,
    val deviceMeta: DeviceMeta,
    val process: Process,
    val cleanup: suspend () -> Unit,
)

/** Starts a scrcpy server on a device and connects to it. */
fun interface ConnectionStarter {
    suspend fun start(serial: String, values: OptionValues): ServerConnection
}

/**
 * Starts the bundled scrcpy server the way v5.0 `app/src/server.c` (`run_server`, `sc_server_connect_to`) and
 * `app/src/adb/adb_tunnel.c` do:
 * 1. `adb push` the jar to [DEVICE_SERVER_PATH];
 * 2. open the tunnel: `adb reverse localabstract:scrcpy_<scid> tcp:<port>` then listen on the port (on a listen
 *    failure the reverse is removed and the next port is tried); if `adb reverse` fails, or `force-adb-forward`
 *    (or `tunnel-host`/`tunnel-port`, which imply it in cli.c) is set, `adb forward tcp:<port> localabstract:...`;
 * 3. run `adb -s <serial> shell CLASSPATH=<jar> app_process / com.genymobile.scrcpy.Server 5.0 <args>`;
 * 4. accept (reverse) or connect (forward) the video, audio and control sockets in that order, skipping disabled
 *    ones. In forward mode the first socket is retried [connectAttempts] times, [connectDelayMs] apart, and must
 *    deliver the 1 dummy byte the server writes; the other sockets connect once;
 * 5. remove the tunnel (server.c closes it as soon as the sockets are connected), set TCP_NODELAY on control;
 * 6. read the 64-byte device meta from the first socket.
 *
 * If the server process exits while connecting, pending accept/connect/read calls are woken up and start fails.
 * On failure or cancellation everything opened so far is cleaned up before the exception propagates.
 */
class ServerLauncher(
    private val adb: AdbClient,
    private val tools: BundledTools,
    private val connectAttempts: Int = 100,
    private val connectDelayMs: Long = 100,
    /** How long cleanup waits for the server to exit after closing the sockets before killing it (server.c: 1 s). */
    private val stopTimeoutMs: Long = 1_000,
    /** Bound of each `adb reverse/forward --remove` run by cleanup, so a hung adb cannot stall a teardown. */
    private val cleanupAdbTimeoutMs: Long = 3_000,
    /** Opens the reverse-tunnel listener on a local port (a seam for tests). */
    private val listen: (port: Int) -> ServerSocket = ::listenLoopback,
) : ConnectionStarter {

    override suspend fun start(serial: String, values: OptionValues): ServerConnection = withContext(Dispatchers.IO) {
        val scid = ScidAllocator.next()
        val run = Run(serial, "$SOCKET_NAME_PREFIX%08x".format(scid))
        try {
            push(serial)
            val e = values.entries
            val tunnelHost = e["tunnel-host"]
            val tunnelPort = e["tunnel-port"]?.toIntOrNull()?.takeIf { it != 0 }
            val forceForward = "force-adb-forward" in e || tunnelHost != null || tunnelPort != null
            run.openTunnel(forceForward, portRange(e["port"]))

            val args = ServerArgs.build(values, scid, run.forward)
            val video = "video=false" !in args
            val audio = "audio=false" !in args
            val control = "control=false" !in args
            if (!video && !audio && !control) throw IOException("Video, audio and control are all disabled")

            val process = adb.startProcess(
                "-s", serial, "shell", "CLASSPATH=$DEVICE_SERVER_PATH", "app_process", "/", SERVER_CLASS,
                tools.serverVersion, *args.toTypedArray(),
            )
            run.process = process
            run.drainOutput(process)

            val meta = run.connect(video, audio, control, tunnelHost ?: LOCALHOST, tunnelPort ?: run.port)
            ServerConnection(run.video, run.audio, run.control, meta, process, cleanup = run::cleanup)
        } catch (t: Throwable) {
            run.cleanup()
            ensureActive() // when cancelled, a "Socket closed" from the woken-up accept() must not mask the cancellation
            throw t
        }
    }

    private suspend fun push(serial: String) {
        val r = adb.exec("-s", serial, "push", tools.serverJar.toString(), DEVICE_SERVER_PATH)
        if (r.exitCode != 0) throw IOException("adb push failed: ${(r.stderr + r.stdout).trim()}")
    }

    private fun portRange(option: String?): IntRange {
        if (option == null) return PortAllocator.FIRST..0xFFFF
        val parts = option.split(':').map { it.trim().toInt() }
        return parts[0]..parts.getOrElse(1) { parts[0] }
    }

    /** State of one start attempt; everything here is released by [cleanup]. */
    private inner class Run(private val serial: String, private val socketName: String) {
        var forward = false
        var port = 0
        @Volatile var process: Process? = null
        @Volatile var serverSocket: ServerSocket? = null
        @Volatile var pending: Socket? = null
        @Volatile var video: Socket? = null
        @Volatile var audio: Socket? = null
        @Volatile var control: Socket? = null
        @Volatile var connected = false
        @Volatile var serverExited = false
        private val tunnelOpen = AtomicBoolean(false)
        private val cleaned = AtomicBoolean(false)
        private val output = ArrayDeque<String>()

        suspend fun openTunnel(forceForward: Boolean, range: IntRange) {
            if (!forceForward && openReverse(range)) return
            openForward(range)
        }

        /**
         * False when `adb reverse` itself fails (it would fail on any port) or no port in [range] can be listened on;
         * the caller then falls back to `adb forward`, as `sc_adb_tunnel_open` does.
         */
        private suspend fun openReverse(range: IntRange): Boolean {
            var from = range.first
            while (from <= range.last) {
                val p = try {
                    PortAllocator.next(from, range.last)
                } catch (_: IOException) {
                    break
                }
                val r = adb.exec("-s", serial, "reverse", "localabstract:$socketName", "tcp:$p")
                if (r.exitCode != 0) {
                    PortAllocator.release(p)
                    log.log(System.Logger.Level.WARNING, "'adb reverse' failed, fallback to 'adb forward': ${r.stderr.trim()}")
                    return false
                }
                try {
                    serverSocket = listen(p)
                    port = p
                    tunnelOpen.set(true)
                    return true
                } catch (e: IOException) {
                    adb.exec("-s", serial, "reverse", "--remove", "localabstract:$socketName")
                    PortAllocator.release(p)
                    from = p + 1
                }
            }
            log.log(
                System.Logger.Level.WARNING,
                "Could not listen on any port in ${range.first}..${range.last}, fallback to 'adb forward'",
            )
            return false
        }

        private suspend fun openForward(range: IntRange) {
            forward = true
            var from = range.first
            var lastError = ""
            while (from <= range.last) {
                val p = PortAllocator.next(from, range.last)
                val r = adb.exec("-s", serial, "forward", "tcp:$p", "localabstract:$socketName")
                if (r.exitCode == 0) {
                    port = p
                    tunnelOpen.set(true)
                    return
                }
                lastError = r.stderr.trim()
                PortAllocator.release(p)
                from = p + 1
            }
            throw IOException("adb forward failed: $lastError")
        }

        suspend fun closeTunnel() {
            if (!tunnelOpen.compareAndSet(true, false)) return
            val args = if (forward) {
                arrayOf("-s", serial, "forward", "--remove", "tcp:$port")
            } else {
                runCatching { serverSocket?.close() }
                arrayOf("-s", serial, "reverse", "--remove", "localabstract:$socketName")
            }
            val r = runCatching { adb.execWithTimeout(cleanupAdbTimeoutMs, *args) }
            if (r.isSuccess && r.getOrNull() == null) {
                log.log(System.Logger.Level.WARNING, "adb ${args.drop(2).joinToString(" ")} timed out after $cleanupAdbTimeoutMs ms")
            }
        }

        fun drainOutput(process: Process) {
            thread(isDaemon = true, name = "scrcpy-server-$serial") {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        log.log(System.Logger.Level.INFO, "[server $serial] $line")
                        synchronized(output) {
                            output.addLast(line)
                            if (output.size > 20) output.removeFirst()
                        }
                    }
                }
            }
        }

        private fun recentOutput(): String = synchronized(output) { output.joinToString("\n") }

        /** Closes every socket (wakes up blocked accept/connect/read calls). */
        fun closeSockets() {
            listOf(serverSocket, pending, video, audio, control).forEach { runCatching { it?.close() } }
        }

        suspend fun connect(video: Boolean, audio: Boolean, control: Boolean, host: String, port: Int): DeviceMeta =
            coroutineScope {
                val process = process!!
                // sc_server_on_terminated: the server dying must wake up accept() and friends.
                val watcher = launch {
                    try {
                        runInterruptible { process.waitFor() }
                        serverExited = true
                        if (!connected) closeSockets()
                    } catch (e: CancellationException) {
                        if (!connected) closeSockets()
                        throw e
                    }
                }
                try {
                    // Cancelling (close() while connecting) must wake accept/connect even if the watcher never ran.
                    val meta = closingOnCancel(onCancel = { if (!connected) closeSockets() }) {
                        connectBlocking(video, audio, control, host, port)
                    }
                    connected = true
                    meta
                } catch (e: IOException) {
                    if (serverExited) {
                        val out = recentOutput()
                        throw IOException(
                            "Server exited (${runCatching { process.exitValue() }.getOrNull()})" +
                                if (out.isNotBlank()) ":\n$out" else "",
                            e,
                        )
                    }
                    throw e
                } finally {
                    watcher.cancel()
                }
            }

        private suspend fun connectBlocking(video: Boolean, audio: Boolean, control: Boolean, host: String, port: Int): DeviceMeta {
            if (!forward) {
                val ss = serverSocket!!
                if (video) this.video = runInterruptible { ss.accept() }
                if (audio) this.audio = runInterruptible { ss.accept() }
                if (control) this.control = runInterruptible { ss.accept() }
            } else {
                val first = connectAndReadByte(host, port)
                fun next(): Socket = Socket().also { s ->
                    pending = s
                    s.connect(InetSocketAddress(host, port))
                }
                this.video = if (video) first else null
                this.audio = if (audio) (if (!video) first else next()) else null
                this.control = if (control) (if (!video && !audio) first else next()) else null
                pending = null
            }
            runCatching { this.control?.tcpNoDelay = true }
            closeTunnel()
            val first = this.video ?: this.audio ?: this.control!!
            return try {
                runInterruptible { readDeviceMeta(DataInputStream(first.getInputStream())) }
            } catch (e: IOException) {
                throw IOException("Could not retrieve device information", e)
            }
        }

        /** `connect_to_server`: the adb forward accepts even with no server behind it, so wait for the dummy byte. */
        private suspend fun connectAndReadByte(host: String, port: Int): Socket {
            repeat(connectAttempts) { attempt ->
                if (serverExited) throw IOException("Server exited")
                val s = Socket()
                pending = s
                val ok = try {
                    runInterruptible {
                        s.connect(InetSocketAddress(host, port))
                        s.getInputStream().read() != -1
                    }
                } catch (_: IOException) {
                    false
                }
                if (ok) return s
                runCatching { s.close() }
                if (attempt < connectAttempts - 1) delay(connectDelayMs)
            }
            throw IOException("Could not connect to the server through adb forward on $host:$port")
        }

        suspend fun cleanup() {
            if (!cleaned.compareAndSet(false, true)) return
            withContext(NonCancellable + Dispatchers.IO) {
                closeSockets()
                closeTunnel()
                process?.let { p ->
                    val exited = runCatching { p.waitFor(stopTimeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                    if (!exited) {
                        log.log(System.Logger.Level.WARNING, "Killing the server...")
                        p.destroyForcibly()
                    }
                }
                if (port != 0) PortAllocator.release(port)
            }
        }
    }

    companion object {
        private fun listenLoopback(port: Int): ServerSocket {
            val ss = ServerSocket()
            try {
                ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), LISTEN_BACKLOG)
            } catch (e: IOException) {
                ss.close()
                throw e
            }
            return ss
        }

        const val DEVICE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"
        const val SERVER_CLASS = "com.genymobile.scrcpy.Server"
        private const val SOCKET_NAME_PREFIX = "scrcpy_"
        private const val LOCALHOST = "127.0.0.1"
        /** scrcpy listens with a backlog of 1; macOS then drops back-to-back connects, so allow the three streams. */
        private const val LISTEN_BACKLOG = 3
        private val log = System.getLogger("scrcpyviewer.ServerLauncher")
    }
}
