package com.programmersbox.scrcpyviewer.adb

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.closingOnCancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Talks to the adb server over its socket protocol and runs the adb binary for everything else. */
open class AdbClient(
    private val adbPath: () -> Path,
    private val host: String = "127.0.0.1",
    private val port: Int = 5037,
    private val startServerTimeoutMs: Long = 10_000,
    /** Socket read timeout of [shell]: a hung device fails the call instead of blocking it forever. */
    private val shellReadTimeoutMs: Int = 10_000,
) {
    @Volatile
    private var started = false

    /**
     * True once this client started the adb server daemon itself (rather than reusing one already running). On macOS
     * the daemon gets the Local Network permission of whichever app started it, so a reused one may be blocked.
     */
    open val startedServer: Boolean get() = started

    open suspend fun startServer() {
        val result = runAdb(listOf("start-server"), startServerTimeoutMs)
        if (result.exitCode != 0) throw IOException("adb start-server failed (${result.exitCode}): ${result.stderr.trim()}")
        if ("daemon started successfully" in result.stdout + result.stderr) started = true
    }

    /** Stops the running adb server and starts a new one owned by this app. Drops every adb connection. */
    open suspend fun restartServer() {
        runAdb(listOf("kill-server"), startServerTimeoutMs)
        startServer()
    }

    /**
     * Runs `shell:<cmd>` on [serial] and returns its output. Throws [java.net.SocketTimeoutException] when the adb
     * server sends nothing for [shellReadTimeoutMs].
     */
    open suspend fun shell(serial: String, cmd: String): String = withContext(Dispatchers.IO) {
        openSocket().use { socket ->
            socket.soTimeout = shellReadTimeoutMs
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(AdbWire.encodeRequest("host:transport:$serial"))
            out.flush()
            AdbWire.readStatus(input)
            out.write(AdbWire.encodeRequest("shell:$cmd"))
            out.flush()
            AdbWire.readStatus(input)
            String(input.readBytes(), Charsets.UTF_8)
        }
    }

    /**
     * Runs `exec:<cmd>` on [serial] and returns its output byte for byte (no pty, so binary output such as a file
     * read with `unzip -p` arrives intact). Throws [java.net.SocketTimeoutException] like [shell].
     */
    open suspend fun execOut(serial: String, cmd: String): ByteArray = withContext(Dispatchers.IO) {
        openSocket().use { socket ->
            socket.soTimeout = shellReadTimeoutMs
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(AdbWire.encodeRequest("host:transport:$serial"))
            out.flush()
            AdbWire.readStatus(input)
            out.write(AdbWire.encodeRequest("exec:$cmd"))
            out.flush()
            AdbWire.readStatus(input)
            input.readBytes()
        }
    }

    /**
     * Runs `shell:<cmd>` on [serial] and emits its output as UTF-8 text chunks as they arrive. Unlike [shell] there
     * is no read timeout and nothing is buffered. Cancelling the collector (or completion) closes the socket.
     */
    open fun shellStream(serial: String, cmd: String): Flow<String> = flow {
        val socket = openSocket()
        // Cancelling closes the socket at once: a blocked read ignores interrupts and would otherwise never return.
        closingOnCancel(onCancel = { runCatching { socket.close() } }) {
            try {
                socket.soTimeout = 0
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(AdbWire.encodeRequest("host:transport:$serial"))
                out.flush()
                AdbWire.readStatus(input)
                out.write(AdbWire.encodeRequest("shell:$cmd"))
                out.flush()
                AdbWire.readStatus(input)
                // Bytes of a UTF-8 sequence split across reads stay in `bytes` until the rest arrives.
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                val bytes = ByteBuffer.allocate(8192)
                val chars = CharBuffer.allocate(8192)
                while (true) {
                    val n = try {
                        input.read(bytes.array(), bytes.position(), bytes.remaining())
                    } catch (e: IOException) {
                        // The closer closes the socket on cancellation; that is a cancel, not a read failure.
                        currentCoroutineContext().ensureActive()
                        throw e
                    }
                    val eof = n < 0
                    if (!eof) bytes.position(bytes.position() + n)
                    bytes.flip()
                    decoder.decode(bytes, chars, eof)
                    if (eof) decoder.flush(chars)
                    bytes.compact()
                    chars.flip()
                    if (chars.hasRemaining()) emit(chars.toString())
                    chars.clear()
                    if (eof) break
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Runs the adb binary with [args] (push, reverse, forward...). Cancellable; no time limit. */
    open suspend fun exec(vararg args: String): ProcessResult = runAdb(args.toList(), null)

    /** [exec] bounded by [timeoutMs]: null when it timed out (the adb process is then destroyed). */
    suspend fun execWithTimeout(timeoutMs: Long, vararg args: String): ProcessResult? =
        withTimeoutOrNull(timeoutMs) { exec(*args) }

    /**
     * Starts the adb binary with [args] as a long-running process (the scrcpy server's `adb shell`). stdin is
     * empty and stderr is merged into stdout; the caller must drain [Process.getInputStream] and destroy the process.
     */
    open fun startProcess(vararg args: String): Process =
        ProcessBuilder(listOf(adbPath().toString()) + args)
            .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
            .redirectErrorStream(true)
            .start()

    /**
     * Output goes to temp files rather than pipes: `adb start-server` forks a daemon that inherits and holds
     * pipe ends open, so reading a pipe to EOF could block forever. The wait is interruptible, and the process
     * is destroyed on timeout or cancellation.
     */
    private suspend fun runAdb(args: List<String>, timeoutMs: Long?): ProcessResult = withContext(Dispatchers.IO) {
        val outFile = Files.createTempFile("adb-out", ".txt")
        val errFile = Files.createTempFile("adb-err", ".txt")
        var process: Process? = null
        try {
            val started = ProcessBuilder(listOf(adbPath().toString()) + args)
                .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                .redirectOutput(outFile.toFile())
                .redirectError(errFile.toFile())
                .start()
            process = started
            val exit = runInterruptible {
                if (timeoutMs == null) {
                    started.waitFor()
                } else if (started.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    started.exitValue()
                } else {
                    throw IOException("adb ${args.joinToString(" ")} timed out after $timeoutMs ms")
                }
            }
            ProcessResult(exit, Files.readString(outFile), Files.readString(errFile))
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(errFile)
        }
    }

    private fun nullFile() = java.io.File(if (Os.isWindows) "NUL" else "/dev/null")

    /**
     * Cold flow of `host:track-devices-l` payloads. Completes when the server closes the stream and
     * throws on connection or protocol errors. Cancelling the collector closes the socket.
     */
    open fun trackDevices(): Flow<String> = flow {
        val socket = openSocket()
        // Cancelling closes the socket at once: a blocked read ignores interrupts and would otherwise never return.
        closingOnCancel(onCancel = { runCatching { socket.close() } }) {
            try {
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(AdbWire.encodeRequest("host:track-devices-l"))
                out.flush()
                AdbWire.readStatus(input)
                while (true) {
                    val payload = AdbWire.readHexLengthPayload(input) ?: break
                    emit(payload)
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun openSocket(): Socket = Socket().also {
        try {
            it.connect(InetSocketAddress(host, port), 3000)
        } catch (e: Throwable) {
            runCatching { it.close() }
            throw e
        }
    }
}
