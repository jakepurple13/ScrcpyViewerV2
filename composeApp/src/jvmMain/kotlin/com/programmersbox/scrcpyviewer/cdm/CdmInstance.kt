package com.programmersbox.scrcpyviewer.cdm

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Which running copy of the app this is, so concurrent copies get different CDM holder IDs while each copy keeps the
 * same ID across launches. Copy n holds an OS lock on `<dir>/instance-<n>.lock` for its lifetime; the lowest free n
 * wins. The OS drops the lock when the process dies (even `kill -9`), so the next launch takes the same n and can
 * release the holds that run left behind.
 */
class CdmInstance private constructor(val number: Int, private val lock: FileLock?) {
    /** `scrcpy-vwr` for the first copy, `scrcpy-vwr-<n>` for the others. */
    val appName: String get() = if (number == 1) APP_NAME else "$APP_NAME-$number"

    companion object {
        const val APP_NAME = "scrcpy-vwr"
        private const val MAX_INSTANCES = 64

        /** Locks the lowest free instance number under [dir]. Falls back to number 1 (unlocked) if locking fails. */
        fun claim(dir: Path): CdmInstance {
            runCatching { Files.createDirectories(dir) }
            for (n in 1..MAX_INSTANCES) {
                val lock = tryLock(dir.resolve("instance-$n.lock")) ?: continue
                return CdmInstance(n, lock)
            }
            return CdmInstance(1, null)
        }

        private fun tryLock(file: Path): FileLock? = try {
            val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) channel.close()
            lock
        } catch (_: Exception) {
            null
        }
    }
}
