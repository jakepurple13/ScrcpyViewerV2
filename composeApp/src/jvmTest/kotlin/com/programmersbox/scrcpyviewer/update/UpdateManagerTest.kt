package com.programmersbox.scrcpyviewer.update

import com.programmersbox.scrcpyviewer.withFakeAppKoin
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.UpdateEvent
import dev.nucleusframework.updater.UpdateLevel
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.exception.ChecksumException
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.exception.NoMatchingFileException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManagerTest {
    private val scope = CoroutineScope(Job())
    private val checkedAt = Instant.parse("2026-10-29T12:00:00Z")

    @AfterTest
    fun cleanup() {
        scope.cancel()
    }

    private fun manager(
        backend: UpdateBackend = FakeUpdateBackend { FakeUpdateBackend.available("1.6.0") },
        macNotInApplications: Boolean = false,
        currentVersion: String = "1.5.0",
    ) = UpdateManager(
        backend = backend,
        scope = scope,
        currentVersion = currentVersion,
        bundledAdbInUse = { null },
        macNotInApplications = macNotInApplications,
        repo = "jakepurple13/ScrcpyViewerV2",
        now = { checkedAt },
        notesTimeoutMs = 200,
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun UpdateManager.awaitState(predicate: (UpdateState) -> Boolean): UpdateState =
        await { state.first(predicate) }

    private fun UpdateManager.checkAndAwaitAvailable(): Release {
        check(userInitiated = false)
        return assertIs<UpdateState.Available>(awaitState { it is UpdateState.Available }).release
    }

    private val v160 = Release(
        version = "1.6.0",
        htmlUrl = "https://github.com/jakepurple13/ScrcpyViewerV2/releases/tag/v1.6.0",
        publishedAt = "2026-10-29T12:00:00.000Z",
    )

    @Test
    fun `Available becomes a release linking to its tag page`() {
        val m = manager()
        m.check(userInitiated = false)
        val state = m.awaitState { it !is UpdateState.Idle && it !is UpdateState.Checking }
        assertEquals(UpdateState.Available(v160), state)
        assertEquals(v160, m.available.value)
        assertEquals(checkedAt, m.lastChecked.value)
        assertFalse(m.popupDismissed.value)
    }

    @Test
    fun `blank release date is dropped`() {
        val info = FakeUpdateBackend.info("1.6.0", releaseDate = "")
        assertNull(info.toRelease("o/r").publishedAt)
        assertEquals("https://github.com/o/r/releases/tag/v1.6.0", info.toRelease("o/r").htmlUrl)
    }

    @Test
    fun `NotAvailable is UpToDate and clears a known release`() {
        var result: UpdateResult = FakeUpdateBackend.available("1.6.0")
        val m = manager(FakeUpdateBackend { result })
        m.checkAndAwaitAvailable()
        result = UpdateResult.NotAvailable
        m.check(userInitiated = true)
        assertEquals(UpdateState.UpToDate, m.awaitState { it is UpdateState.UpToDate })
        assertNull(m.available.value)
        assertEquals(checkedAt, m.lastChecked.value)
    }

    @Test
    fun `launch check error is silent`() {
        val m = manager(FakeUpdateBackend { UpdateResult.Error(NetworkException("HTTP 404 for latest-mac.yml")) })
        m.check(userInitiated = false)
        assertEquals(UpdateState.Idle, m.awaitState { it !is UpdateState.Checking })
        assertNull(m.available.value)
        assertNull(m.lastChecked.value)
    }

    @Test
    fun `manual check error shows its message`() {
        val m = manager(FakeUpdateBackend { UpdateResult.Error(NetworkException("HTTP 404 for latest-mac.yml")) })
        m.check(userInitiated = true)
        assertEquals(
            UpdateState.Failed("Couldn't check for updates: HTTP 404 for latest-mac.yml", null),
            m.awaitState { it is UpdateState.Failed },
        )
    }

    @Test
    fun `thrown check failure is treated like an error`() {
        val m = manager(FakeUpdateBackend { throw IllegalStateException("boom") })
        m.check(userInitiated = true)
        assertEquals("Couldn't check for updates: boom", assertIs<UpdateState.Failed>(m.awaitState { it is UpdateState.Failed }).message)
    }

    @Test
    fun `manual check error keeps the known release`() {
        var result: UpdateResult = FakeUpdateBackend.available("1.6.0")
        val m = manager(FakeUpdateBackend { result })
        m.checkAndAwaitAvailable()
        result = UpdateResult.Error(NetworkException("offline"))
        m.check(userInitiated = true)
        val failed = assertIs<UpdateState.Failed>(m.awaitState { it is UpdateState.Failed })
        assertEquals(v160, failed.release)
        assertEquals(v160, m.available.value)
    }

    @Test
    fun `download progress maps to Downloading then Installing`() {
        val finish = CompletableDeferred<Unit>()
        val file = File("ScrcpyViewer-1.6.0-mac-arm64.zip")
        val backend = FakeUpdateBackend(downloadBlock = { emit ->
            emit(DownloadProgress(40, 100, 40.0))
            finish.await()
            emit(DownloadProgress(100, 100, 100.0, file = file))
        }) { FakeUpdateBackend.available("1.6.0") }
        val m = manager(backend)
        val release = m.checkAndAwaitAvailable()
        m.startUpdate()
        m.awaitState { it == UpdateState.Downloading(release, 40, 100) }
        finish.complete(Unit)
        assertEquals(UpdateState.Installing(release), m.awaitState { it is UpdateState.Installing })
        assertTrue(backend.installed.isEmpty(), "installs only once main() calls installPending")

        m.installPending()
        assertEquals(listOf(file), backend.installed)
    }

    @Test
    fun `installPending does nothing without a download`() {
        val backend = FakeUpdateBackend { FakeUpdateBackend.available("1.6.0") }
        manager(backend).installPending()
        assertTrue(backend.installed.isEmpty())
    }

    @Test
    fun `download that ends without a file fails`() {
        val m = manager(FakeUpdateBackend(downloadBlock = { emit -> emit(DownloadProgress(100, 100, 100.0)) }) {
            FakeUpdateBackend.available("1.6.0")
        })
        m.checkAndAwaitAvailable()
        m.startUpdate()
        assertEquals("Download finished without a file", assertIs<UpdateState.Failed>(m.awaitState { it is UpdateState.Failed }).message)
    }

    @Test
    fun `checksum failure becomes Failed with the release kept`() {
        val m = manager(FakeUpdateBackend(downloadBlock = { throw ChecksumException("abc", "def") }) {
            FakeUpdateBackend.available("1.6.0")
        })
        m.checkAndAwaitAvailable()
        m.startUpdate()
        val failed = assertIs<UpdateState.Failed>(m.awaitState { it is UpdateState.Failed })
        assertEquals(v160, failed.release)
        assertNotNull(failed.message)
        assertNotNull(m.available.value)
    }

    @Test
    fun `startUpdate retries from Failed`() {
        var fail = true
        val m = manager(FakeUpdateBackend(downloadBlock = { emit ->
            if (fail) throw NetworkException("HTTP 500")
            emit(DownloadProgress(100, 100, 100.0, file = File("a.zip")))
        }) { FakeUpdateBackend.available("1.6.0") })
        m.checkAndAwaitAvailable()
        m.startUpdate()
        m.awaitState { it is UpdateState.Failed }
        fail = false
        m.startUpdate()
        m.awaitState { it is UpdateState.Installing }
    }

    @Test
    fun `check ignored while downloading`() {
        val backend = FakeUpdateBackend(downloadBlock = { awaitCancellation() }) { FakeUpdateBackend.available("1.6.0") }
        val m = manager(backend)
        m.checkAndAwaitAvailable()
        m.startUpdate()
        assertIs<UpdateState.Downloading>(m.state.value)
        await { backend.downloadStarted.await() }

        m.check(userInitiated = true)
        m.startUpdate()

        assertIs<UpdateState.Downloading>(m.state.value)
        assertEquals(1, backend.checks.get())
        assertEquals(1, backend.downloads.get())
    }

    @Test
    fun `cancel returns to Available`() {
        val backend = FakeUpdateBackend(downloadBlock = { awaitCancellation() }) { FakeUpdateBackend.available("1.6.0") }
        val m = manager(backend)
        m.checkAndAwaitAvailable()
        m.startUpdate()
        await { backend.downloadStarted.await() }
        m.cancel()
        assertEquals(UpdateState.Available(v160), m.state.value)
    }

    @Test
    fun `no self update when Nucleus cannot install`() {
        val backend = FakeUpdateBackend(canInstall = false) { FakeUpdateBackend.available("1.6.0") }
        val m = manager(backend)
        m.checkAndAwaitAvailable()
        assertFalse(m.canSelfUpdate(m.available.value!!))
        m.startUpdate()
        assertIs<UpdateState.Available>(m.state.value)
        assertEquals(0, backend.downloads.get())
    }

    @Test
    fun `no self update from a disk image`() {
        val backend = FakeUpdateBackend { FakeUpdateBackend.available("1.6.0") }
        val m = manager(backend, macNotInApplications = true)
        m.checkAndAwaitAvailable()
        assertFalse(m.canSelfUpdate(m.available.value!!))
        m.startUpdate()
        assertEquals(0, backend.downloads.get())
    }

    @Test
    fun `dismissPopup sets popupDismissed but keeps available`() {
        val m = manager()
        m.checkAndAwaitAvailable()
        m.dismissPopup()
        assertTrue(m.popupDismissed.value)
        assertEquals(v160, m.available.value)
        assertIs<UpdateState.Available>(m.state.value)
    }

    @Test
    fun `checkPeriodically checks at once and again after each interval`() {
        val backend = FakeUpdateBackend { FakeUpdateBackend.available("1.6.0") }
        val m = manager(backend)
        val loop = scope.launch { m.checkPeriodically(interval = 50.milliseconds) }
        await { while (backend.checks.get() < 3) delay(10) }
        loop.cancel()
        assertIs<UpdateState.Available>(m.awaitState { it is UpdateState.Available })
    }

    @Test
    fun `a newer release found later shows the popup again`() {
        var version = "1.6.0"
        val m = manager(FakeUpdateBackend { FakeUpdateBackend.available(version) })
        m.checkAndAwaitAvailable()
        m.dismissPopup()
        assertEquals("1.6.0", m.checkAndAwaitAvailable().version)
        assertTrue(m.popupDismissed.value)
        version = "1.7.0"
        m.check(userInitiated = false)
        await { m.available.first { it?.version == "1.7.0" } }
        assertFalse(m.popupDismissed.value)
    }

    @Test
    fun `marker for this version is a completed update`() {
        val backend = FakeUpdateBackend(event = UpdateEvent("1.5.0", "1.6.0", UpdateLevel.MINOR))
        val m = manager(backend, currentVersion = "1.6.0")
        assertEquals("1.5.0", m.updatedFrom)
        assertNull(m.previousFailure)
        assertEquals(1, backend.eventsConsumed)
    }

    @Test
    fun `marker for another version is a failed update`() {
        val m = manager(FakeUpdateBackend(event = UpdateEvent("1.5.0", "1.6.0", UpdateLevel.MINOR)), currentVersion = "1.5.0")
        assertEquals("Updating to 1.6.0 did not complete.", m.previousFailure)
        assertNull(m.updatedFrom)
    }

    @Test
    fun `a copy that cannot install leaves the marker alone`() {
        val backend = FakeUpdateBackend(canInstall = false, event = UpdateEvent("1.5.0", "1.6.0", UpdateLevel.MINOR))
        val m = manager(backend, currentVersion = "1.6.0")
        assertEquals(0, backend.eventsConsumed)
        assertNull(m.updatedFrom)
        assertNull(m.previousFailure)
    }

    @Test
    fun `release notes are attached to the release`() {
        val asked = mutableListOf<String>()
        val m = manager(FakeUpdateBackend(notes = { asked += it; "- Faster\n- Fixes" }) { FakeUpdateBackend.available("1.6.0") })
        val release = m.checkAndAwaitAvailable()
        assertEquals("- Faster\n- Fixes", release.body)
        assertEquals(release, m.available.value)
        assertEquals(listOf("1.6.0"), asked)
        assertTrue(m.canSelfUpdate(release))
    }

    @Test
    fun `failing release notes leave the release without notes`() {
        val m = manager(FakeUpdateBackend(notes = { throw IllegalStateException("rate limited") }) {
            FakeUpdateBackend.available("1.6.0")
        })
        // The backend swallows its own failures; a throwing one still must not fail the check.
        m.check(userInitiated = true)
        val state = m.awaitState { it !is UpdateState.Idle && it !is UpdateState.Checking }
        assertEquals(UpdateState.Available(v160), state)
    }

    @Test
    fun `slow release notes are given up on`() {
        val m = manager(FakeUpdateBackend(notes = { awaitCancellation() }) { FakeUpdateBackend.available("1.6.0") })
        m.check(userInitiated = false)
        assertNull(assertIs<UpdateState.Available>(m.awaitState { it is UpdateState.Available }).release.body)
    }

    @Test
    fun `no file for this OS but a newer release is offered without self update`() {
        val backend = FakeUpdateBackend(latest = { "1.6.0" }, notes = { "notes" }) {
            UpdateResult.Error(NoMatchingFileException("Linux", "ARM64", "deb"))
        }
        val m = manager(backend)
        val release = m.checkAndAwaitAvailable()
        assertEquals(v160.copy(publishedAt = null, body = "notes"), release)
        assertFalse(m.canSelfUpdate(release))
        m.startUpdate()
        assertEquals(0, backend.downloads.get())
    }

    @Test
    fun `no file for this OS and nothing newer is up to date`() {
        for (latest in listOf("1.5.0", null)) {
            val m = manager(FakeUpdateBackend(latest = { latest }) {
                UpdateResult.Error(NoMatchingFileException("Linux", "ARM64", "deb"))
            })
            m.check(userInitiated = true)
            assertEquals(UpdateState.UpToDate, m.awaitState { it !is UpdateState.Idle && it !is UpdateState.Checking })
        }
    }

    @Test
    fun `release body and metadata version parsing`() {
        assertEquals("- a\n- b", releaseBody("""{"tag_name":"v1.6.0","body":"- a\n- b\n"}"""))
        assertNull(releaseBody("""{"tag_name":"v1.6.0","body":null}"""))
        assertNull(releaseBody("""{"body":"  "}"""))
        assertNull(releaseBody("not json"))
        assertEquals("1.6.1", metadataVersion("version: 1.6.1\nfiles:\n  - url: a.zip\n"))
        assertEquals("1.6.1", metadataVersion("version: '1.6.1'\n"))
        assertNull(metadataVersion("files: []\n"))
    }

    @Test
    fun `no marker is neither`() {
        val m = manager()
        assertNull(m.previousFailure)
        assertNull(m.updatedFrom)
    }

    @Test
    fun `mac disk image and translocation are outside Applications`() {
        assertTrue(macRunningOutsideApplications("/Volumes/ScrcpyViewer/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer", isMac = true))
        assertTrue(macRunningOutsideApplications("/private/var/folders/x/AppTranslocation/1/d/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer", isMac = true))
        assertFalse(macRunningOutsideApplications("/Applications/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer", isMac = true))
        assertFalse(macRunningOutsideApplications(null, isMac = true))
        assertFalse(macRunningOutsideApplications("/Volumes/x", isMac = false))
    }

    @Test
    fun `published executable type per OS`() {
        assertEquals("zip", publishedExecutableType("Mac OS X"))
        assertEquals("nsis", publishedExecutableType("Windows 11"))
        assertEquals("deb", publishedExecutableType("Linux"))
        assertNull(publishedExecutableType("FreeBSD"))
    }

    @Test
    fun `koin provides an idle UpdateManager and removes 1_5_0 downloads`() {
        val dataDir = Files.createTempDirectory("scrcpyviewer-update")
        try {
            val legacy = Files.createDirectories(dataDir.resolve("updates/1.6.0"))
            Files.writeString(legacy.resolve("ScrcpyViewer-1.6.0.dmg"), "x")
            withFakeAppKoin(dataDir) { koin ->
                val m = koin.get<UpdateManager>()
                assertEquals(UpdateState.Idle, m.state.value)
                assertFalse(Files.exists(dataDir.resolve("updates")))
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }
}
