package com.programmersbox.scrcpyviewer.telemetry

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The anonymous install ID: a random UUID v4 stored in `dir/install-id`. Created on the first [get], so a build that
 * never asks for it never writes a file. [reset] replaces it with a new random one.
 */
class InstallId(private val dir: Path) {
    private val file: Path get() = dir.resolve("install-id")
    private var cached: String? = null

    @Synchronized
    fun get(): String {
        cached?.let { return it }
        val stored = runCatching { if (Files.isRegularFile(file)) file.readText().trim() else "" }.getOrDefault("")
        val valid = runCatching { UUID.fromString(stored) }.isSuccess
        return (if (valid) stored else create()).also { cached = it }
    }

    @Synchronized
    fun reset(): String = create().also { cached = it }

    private fun create(): String {
        val id = UUID.randomUUID().toString()
        runCatching {
            dir.createDirectories()
            file.writeText(id)
        }
        return id
    }
}
