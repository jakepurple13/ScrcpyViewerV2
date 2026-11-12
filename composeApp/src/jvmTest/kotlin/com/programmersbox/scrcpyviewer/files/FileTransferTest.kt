package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTransferTest {
    /**
     * A [Process] whose run is [block] on a coroutine: it exits with the block's exit code once the block returns, and
     * [destroy] exits it with 137 and cancels the block (its `finally` runs). Output is the block's stdout + stderr,
     * readable once it exits and also written to [outputFile]. [afterExit] runs in [waitFor] once it exited, on the
     * waiting thread. No real process is started.
     */
    class FakeProcess(
        scope: CoroutineScope,
        private val outputFile: java.nio.file.Path? = null,
        private val afterExit: () -> Unit = {},
        block: suspend () -> ProcessResult,
    ) : Process() {
        private val exited = java.util.concurrent.CountDownLatch(1)
        @Volatile private var exit = 0
        @Volatile private var output = ByteArray(0)
        @Volatile var destroyed = false
            private set
        private val job = scope.launch {
            val r = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                ProcessResult(137, "", "")
            }
            finish(r.exitCode, (r.stdout + r.stderr).toByteArray())
        }

        @Synchronized
        private fun finish(code: Int, out: ByteArray) {
            if (exited.count == 0L) return
            exit = code
            output = out
            outputFile?.let { java.nio.file.Files.write(it, out) }
            exited.countDown()
        }

        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
        override fun getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun getInputStream(): java.io.InputStream = object : java.io.InputStream() {
            private var bytes: java.io.InputStream? = null
            private fun open(): java.io.InputStream {
                bytes?.let { return it }
                exited.await()
                return java.io.ByteArrayInputStream(output).also { bytes = it }
            }
            override fun read(): Int = open().read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = open().read(b, off, len)
        }

        override fun waitFor(): Int {
            exited.await()
            afterExit()
            return exit
        }

        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = exited.await(timeout, unit)

        override fun exitValue(): Int {
            if (exited.count != 0L) throw IllegalThreadStateException("running")
            return exit
        }

        override fun isAlive(): Boolean = exited.count != 0L

        override fun destroy() {
            destroyed = true
            finish(137, ByteArray(0))
            job.cancel()
        }
    }

    /**
     * Records every adb call; [handler] answers it (it may suspend). [startProcess] runs [handler] in a [FakeProcess];
     * [shell] answers through [shellHandler] and is recorded in [shellCalls].
     */
    private class FakeAdb(
        val shellHandler: suspend (String) -> String = { "" },
        val handler: suspend (List<String>) -> ProcessResult = { ProcessResult(0, "", "") },
    ) : AdbClient({ Paths.get("adb") }) {
        val calls: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        val shellCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val processes: MutableList<FakeProcess> = Collections.synchronizedList(mutableListOf())
        private val processScope = CoroutineScope(Job() + Dispatchers.Default)

        override suspend fun exec(vararg args: String): ProcessResult {
            calls += args.toList()
            return handler(args.toList())
        }

        /** Runs between a process's exit and its waiter seeing it. */
        @Volatile var afterExit: () -> Unit = {}

        override fun startProcess(vararg args: String): Process = error("transfers write adb's output to a file")

        override fun startProcess(output: java.nio.file.Path, vararg args: String): Process {
            calls += args.toList()
            return FakeProcess(processScope, output, { afterExit() }) { handler(args.toList()) }.also { processes += it }
        }

        override suspend fun shell(serial: String, cmd: String): String {
            shellCalls += cmd
            return shellHandler(cmd)
        }
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val scans: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @AfterTest
    fun tearDown() = scope.cancel()

    private val tmp = kotlin.io.path.createTempDirectory("transfers")

    @AfterTest
    fun deleteTemp() {
        tmp.toFile().deleteRecursively()
    }

    private fun queue(adb: AdbClient, connected: () -> Boolean = { true }, target: String = DEFAULT_PUSH_TARGET, timeoutMs: Long = 5_000) =
        FileTransferQueue("SER", adb, target, connected, { scans += it }, scope, timeoutMs, tempRoot = tmp.resolve("scrcpy-vwr"))

    private val noDirs: (java.nio.file.Path) -> Boolean = { false }

    @Test
    fun `apk is installed, anything else is pushed, case-insensitively`() {
        assertEquals(TransferKind.Install, classify(Paths.get("/x/app.apk"), noDirs))
        assertEquals(TransferKind.Install, classify(Paths.get("/x/APP.APK"), noDirs))
        assertEquals(TransferKind.Install, classify(Paths.get("/x/a.b.Apk"), noDirs))
        assertEquals(TransferKind.Push, classify(Paths.get("/x/app.apks"), noDirs))
        assertEquals(TransferKind.Push, classify(Paths.get("/x/apk"), noDirs))
        assertEquals(TransferKind.Push, classify(Paths.get("/x/photo.jpg"), noDirs))
        assertEquals(TransferKind.Push, classify(Paths.get("/x/app.apk.txt"), noDirs))
    }

    @Test
    fun `directories are pushed even when named apk`() {
        assertEquals(TransferKind.Push, classify(Paths.get("/x/folder.apk")) { true })
        assertEquals(TransferKind.Push, classify(Paths.get("/x/folder")) { true })
    }

    @Test
    fun `push target defaults to sdcard Download and follows the option`() {
        assertEquals("/sdcard/Download/", pushTarget(OptionValues(emptyMap())))
        assertEquals("/sdcard/Download/", pushTarget(OptionValues(mapOf("push-target" to ""))))
        assertEquals("/data/local/tmp/", pushTarget(OptionValues(mapOf("push-target" to "/data/local/tmp/"))))
    }

    @Test
    fun `adb arguments match file_pusher c`() {
        val p = Paths.get("/tmp/my app.apk")
        assertEquals(listOf("-s", "SER", "install", "-r", p.toString()), installArgs("SER", p))
        assertEquals(listOf("-s", "SER", "push", p.toString(), "/sdcard/Download/"), pushArgs("SER", p, "/sdcard/Download/"))
    }

    @Test
    fun `error line extraction`() {
        assertEquals(
            "INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 1 is older than current 2",
            adbErrorLine(ProcessResult(1, "Performing Streamed Install\n",
                "adb: failed to install /x/app.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 1 is older than current 2]\n")),
        )
        assertEquals(
            "failed to copy '/x/a.txt' to '/system/a.txt': remote couldn't create file: Read-only file system",
            adbErrorLine(ProcessResult(1, "", "adb: error: failed to copy '/x/a.txt' to '/system/a.txt': remote couldn't create file: Read-only file system\n")),
        )
        assertEquals("device 'SER' not found", adbErrorLine(ProcessResult(1, "", "adb: error: device 'SER' not found\n")))
        assertEquals("something odd", adbErrorLine(ProcessResult(1, "", "\nsomething odd\n\n")))
        assertEquals("adb exited with code 7", adbErrorLine(ProcessResult(7, "", "")))
    }

    @Test
    fun `progress labels`() {
        val apk = TransferRequest(Paths.get("/x/app.apk"), TransferKind.Install)
        val jpg = TransferRequest(Paths.get("/x/photo.jpg"), TransferKind.Push)
        assertEquals("Installing app.apk…", TransferProgress(apk, 1, 1).label)
        assertEquals("Pushing 2 of 3: photo.jpg…", TransferProgress(jpg, 2, 3).label)
        assertEquals("Installed app.apk", TransferResult.Installed("app.apk").message)
        assertEquals("Pushed photo.jpg to /sdcard/Download/", TransferResult.Pushed("photo.jpg", "/sdcard/Download/").message)
        assertEquals("Could not install app.apk: INSTALL_FAILED_X", TransferResult.Failed(apk, "INSTALL_FAILED_X").message)
    }

    @Test
    fun `requests run sequentially in order, push then scan`() = runBlocking<Unit> {
        var running = 0
        var maxRunning = 0
        val lock = Any()
        val adb = FakeAdb {
            synchronized(lock) { running++; maxRunning = maxOf(maxRunning, running) }
            kotlinx.coroutines.delay(30)
            synchronized(lock) { running-- }
            ProcessResult(0, "", "")
        }
        val q = queue(adb, target = "/sdcard/Pictures/")
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(3).toList() }
        assertEquals(3, q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk"), Paths.get("/x/b.jpg"), Paths.get("/x/c.txt")), noDirs)).size)
        val got = withTimeout(5_000) { results.await() }
        assertEquals(
            listOf(
                TransferResult.Installed("a.apk"),
                TransferResult.Pushed("b.jpg", "/sdcard/Pictures/"),
                TransferResult.Pushed("c.txt", "/sdcard/Pictures/"),
            ),
            got,
        )
        assertEquals(1, maxRunning)
        assertEquals(
            listOf(
                listOf("-s", "SER", "install", "-r", Paths.get("/x/a.apk").toString()),
                listOf("-s", "SER", "push", Paths.get("/x/b.jpg").toString(), "/sdcard/Pictures/"),
                listOf("-s", "SER", "push", Paths.get("/x/c.txt").toString(), "/sdcard/Pictures/"),
            ),
            adb.calls.toList(),
        )
        // v5.0 request_scan_file: the push target, once per successful push.
        assertEquals(listOf("/sdcard/Pictures/", "/sdcard/Pictures/"), scans.toList())
        withTimeout(5_000) { q.progress.first { it == null } }
    }

    @Test
    fun `progress counts the batch`() = runBlocking<Unit> {
        val gates = List(3) { CompletableDeferred<Unit>() }
        var n = 0
        val adb = FakeAdb { gates[n++].await(); ProcessResult(0, "", "") }
        val q = queue(adb)
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.jpg"), Paths.get("/x/b.jpg"), Paths.get("/x/c.jpg")), noDirs))
        withTimeout(5_000) { q.progress.first { it != null } }.let { assertEquals("Pushing 1 of 3: a.jpg…", it!!.label) }
        gates[0].complete(Unit)
        withTimeout(5_000) { q.progress.first { it?.index == 2 } }.let { assertEquals("Pushing 2 of 3: b.jpg…", it!!.label) }
        gates[1].complete(Unit)
        gates[2].complete(Unit)
        withTimeout(5_000) { q.progress.first { it == null } }
        assertNull(q.progress.value)
    }

    @Test
    fun `failure reports adb's error and does not scan`() = runBlocking<Unit> {
        val adb = FakeAdb { args ->
            if (args[2] == "install") ProcessResult(1, "Performing Streamed Install\n", "adb: failed to install x: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n")
            else ProcessResult(1, "", "adb: error: failed to copy 'b' to '/sdcard/Download/b': remote Permission denied\n")
        }
        val q = queue(adb)
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(2).toList() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk"), Paths.get("/x/b")), noDirs))
        val got = withTimeout(5_000) { results.await() }
        assertEquals("Could not install a.apk: INSTALL_FAILED_VERSION_DOWNGRADE", got[0].message)
        assertEquals("Could not push b: failed to copy 'b' to '/sdcard/Download/b': remote Permission denied", got[1].message)
        assertTrue(scans.isEmpty())
    }

    @Test
    fun `old adb that prints Failure with exit 0 is a failure`() = runBlocking<Unit> {
        val q = queue(FakeAdb { ProcessResult(0, "Failure [INSTALL_FAILED_INVALID_APK]\n", "") })
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk")), noDirs))
        assertEquals("Could not install a.apk: INSTALL_FAILED_INVALID_APK", withTimeout(5_000) { result.await() }.message)
    }

    @Test
    fun `disconnected device is refused without running adb`() = runBlocking<Unit> {
        val adb = FakeAdb()
        val q = queue(adb, connected = { false })
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        assertTrue(q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk")), noDirs)).isEmpty())
        val r = withTimeout(5_000) { result.await() }
        assertIs<TransferResult.Refused>(r)
        assertEquals("Device not connected: cannot install", r.message)
        assertTrue(adb.calls.isEmpty())
    }

    @Test
    fun `a disconnect mid-queue cancels the rest with one result and no adb`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val connected = java.util.concurrent.atomic.AtomicBoolean(true)
        val adb = FakeAdb { gate.await(); ProcessResult(0, "", "") }
        val q = queue(adb, connected = { connected.get() })
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(2).toList() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.jpg"), Paths.get("/x/b.jpg"), Paths.get("/x/c.apk")), noDirs))
        withTimeout(5_000) { q.progress.first { it != null } }
        connected.set(false)
        gate.complete(Unit)
        val got = withTimeout(5_000) { results.await() }
        assertIs<TransferResult.Pushed>(got[0])
        assertEquals(TransferResult.Cancelled(2), got[1])
        assertEquals("2 transfers cancelled: device disconnected", got[1].message)
        assertEquals("1 transfer cancelled: device disconnected", TransferResult.Cancelled(1).message)
        kotlinx.coroutines.delay(100)
        assertEquals(1, adb.calls.size) // nothing else ran
        assertNull(q.progress.value)
        // The queue still works once the device is back.
        connected.set(true)
        val next = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/d.txt")), noDirs))
        assertEquals(TransferResult.Pushed("d.txt", DEFAULT_PUSH_TARGET), withTimeout(5_000) { next.await() })
    }

    @Test
    fun `timeout is reported`() = runBlocking<Unit> {
        val q = queue(FakeAdb { awaitCancellation() }, timeoutMs = 100)
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk")), noDirs))
        assertEquals("Could not install a.apk: timed out after 0 min", withTimeout(5_000) { result.await() }.message)
    }

    @Test
    fun `closing the window cancels the running adb and drops the rest`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val adb = FakeAdb {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val windowScope = CoroutineScope(Job() + Dispatchers.Default)
        val q = FileTransferQueue("SER", adb, DEFAULT_PUSH_TARGET, { true }, { scans += it }, windowScope)
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk"), Paths.get("/x/b.jpg")), noDirs))
        withTimeout(5_000) { started.await() }
        windowScope.cancel()
        withTimeout(5_000) { cancelled.await() }
        kotlinx.coroutines.delay(100)
        assertEquals(1, adb.calls.size)
        assertNull(q.progress.value)
        assertTrue(scans.isEmpty())
    }

    // ---- Jobs: per-folder push, pull, device install, progress, cancel ----

    private suspend fun FileTransferQueue.awaitJob(id: Long, predicate: (TransferJob) -> Boolean): TransferJob =
        withTimeout(5_000) { jobs.first { list -> list.any { it.id == id && predicate(it) } } }.first { it.id == id }

    private fun pull(dir: java.nio.file.Path, name: String, size: Long? = null) =
        TransferRequest(dir, TransferKind.Pull, deviceDir = "/sdcard/Download", deviceName = name, size = size)

    @Test
    fun `push into a device folder targets that folder, and dismiss drops the finished job`() = runBlocking<Unit> {
        val adb = FakeAdb()
        val q = queue(adb)
        val host = Paths.get("/x/photo.jpg")
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        val id = q.enqueue(listOf(TransferRequest(host, TransferKind.Push, deviceDir = "/sdcard/DCIM/Camera"))).single()
        assertEquals(TransferResult.Pushed("photo.jpg", "/sdcard/DCIM/Camera"), withTimeout(5_000) { result.await() })
        assertEquals(listOf(listOf("-s", "SER", "push", host.toString(), "/sdcard/DCIM/Camera/photo.jpg")), adb.calls.toList())
        assertEquals(JobState.Done, q.awaitJob(id) { it.state == JobState.Done }.state)
        q.dismiss(id)
        assertTrue(q.jobs.value.isEmpty())
    }

    @Test
    fun `pull never overwrites a host file`() = runBlocking<Unit> {
        val dir = java.nio.file.Files.createDirectories(tmp.resolve("Downloads"))
        val adb = FakeAdb { args -> java.nio.file.Files.writeString(Paths.get(args.last()), "data"); ProcessResult(0, "", "") }
        val q = queue(adb)
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(2).toList() }
        val first = q.enqueue(listOf(pull(dir, "a.txt"))).single()
        val done = q.awaitJob(first) { it.state == JobState.Done }
        q.enqueue(listOf(pull(dir, "a.txt")))
        val got = withTimeout(5_000) { results.await() }
        assertEquals(
            listOf(
                listOf("-s", "SER", "pull", "/sdcard/Download/a.txt", dir.resolve("a.txt").toString()),
                listOf("-s", "SER", "pull", "/sdcard/Download/a.txt", dir.resolve("a (1).txt").toString()),
            ),
            adb.calls.toList(),
        )
        assertEquals(listOf("Pulled a.txt to Downloads", "Pulled a (1).txt to Downloads"), got.map { it.message })
        assertEquals(dir.resolve("a.txt"), done.hostResult)
    }

    @Test
    fun `a failed pull says so and removes its partial file`() = runBlocking<Unit> {
        val dir = java.nio.file.Files.createDirectories(tmp.resolve("d"))
        val adb = FakeAdb { args ->
            java.nio.file.Files.writeString(Paths.get(args.last()), "part")
            ProcessResult(1, "adb: error: failed to copy: Permission denied\n", "")
        }
        val q = queue(adb)
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        val id = q.enqueue(listOf(pull(dir, "a.txt"))).single()
        assertEquals("Could not pull a.txt: failed to copy: Permission denied", withTimeout(5_000) { result.await() }.message)
        assertEquals(JobState.Failed("failed to copy: Permission denied"), q.awaitJob(id) { it.state is JobState.Failed }.state)
        assertFalse(java.nio.file.Files.exists(dir.resolve("a.txt")))
    }

    @Test
    fun `cancel kills the running job, removes its partial file and starts the next`() = runBlocking<Unit> {
        val dir = java.nio.file.Files.createDirectories(tmp.resolve("d"))
        val adb = FakeAdb { args ->
            if (args[2] == "pull") {
                java.nio.file.Files.write(Paths.get(args.last()), ByteArray(10))
                awaitCancellation()
            }
            ProcessResult(0, "", "")
        }
        val q = queue(adb)
        val results = Collections.synchronizedList(mutableListOf<TransferResult>())
        scope.launch { q.results.collect { results += it } }
        val ids = q.enqueue(listOf(pull(dir, "big.bin", size = 100), TransferRequest(Paths.get("/x/b.jpg"), TransferKind.Push)))
        q.awaitJob(ids[0]) { it.state == JobState.Running }
        withTimeout(5_000) { while (!java.nio.file.Files.exists(dir.resolve("big.bin"))) kotlinx.coroutines.delay(10) }
        q.cancel(ids[0])
        q.awaitJob(ids[0]) { it.state == JobState.Cancelled }
        assertTrue(adb.processes[0].destroyed)
        assertFalse(java.nio.file.Files.exists(dir.resolve("big.bin")))
        q.awaitJob(ids[1]) { it.state == JobState.Done }
        assertEquals("push", adb.calls[1][2])
        kotlinx.coroutines.delay(100)
        // A cancel is the user's own doing: no snackbar for it.
        assertEquals(listOf<TransferResult>(TransferResult.Pushed("b.jpg", DEFAULT_PUSH_TARGET)), results.toList())
    }

    @Test
    fun `cancel on a queued job removes it without running adb`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val adb = FakeAdb { gate.await(); ProcessResult(0, "", "") }
        val q = queue(adb)
        val ids = q.enqueue(requestsFor(listOf(Paths.get("/x/a.jpg"), Paths.get("/x/b.jpg"), Paths.get("/x/c.jpg")), noDirs))
        q.awaitJob(ids[0]) { it.state == JobState.Running }
        q.cancel(ids[1])
        assertTrue(q.jobs.value.none { it.id == ids[1] })
        gate.complete(Unit)
        q.awaitJob(ids[2]) { it.state == JobState.Done }
        assertEquals(listOf("a.jpg", "c.jpg"), adb.calls.map { Paths.get(it[3]).fileName.toString() })
    }

    @Test
    fun `a disconnect fails the running job and cancels the queued ones`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val connected = java.util.concurrent.atomic.AtomicBoolean(true)
        val adb = FakeAdb { gate.await(); ProcessResult(1, "adb: error: device 'SER' not found\n", "") }
        val q = queue(adb, connected = { connected.get() })
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(2).toList() }
        val ids = q.enqueue(requestsFor(listOf(Paths.get("/x/a.jpg"), Paths.get("/x/b.jpg"), Paths.get("/x/c.apk")), noDirs))
        q.awaitJob(ids[0]) { it.state == JobState.Running }
        connected.set(false)
        gate.complete(Unit)
        val got = withTimeout(5_000) { results.await() }
        assertEquals("Could not push a.jpg: device 'SER' not found", got[0].message)
        assertEquals(TransferResult.Cancelled(2), got[1])
        q.awaitJob(ids[2]) { it.state == JobState.Cancelled }
        assertEquals(
            listOf(JobState.Failed("device 'SER' not found"), JobState.Cancelled, JobState.Cancelled),
            q.jobs.value.map { it.state },
        )
        assertEquals(1, adb.calls.size)
    }

    @Test
    fun `pull progress follows the growing host file`() = runBlocking<Unit> {
        val dir = java.nio.file.Files.createDirectories(tmp.resolve("d"))
        val adb = FakeAdb { args ->
            java.nio.file.Files.write(Paths.get(args.last()), ByteArray(500))
            awaitCancellation()
        }
        val q = queue(adb)
        val id = q.enqueue(listOf(pull(dir, "v.mp4", size = 1000))).single()
        val job = q.awaitJob(id) { it.bytes == 500L }
        assertEquals(1000L, job.total)
        assertEquals(JobState.Running, job.state)
        assertTrue((job.bytesPerSecond ?: 0) > 0)
        q.cancel(id)
        q.awaitJob(id) { it.state == JobState.Cancelled }
    }

    @Test
    fun `push progress polls the device file and stops when the job ends`() = runBlocking<Unit> {
        val src = tmp.resolve("p.bin")
        java.nio.file.Files.write(src, ByteArray(800))
        val gate = CompletableDeferred<Unit>()
        val adb = FakeAdb(shellHandler = { "400\n" }) { gate.await(); ProcessResult(0, "", "") }
        val q = queue(adb)
        val id = q.enqueue(listOf(TransferRequest(src, TransferKind.Push, deviceDir = "/sdcard/X"))).single()
        val job = q.awaitJob(id) { it.bytes == 400L }
        assertEquals(800L, job.total)
        assertEquals("stat -c %s '/sdcard/X/p.bin'", adb.shellCalls.first())
        gate.complete(Unit)
        assertEquals(800L, q.awaitJob(id) { it.state == JobState.Done }.bytes)
        val polls = adb.shellCalls.size
        kotlinx.coroutines.delay(800)
        assertEquals(polls, adb.shellCalls.size, "no polling after the job ended")
    }

    @Test
    fun `cancelling a push removes the partial device file`() = runBlocking<Unit> {
        val src = tmp.resolve("it's.bin")
        java.nio.file.Files.write(src, ByteArray(8))
        val started = CompletableDeferred<Unit>()
        val adb = FakeAdb { started.complete(Unit); awaitCancellation() }
        val q = queue(adb)
        val id = q.enqueue(listOf(TransferRequest(src, TransferKind.Push, deviceDir = "/sdcard/X"))).single()
        // Once adb runs: a cancel before that removes nothing.
        withTimeout(5_000) { started.await() }
        q.cancel(id)
        q.awaitJob(id) { it.state == JobState.Cancelled }
        assertTrue("rm -f '/sdcard/X/it'\\''s.bin'" in adb.shellCalls, adb.shellCalls.toString())
    }

    @Test
    fun `installs and folders have no known total`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val q = queue(FakeAdb { gate.await(); ProcessResult(0, "", "") })
        val id = q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk")), noDirs)).single()
        assertNull(q.awaitJob(id) { it.state == JobState.Running }.total)
        gate.complete(Unit)
        q.awaitJob(id) { it.state == JobState.Done }
    }

    @Test
    fun `install from device pulls to temp, installs it and deletes the temp file`() = runBlocking<Unit> {
        val existed = Collections.synchronizedList(mutableListOf<Boolean>())
        val adb = FakeAdb { args ->
            if (args[2] == "pull") java.nio.file.Files.writeString(Paths.get(args.last()), "apk")
            else existed += java.nio.file.Files.exists(Paths.get(args.last()))
            ProcessResult(0, "Success\n", "")
        }
        val q = queue(adb)
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        val request = TransferRequest(tmp, TransferKind.InstallFromDevice, deviceDir = "/sdcard/Download", deviceName = "app.apk")
        val id = q.enqueue(listOf(request)).single()
        assertEquals(TransferResult.Installed("app.apk"), withTimeout(5_000) { result.await() })
        val temp = tmp.resolve("scrcpy-vwr").resolve("SER").resolve("install").resolve("app.apk")
        assertEquals(
            listOf(
                listOf("-s", "SER", "pull", "/sdcard/Download/app.apk", temp.toString()),
                listOf("-s", "SER", "install", "-r", temp.toString()),
            ),
            adb.calls.toList(),
        )
        assertEquals(listOf(true), existed.toList())
        q.awaitJob(id) { it.state == JobState.Done }
        assertFalse(java.nio.file.Files.exists(temp))
    }

    @Test
    fun `open-after pulls into the open temp folder and sets hostResult`() = runBlocking<Unit> {
        val adb = FakeAdb { args -> java.nio.file.Files.writeString(Paths.get(args.last()), "pdf"); ProcessResult(0, "", "") }
        val q = queue(adb)
        val request = TransferRequest(tmp.resolve("unused"), TransferKind.Pull, deviceDir = "/sdcard/Download", deviceName = "a.pdf", opensAfter = true)
        val id = q.enqueue(listOf(request)).single()
        val job = q.awaitJob(id) { it.state == JobState.Done }
        val expected = tmp.resolve("scrcpy-vwr").resolve("SER").resolve("open").resolve("a.pdf")
        assertEquals(expected, job.hostResult)
        assertTrue(java.nio.file.Files.exists(expected))
    }

    @Test
    fun `a drag-out pull goes into its drag folder and reports nothing`() = runBlocking<Unit> {
        val drag = java.nio.file.Files.createDirectories(tmp.resolve("drag"))
        val adb = FakeAdb { args -> java.nio.file.Files.writeString(Paths.get(args.last()), "data"); ProcessResult(0, "", "") }
        val q = queue(adb)
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(1).toList() }
        val id = q.enqueue(listOf(pull(drag, "a.txt").copy(dragOut = true))).single()
        val job = q.awaitJob(id) { it.state == JobState.Done }
        assertEquals(drag.resolve("a.txt"), job.hostResult)
        // The next result is the plain pull's: the drag-out pull said nothing.
        q.enqueue(listOf(pull(drag, "b.txt")))
        assertEquals(listOf("Pulled b.txt to drag"), withTimeout(5_000) { results.await() }.map { it.message })
    }

    @Test
    fun `hostUniquePath never names an existing file`() {
        val d = java.nio.file.Files.createDirectories(tmp.resolve("u"))
        fun touch(name: String) = java.nio.file.Files.createFile(d.resolve(name))
        assertEquals(d.resolve("a.txt"), hostUniquePath(d, "a.txt"))
        touch("a.txt")
        assertEquals(d.resolve("a (1).txt"), hostUniquePath(d, "a.txt"))
        touch("a (1).txt")
        assertEquals(d.resolve("a (2).txt"), hostUniquePath(d, "a.txt"))
        touch("noext")
        assertEquals(d.resolve("noext (1)"), hostUniquePath(d, "noext"))
        touch(".bashrc")
        assertEquals(d.resolve(".bashrc (1)"), hostUniquePath(d, ".bashrc"))
        touch("archive.tar.gz")
        assertEquals(d.resolve("archive.tar (1).gz"), hostUniquePath(d, "archive.tar.gz"))
        java.nio.file.Files.createDirectories(d.resolve("Photos.2024"))
        assertEquals(d.resolve("Photos.2024 (1)"), hostUniquePath(d, "Photos.2024", isDirectory = true))
    }

    @Test
    fun `pull and device-install labels and messages`() {
        val p = TransferRequest(Paths.get("/h/Downloads"), TransferKind.Pull, deviceDir = "/sdcard/Download", deviceName = "a.txt")
        val i = TransferRequest(Paths.get("/h"), TransferKind.InstallFromDevice, deviceDir = "/sdcard/Download", deviceName = "app.apk")
        assertEquals("a.txt", p.name)
        assertEquals("Pulling a.txt…", TransferProgress(p, 1, 1).label)
        assertEquals("Installing app.apk…", TransferProgress(i, 1, 1).label)
        assertEquals("Pulled a.txt to Downloads", TransferResult.Pulled("a.txt", Paths.get("/h/Downloads")).message)
        assertEquals("Could not pull a.txt: x", TransferResult.Failed(p, "x").message)
        assertEquals("Could not install app.apk: x", TransferResult.Failed(i, "x").message)
    }

    // ---- Review fixes ----

    @Test
    fun `a Wi-Fi serial becomes a valid path segment`() = runBlocking<Unit> {
        assertEquals("192.168.1.5_5555", serialPathSegment("192.168.1.5:5555"))
        assertEquals("emulator-5554", serialPathSegment("emulator-5554"))
        assertEquals("adb-R5C._adb-tls-connect._tcp", serialPathSegment("adb-R5C._adb-tls-connect._tcp"))
        assertEquals("a_b", serialPathSegment("a/b"))
        assertEquals("_", serialPathSegment(".."))
        val adb = FakeAdb { args -> java.nio.file.Files.writeString(Paths.get(args.last()), "pdf"); ProcessResult(0, "", "") }
        val q = FileTransferQueue("192.168.1.5:5555", adb, DEFAULT_PUSH_TARGET, { true }, {}, scope, tempRoot = tmp.resolve("scrcpy-vwr"))
        val request = TransferRequest(tmp, TransferKind.Pull, deviceDir = "/sdcard/Download", deviceName = "a.pdf", opensAfter = true)
        val job = q.awaitJob(q.enqueue(listOf(request)).single()) { it.state == JobState.Done }
        assertEquals(tmp.resolve("scrcpy-vwr").resolve("192.168.1.5_5555").resolve("open").resolve("a.pdf"), job.hostResult)
    }

    @Test
    fun `a cancel before adb starts removes nothing on the device or host`() = runBlocking<Unit> {
        val src = tmp.resolve("keep.bin")
        java.nio.file.Files.write(src, ByteArray(8))
        val dir = java.nio.file.Files.createDirectories(tmp.resolve("d"))
        java.nio.file.Files.writeString(dir.resolve("a.txt"), "mine")
        val adb = FakeAdb { ProcessResult(0, "", "") }
        val q = queue(adb)
        q.beforeStart = { q.cancel(it) }
        val ids = q.enqueue(listOf(TransferRequest(src, TransferKind.Push, deviceDir = "/sdcard/X"), pull(dir, "a.txt")))
        q.awaitJob(ids[0]) { it.state == JobState.Cancelled }
        q.awaitJob(ids[1]) { it.state == JobState.Cancelled }
        assertTrue(adb.calls.isEmpty(), adb.calls.toString())
        assertTrue(adb.shellCalls.none { it.startsWith("rm") }, adb.shellCalls.toString())
        assertEquals("mine", java.nio.file.Files.readString(dir.resolve("a.txt")))
        assertEquals(listOf("a.txt"), java.nio.file.Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a cancel after adb exited successfully does not undo the transfer`() = runBlocking<Unit> {
        val dir = java.nio.file.Files.createDirectories(tmp.resolve("d"))
        val adb = FakeAdb { args -> java.nio.file.Files.writeString(Paths.get(args.last()), "data"); ProcessResult(0, "", "") }
        val q = queue(adb)
        val running = java.util.concurrent.atomic.AtomicLong()
        q.beforeStart = { running.set(it) }
        adb.afterExit = { q.cancel(running.get()) }
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        val id = q.enqueue(listOf(pull(dir, "a.txt"))).single()
        assertEquals(TransferResult.Pulled("a.txt", dir), withTimeout(5_000) { result.await() })
        assertEquals(JobState.Done, q.awaitJob(id) { !it.active }.state)
        assertEquals("data", java.nio.file.Files.readString(dir.resolve("a.txt")))
    }

    @Test
    fun `pulls into a cache root that is not this user's own fail without running adb`() = runBlocking<Unit> {
        val adb = FakeAdb { ProcessResult(0, "", "") }
        val checked = Collections.synchronizedList(mutableListOf<java.nio.file.Path>())
        val root = tmp.resolve("cache")
        val q = FileTransferQueue(
            "SER", adb, DEFAULT_PUSH_TARGET, { true }, {}, scope, tempRoot = root,
            securePrivateRoot = { checked.add(it); throw java.io.IOException("The cache folder $it belongs to another user; not using it") },
        )
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(3).toList() }
        q.enqueue(
            listOf(
                TransferRequest(tmp, TransferKind.Pull, "/sdcard/Download", "a.pdf", opensAfter = true),
                TransferRequest(tmp, TransferKind.InstallFromDevice, "/sdcard/Download", "app.apk"),
                // A drag-out pull into the cache's drag folder.
                TransferRequest(root.resolve("SER").resolve("drag").resolve("x"), TransferKind.Pull, "/sdcard/Download", "b.jpg", dragOut = true),
            ),
        )
        val messages = withTimeout(5_000) { results.await() }.map { it.message }
        assertEquals(
            listOf(
                "Could not pull a.pdf: The cache folder $root belongs to another user; not using it",
                "Could not install app.apk: The cache folder $root belongs to another user; not using it",
                "Could not pull b.jpg: The cache folder $root belongs to another user; not using it",
            ),
            messages,
        )
        assertTrue(adb.calls.isEmpty(), adb.calls.toString())
        assertFalse(java.nio.file.Files.exists(root), "nothing written under it")
        assertEquals(3, checked.size)

        // A pull to a folder the user picked is not the cache: no check.
        val picked = java.nio.file.Files.createDirectories(tmp.resolve("picked"))
        val ok = FakeAdb { args -> java.nio.file.Files.writeString(Paths.get(args.last()), "x"); ProcessResult(0, "", "") }
        val q2 = FileTransferQueue(
            "SER", ok, DEFAULT_PUSH_TARGET, { true }, {}, scope, tempRoot = root,
            securePrivateRoot = { throw java.io.IOException("unexpected") },
        )
        q2.awaitJob(q2.enqueue(listOf(pull(picked, "c.txt"))).single()) { it.state == JobState.Done }
    }

    @Test
    fun `device names Windows refuses become valid host names, elsewhere they stay`() {
        assertEquals("a_b_c_d_e_f_g_h_.txt", hostFileName("a:b?c*d\"e<f>g|h\\.txt", windows = true))
        assertEquals("tab_name", hostFileName("tab\tname", windows = true))
        assertEquals("notes_", hostFileName("notes.", windows = true))
        assertEquals("notes__", hostFileName("notes .", windows = true))
        assertEquals("_CON", hostFileName("CON", windows = true))
        assertEquals("_nul.txt", hostFileName("nul.txt", windows = true))
        assertEquals("console.txt", hostFileName("console.txt", windows = true))
        assertEquals("photo.jpg", hostFileName("photo.jpg", windows = true))
        assertEquals("a:b?.txt", hostFileName("a:b?.txt", windows = false))
        // The sanitized name resolves (Paths.get would throw on Windows for the raw one).
        assertEquals("12_30_00.jpg", hostUniquePath(tmp, hostFileName("12:30:00.jpg", windows = true)).fileName.toString())
    }

    // ---- Replace pushes ----

    private val swapOf = { dir: String, name: String, temp: String ->
        "${keepOutsideGuard("$dir", "$dir/$name", name, "is the folder being pushed into")} && " +
            "rm -rf -- ${shellQuote("$dir/$name")} && mv ${shellQuote(temp)} ${shellQuote("$dir/$name")} && echo __svw_replaced__"
    }

    @Test
    fun `a Replace push goes to a temp name, then takes the item's place, so a folder never nests`() = runBlocking<Unit> {
        val folder = java.nio.file.Files.createDirectories(tmp.resolve("Photos"))
        val adb = FakeAdb(shellHandler = { if ("__svw_replaced__" in it) "__svw_replaced__\n" else "" })
        val q = queue(adb)
        q.replaceToken = { "T0K3N" }
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(listOf(TransferRequest(folder, TransferKind.Push, "/sdcard/X", "Photos", replace = true)))
        assertEquals(TransferResult.Pushed("Photos", "/sdcard/X"), withTimeout(5_000) { result.await() })
        val temp = "/sdcard/X/.svw-replace-T0K3N-Photos"
        assertEquals(listOf(listOf("-s", "SER", "push", folder.toString(), temp)), adb.calls.toList())
        assertEquals(listOf(swapOf("/sdcard/X", "Photos", temp)), adb.shellCalls.toList())
        assertEquals(listOf("/sdcard/X"), scans.toList())
    }

    @Test
    fun `a Replace push cancelled before adb starts touches nothing, after only its own temp copy`() = runBlocking<Unit> {
        val src = tmp.resolve("a.jpg")
        java.nio.file.Files.write(src, ByteArray(8))
        val request = TransferRequest(src, TransferKind.Push, "/sdcard/X", "a.jpg", replace = true)

        val idle = FakeAdb { ProcessResult(0, "", "") }
        val q1 = queue(idle)
        q1.beforeStart = { q1.cancel(it) }
        q1.awaitJob(q1.enqueue(listOf(request)).single()) { it.state == JobState.Cancelled }
        assertTrue(idle.calls.isEmpty())
        assertTrue(idle.shellCalls.isEmpty(), idle.shellCalls.toString())

        val started = CompletableDeferred<Unit>()
        val running = FakeAdb { started.complete(Unit); awaitCancellation() }
        val q2 = queue(running)
        q2.replaceToken = { "T0K3N" }
        val id = q2.enqueue(listOf(request)).single()
        withTimeout(5_000) { started.await() }
        q2.cancel(id)
        q2.awaitJob(id) { it.state == JobState.Cancelled }
        val removes = running.shellCalls.filter { it.startsWith("rm") }
        assertEquals(listOf("rm -rf -- '/sdcard/X/.svw-replace-T0K3N-a.jpg'"), removes, "never the original")
    }

    @Test
    fun `a Replace push whose swap is refused fails and keeps the original`() = runBlocking<Unit> {
        val src = tmp.resolve("a.jpg")
        java.nio.file.Files.write(src, ByteArray(8))
        val adb = FakeAdb(shellHandler = { if ("__svw_replaced__" in it) "a.jpg: is the folder being pushed into\n" else "" })
        val q = queue(adb)
        q.replaceToken = { "T0K3N" }
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(listOf(TransferRequest(src, TransferKind.Push, "/sdcard/X", "a.jpg", replace = true)))
        assertEquals("Could not push a.jpg: a.jpg: is the folder being pushed into", withTimeout(5_000) { result.await() }.message)
        // The temp copy goes only while something still stands in the item's place.
        assertEquals(
            "if [ -e '/sdcard/X/a.jpg' ] || [ -L '/sdcard/X/a.jpg' ]; then rm -rf -- '/sdcard/X/.svw-replace-T0K3N-a.jpg'; fi",
            adb.shellCalls.last(),
        )
    }

    @Test
    fun `adb output reaches the error line through the output file`() = runBlocking<Unit> {
        val q = queue(FakeAdb { ProcessResult(1, "", "adb: error: remote secure_mkdirs failed: Operation not permitted\n") })
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(listOf(TransferRequest(Paths.get("/x/b.jpg"), TransferKind.Push, deviceDir = "/system")))
        assertEquals("Could not push b.jpg: remote secure_mkdirs failed: Operation not permitted", withTimeout(5_000) { result.await() }.message)
    }
}
