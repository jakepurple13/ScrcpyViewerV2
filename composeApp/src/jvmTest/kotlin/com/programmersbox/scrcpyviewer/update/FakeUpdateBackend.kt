package com.programmersbox.scrcpyviewer.update

import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.UpdateEvent
import dev.nucleusframework.updater.UpdateFile
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateLevel
import dev.nucleusframework.updater.UpdateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * An [UpdateBackend] that never touches the network or installs. [check] answers with [result] (the default never
 * returns); [download] runs [downloadBlock], which by default reports full progress and hands back a file.
 */
class FakeUpdateBackend(
    override val canInstall: Boolean = true,
    private val event: UpdateEvent? = null,
    private val downloadBlock: suspend (emit: suspend (DownloadProgress) -> Unit) -> Unit = { emit ->
        emit(DownloadProgress(100, 100, 100.0, file = File("ScrcpyViewer-1.5.0-mac-arm64.zip")))
    },
    private val latest: suspend () -> String? = { null },
    private val notes: suspend (version: String) -> String? = { null },
    private val result: suspend () -> UpdateResult = { awaitCancellation() },
) : UpdateBackend {
    override suspend fun latestVersion(): String? = latest()

    override suspend fun releaseNotes(version: String): String? = notes(version)

    val checks = AtomicInteger()
    val downloads = AtomicInteger()
    val downloadStarted = CompletableDeferred<Unit>()
    val installed = mutableListOf<File>()
    /** Files installed by [installAndQuit], which does not reopen the app. */
    val installedQuitting = mutableListOf<File>()
    var eventsConsumed = 0
        private set

    override suspend fun check(): UpdateResult {
        checks.incrementAndGet()
        return result()
    }

    override fun download(info: UpdateInfo): Flow<DownloadProgress> = flow {
        downloads.incrementAndGet()
        downloadStarted.complete(Unit)
        downloadBlock { emit(it) }
    }

    override fun installAndRestart(file: File) {
        installed += file
    }

    override fun installAndQuit(file: File) {
        installedQuitting += file
    }

    override fun consumeUpdateEvent(): UpdateEvent? {
        eventsConsumed++
        return event
    }

    companion object {
        fun info(version: String, releaseDate: String = "2026-10-29T12:00:00.000Z"): UpdateInfo {
            val file = UpdateFile(
                url = "https://example.invalid/v$version/ScrcpyViewer-$version-mac-arm64.zip",
                sha512 = "",
                size = 100,
                fileName = "ScrcpyViewer-$version-mac-arm64.zip",
            )
            return UpdateInfo(version, releaseDate, listOf(file), file)
        }

        fun available(version: String, level: UpdateLevel = UpdateLevel.MINOR): UpdateResult =
            UpdateResult.Available(info(version), level)
    }
}
