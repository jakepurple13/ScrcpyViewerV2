package com.programmersbox.scrcpyviewer.adb

import java.io.IOException
import java.io.InputStream

class AdbException(message: String) : IOException(message)

/** adb host protocol framing: requests are a 4-digit hex length followed by the payload. */
object AdbWire {
    /** The largest request body the 4-hex-digit length can describe. */
    const val MAX_REQUEST_BYTES = 0xFFFF

    /** Throws [IllegalArgumentException] for a body over 0xFFFF bytes, which the 4-digit length cannot frame. */
    fun encodeRequest(cmd: String): ByteArray {
        val body = cmd.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_REQUEST_BYTES) {
            "adb request is ${body.size} bytes; the adb protocol allows at most $MAX_REQUEST_BYTES"
        }
        return "%04x".format(body.size).toByteArray(Charsets.US_ASCII) + body
    }

    /** Reads `OKAY`, or `FAIL` + hex length + message and throws [AdbException]. */
    fun readStatus(input: InputStream) {
        val status = readExactly(input, 4) ?: throw AdbException("adb server closed the connection")
        when (String(status, Charsets.US_ASCII)) {
            "OKAY" -> Unit
            "FAIL" -> throw AdbException("FAIL: " + (readHexLengthPayload(input) ?: ""))
            else -> throw AdbException("Unexpected adb status: ${String(status, Charsets.US_ASCII)}")
        }
    }

    /** Reads a 4-hex-digit length then that many bytes. Returns null when the stream is at EOF. */
    fun readHexLengthPayload(input: InputStream): String? {
        val header = readExactly(input, 4) ?: return null
        val length = String(header, Charsets.US_ASCII).toIntOrNull(16)
            ?: throw AdbException("Bad adb length header: ${String(header, Charsets.US_ASCII)}")
        if (length == 0) return ""
        val body = readExactly(input, length) ?: throw AdbException("adb stream ended inside a payload")
        return String(body, Charsets.UTF_8)
    }

    /** Returns null if EOF is hit before the first byte; throws if EOF hits mid-way. */
    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) {
                if (read == 0) return null
                throw AdbException("adb stream ended mid-read")
            }
            read += r
        }
        return buf
    }
}
