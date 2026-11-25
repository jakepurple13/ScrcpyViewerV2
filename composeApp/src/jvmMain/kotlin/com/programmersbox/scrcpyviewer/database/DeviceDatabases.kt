package com.programmersbox.scrcpyviewer.database

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ShellRunner
import com.programmersbox.scrcpyviewer.files.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One database in an app's `databases` folder. [walSize] is null when there's no `-wal` beside it. */
data class DeviceDatabase(val name: String, val size: Long, val walSize: Long?, val hasShm: Boolean)

/**
 * An app's database files on the device, reached through an [AppDataAccess]. Writing goes through
 * `/data/local/tmp`, since the adb connection here can't feed a command's stdin; `cat >` into the existing file keeps
 * its owner and SELinux label.
 */
open class DeviceDatabases(
    private val adb: AdbClient,
    private val runner: ShellRunner,
    private val serial: String,
    private val tempName: () -> String = { "scrcpyvwr-${UUID.randomUUID()}.db" },
) {
    /** The databases, sorted as listed; `-wal`, `-shm` and `-journal` files fold into their database. */
    open suspend fun list(access: AppDataAccess): List<DeviceDatabase> {
        val output = runner.run(access.wrap("stat -c '%n|%s|%F' databases/* 2>/dev/null")).output
        val files = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size < 3 || parts[2] != "regular file") return@mapNotNull null
            val size = parts[1].toLongOrNull() ?: return@mapNotNull null
            parts[0].removePrefix("databases/") to size
        }.toMap()
        return files.keys.filter { name -> COMPANIONS.none { name.endsWith(it) } }.map { name ->
            DeviceDatabase(name, files.getValue(name), files["$name-wal"], "$name-shm" in files)
        }
    }

    /** Copies [db] and its companions into [into]; returns the copied database file. */
    open suspend fun pull(access: AppDataAccess, db: DeviceDatabase, into: Path): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(into)
        val names = listOfNotNull(db.name, if (db.walSize != null) "${db.name}-wal" else null, if (db.hasShm) "${db.name}-shm" else null)
        for (suffix in listOf("-wal", "-shm")) Files.deleteIfExists(into.resolve(db.name + suffix))
        for (name in names) {
            adb.openExecStream(serial, access.wrap("cat " + shellQuote("databases/$name"))).use { input ->
                Files.copy(input, into.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        into.resolve(db.name)
    }

    /** Size and modified time of [db] and its WAL; changes whenever the app writes to it. */
    open suspend fun fingerprint(access: AppDataAccess, db: String): String {
        val files = listOf(db, "$db-wal").joinToString(" ") { shellQuote("databases/$it") }
        return runner.run(access.wrap("stat -c '%s:%Y' $files 2>/dev/null")).output.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("|")
    }

    open suspend fun isRunning(pkg: String): Boolean = runner.run("pidof $pkg").output.isNotBlank()

    open suspend fun forceStop(pkg: String) {
        runner.run("am force-stop $pkg")
    }

    /** Replaces [db] on the device with [local] and drops the device's stale WAL. Throws [IOException] on failure. */
    open suspend fun write(access: AppDataAccess, db: String, local: Path) {
        val remote = "/data/local/tmp/${tempName()}"
        val push = adb.exec("-s", serial, "push", local.toString(), remote)
        if (push.exitCode != 0) throw IOException(push.stderr.ifBlank { push.stdout }.trim().ifEmpty { "adb push failed" })
        try {
            checked(access.wrap("cat ${shellQuote(remote)} > ${shellQuote("databases/$db")}"))
            checked(access.wrap("rm -f ${shellQuote("databases/$db-wal")} ${shellQuote("databases/$db-shm")}"))
        } finally {
            runner.run("rm -f ${shellQuote(remote)}")
        }
    }

    private suspend fun checked(cmd: String) {
        val result = runner.run(cmd)
        if (result.exitCode != 0) throw IOException(result.output.trim().ifEmpty { "Command failed: $cmd" })
    }

    private companion object {
        val COMPANIONS = listOf("-wal", "-shm", "-journal")
    }
}
