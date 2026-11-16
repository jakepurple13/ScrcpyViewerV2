package com.programmersbox.scrcpyviewer.ui.common

import com.programmersbox.scrcpyviewer.ui.window.ObjC
import com.sun.jna.Memory
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

private val log = System.getLogger("scrcpyviewer.ImageClipboard")

internal const val NO_IMAGE_CLIPBOARD_TOOL = "Install wl-clipboard or xclip to copy images"

/** Runs a clipboard tool with [stdin] piped in (if any). The exit code, or null when it timed out. */
internal typealias ToolRun = (command: List<String>, stdin: Path?) -> Int?

/** Copies a PNG file to the system clipboard as an image. */
object ImageClipboard {
    /** Copies [file] (a PNG) as an image. A failure carries a message fit for the snackbar; never throws. */
    fun copyPng(file: Path): Result<Unit> = copyPng(file, OpenPlatform.current())

    internal fun copyPng(
        file: Path,
        platform: OpenPlatform,
        env: (String) -> String? = System::getenv,
        which: (String) -> Boolean = ::onPath,
        run: ToolRun = ::runTool,
    ): Result<Unit> = runCatching {
        require(Files.isRegularFile(file)) { "The screenshot file is missing" }
        when (platform) {
            OpenPlatform.Mac -> copyMac(file)
            OpenPlatform.Windows -> copyWindows(file)
            OpenPlatform.Linux -> copyLinux(file, env, which, run)
        }
    }.onFailure { log.log(System.Logger.Level.WARNING, "Could not copy $file", it) }

    internal class LinuxCopy(val command: List<String>, val stdin: Path?)

    /** The Linux tool and argv for [file], or null when neither is installed. Wayland sessions prefer wl-copy. */
    internal fun linuxCommand(file: Path, env: (String) -> String?, which: (String) -> Boolean): LinuxCopy? {
        val wayland = !env("WAYLAND_DISPLAY").isNullOrEmpty()
        val wlCopy = LinuxCopy(listOf("wl-copy", "--type", "image/png"), stdin = file)
        val xclip = LinuxCopy(listOf("xclip", "-selection", "clipboard", "-t", "image/png", "-i", file.toString()), stdin = null)
        return when {
            wayland && which("wl-copy") -> wlCopy
            which("xclip") -> xclip
            which("wl-copy") -> wlCopy
            else -> null
        }
    }

    private fun copyLinux(file: Path, env: (String) -> String?, which: (String) -> Boolean, run: ToolRun) {
        val copy = linuxCommand(file, env, which) ?: error(NO_IMAGE_CLIPBOARD_TOOL)
        val tool = copy.command.first()
        val code = run(copy.command, copy.stdin) ?: error("$tool timed out")
        check(code == 0) { "$tool exited with $code" }
    }

    /** NSPasteboard with the PNG bytes as `public.png`, on its own autorelease pool (this runs off the main thread). */
    private fun copyMac(file: Path) {
        val bytes = Files.readAllBytes(file)
        val pool = ObjC.msgPtr(ObjC.msgPtr(ObjC.cls("NSAutoreleasePool"), "alloc")!!, "init")!!
        try {
            val board = ObjC.msgPtr(ObjC.cls("NSPasteboard"), "generalPasteboard")!!
            val memory = Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
            // dataWithBytes:length: copies the bytes, so the JNA memory may be freed afterwards.
            val data = ObjC.msgPtr(ObjC.cls("NSData"), "dataWithBytes:length:", memory, bytes.size.toLong())
                ?: error("Could not wrap the image")
            ObjC.msgLong(board, "clearContents")
            check(ObjC.msgBool(board, "setData:forType:", data, ObjC.nsString("public.png"))) {
                "The pasteboard refused the image"
            }
        } finally {
            ObjC.msgVoid(pool, "drain")
        }
    }

    /** Windows still copies through AWT's clipboard. */
    private fun copyWindows(file: Path) {
        val image = ImageIO.read(file.toFile()) ?: error("Could not read the screenshot")
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
    }
}

private class ImageTransferable(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (isDataFlavorSupported(flavor)) image else throw UnsupportedFlavorException(flavor)
}

/** Runs [command] with [stdin] as its input, killing it after 5 s. Exit code, or null on timeout. */
private fun runTool(command: List<String>, stdin: Path?): Int? {
    val builder = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
    if (stdin != null) builder.redirectInput(stdin.toFile())
    val process = builder.start()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    return process.exitValue()
}
