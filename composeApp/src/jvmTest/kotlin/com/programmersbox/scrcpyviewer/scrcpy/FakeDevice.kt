package com.programmersbox.scrcpyviewer.scrcpy

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.tools.BundledTools
import kotlinx.coroutines.awaitCancellation
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A process that runs until destroyed (or [exit] is called). */
class FakeProcess : Process() {
    private val done = CountDownLatch(1)
    @Volatile var destroyed = false
        private set
    @Volatile private var code = 0

    fun exit(code: Int) {
        this.code = code
        done.countDown()
    }

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int {
        done.await()
        return code
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
    override fun exitValue(): Int {
        if (done.count > 0) throw IllegalThreadStateException("running")
        return code
    }
    override fun isAlive(): Boolean = done.count > 0
    override fun destroy() {
        destroyed = true
        exit(137)
    }
}

/**
 * Fake adb that records every call and simulates the scrcpy server: when the server process starts, it connects
 * to the reverse port (or accepts on the forwarded port) once per enabled stream and writes the device meta
 * (after the dummy byte in forward mode) on the first socket.
 */
class FakeAdb(
    private val reverseFails: Boolean = false,
    private val deviceConnects: Boolean = true,
    private val deviceName: String = "Pixel 8",
    /** `adb reverse/forward --remove` never returns (a hung adb). */
    private val removeHangs: Boolean = false,
    /** `adb push` never returns (a wedged link). */
    private val pushHangs: Boolean = false,
) : AdbClient(adbPath = { error("no real adb in tests") }) {
    val calls = CopyOnWriteArrayList<List<String>>()
    /** Device-side sockets in stream order (video, audio, control minus disabled ones). */
    val deviceSockets = CopyOnWriteArrayList<Socket>()
    val connected = CountDownLatch(1)
    val processes = CopyOnWriteArrayList<FakeProcess>()

    @Volatile var reversePort: Int? = null
    @Volatile private var forwardListener: ServerSocket? = null

    override suspend fun exec(vararg args: String): ProcessResult {
        val a = args.toList()
        calls += a
        val cmd = a.getOrNull(2)
        if (removeHangs && (cmd == "reverse" || cmd == "forward") && a[3] == "--remove") awaitCancellation()
        if (pushHangs && cmd == "push") awaitCancellation()
        if (cmd == "reverse" && a[3] != "--remove") {
            if (reverseFails) return ProcessResult(1, "", "error: more than one device/emulator")
            reversePort = a[4].removePrefix("tcp:").toInt()
        }
        if (cmd == "forward" && a[3] != "--remove") {
            val port = a[3].removePrefix("tcp:").toInt()
            forwardListener = ServerSocket(port, 50, InetAddress.getLoopbackAddress())
        }
        if (cmd == "forward" && a[3] == "--remove") forwardListener?.close()
        return ProcessResult(0, "", "")
    }

    override suspend fun shell(serial: String, cmd: String): String {
        calls += listOf("shell:$cmd")
        return ""
    }

    override fun startProcess(vararg args: String): Process {
        val a = args.toList()
        calls += a
        val process = FakeProcess()
        processes += process
        if (!deviceConnects) return process
        val count = listOf("video=false", "audio=false", "control=false").count { it !in a }
        val forward = "tunnel_forward=true" in a
        thread(isDaemon = true, name = "fake-device") {
            try {
                simulate(count, forward)
            } catch (_: java.io.IOException) {
                // The host closed the forward listener or sockets first (forward --remove, cleanup): device gone.
            }
        }
        return process
    }

    private fun simulate(count: Int, forward: Boolean) {
        for (i in 0 until count) {
            val s = if (forward) forwardListener!!.accept() else Socket(InetAddress.getLoopbackAddress(), reversePort!!)
            deviceSockets += s
            if (i == 0) {
                val out = DataOutputStream(s.getOutputStream())
                if (forward) out.writeByte(0)
                val name = ByteArray(StreamProtocol.DEVICE_NAME_FIELD_LENGTH)
                deviceName.toByteArray().copyInto(name)
                out.write(name)
                out.flush()
            }
        }
        connected.countDown()
    }

    fun shellCommand(): List<String> = calls.first { "app_process" in it }
}

fun fakeTools(): BundledTools {
    val dir = Files.createTempDirectory("bundled")
    Files.write(dir.resolve("scrcpy-server"), byteArrayOf(1, 2, 3))
    return BundledTools(resourcesDir = dir, dataDir = Files.createTempDirectory("data"))
}

/**
 * Captures the `scrcpyviewer.*` System.Logger (JUL) output instead of printing it, so intentional warnings and
 * stack traces do not clutter test output. Tests may inspect [records].
 */
object TestLogs {
    val records = CopyOnWriteArrayList<java.util.logging.LogRecord>()
    // Strong reference: JUL only keeps weak references to loggers.
    private val root = java.util.logging.Logger.getLogger("scrcpyviewer")

    init {
        root.useParentHandlers = false
        root.addHandler(object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() {}
            override fun close() {}
        })
    }

    fun install() = Unit
}
