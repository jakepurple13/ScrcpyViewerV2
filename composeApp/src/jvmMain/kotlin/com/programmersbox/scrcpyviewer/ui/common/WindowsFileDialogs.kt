package com.programmersbox.scrcpyviewer.ui.common

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import java.nio.file.Path

/**
 * `"APK files (*.apk)\0*.apk\0\0"`: the comdlg32 filter for [extensions] (all files when empty), as display name and
 * pattern pairs ending in an empty pair.
 */
fun windowsFilter(extensions: List<String>): String {
    val exts = extensionsFilter(extensions)
    if (exts.isEmpty()) return "All files\u0000*.*\u0000\u0000"
    val patterns = exts.joinToString(";") { "*.$it" }
    val name = if (exts.size == 1) "${exts[0].uppercase()} files" else "Files"
    return "$name ($patterns)\u0000$patterns\u0000\u0000"
}

/**
 * The paths in a multi-select `lpstrFile` buffer, split at its NULs: one full path, or the folder followed by the
 * file names in it.
 */
fun parseWindowsSelection(parts: List<String>): List<Path> = when (parts.size) {
    0 -> emptyList()
    1 -> listOf(Path.of(parts[0]))
    else -> parts.drop(1).map { Path.of(parts[0], it) }
}

/**
 * comdlg32 `GetOpenFileNameW` / `GetSaveFileNameW` and shell32 `SHBrowseForFolderW` over JNA, each on its own thread
 * with OLE initialized. The dialogs have no owner window.
 */
internal object WindowsFileDialogs : NativeFileDialogs {
    private const val OFN_OVERWRITEPROMPT = 0x2
    private const val OFN_NOCHANGEDIR = 0x8
    private const val OFN_ALLOWMULTISELECT = 0x200
    private const val OFN_PATHMUSTEXIST = 0x800
    private const val OFN_FILEMUSTEXIST = 0x1000
    private const val OFN_EXPLORER = 0x80000
    private const val BIF_RETURNONLYFSDIRS = 0x1
    private const val BIF_NEWDIALOGSTYLE = 0x40
    private const val FILE_BUFFER_CHARS = 32 * 1024
    private const val MAX_PATH = 260

    @Suppress("unused")
    @Structure.FieldOrder(
        "lStructSize", "hwndOwner", "hInstance", "lpstrFilter", "lpstrCustomFilter", "nMaxCustFilter", "nFilterIndex",
        "lpstrFile", "nMaxFile", "lpstrFileTitle", "nMaxFileTitle", "lpstrInitialDir", "lpstrTitle", "Flags",
        "nFileOffset", "nFileExtension", "lpstrDefExt", "lCustData", "lpfnHook", "lpTemplateName", "pvReserved",
        "dwReserved", "FlagsEx",
    )
    class OpenFileName : Structure() {
        @JvmField var lStructSize = 0
        @JvmField var hwndOwner: Pointer? = null
        @JvmField var hInstance: Pointer? = null
        @JvmField var lpstrFilter: Pointer? = null
        @JvmField var lpstrCustomFilter: Pointer? = null
        @JvmField var nMaxCustFilter = 0
        @JvmField var nFilterIndex = 0
        @JvmField var lpstrFile: Pointer? = null
        @JvmField var nMaxFile = 0
        @JvmField var lpstrFileTitle: Pointer? = null
        @JvmField var nMaxFileTitle = 0
        @JvmField var lpstrInitialDir: WString? = null
        @JvmField var lpstrTitle: WString? = null
        @JvmField var Flags = 0
        @JvmField var nFileOffset: Short = 0
        @JvmField var nFileExtension: Short = 0
        @JvmField var lpstrDefExt: WString? = null
        @JvmField var lCustData: Pointer? = null
        @JvmField var lpfnHook: Pointer? = null
        @JvmField var lpTemplateName: Pointer? = null
        @JvmField var pvReserved: Pointer? = null
        @JvmField var dwReserved = 0
        @JvmField var FlagsEx = 0
    }

    @Suppress("unused")
    @Structure.FieldOrder("hwndOwner", "pidlRoot", "pszDisplayName", "lpszTitle", "ulFlags", "lpfn", "lParam", "iImage")
    class BrowseInfo : Structure() {
        @JvmField var hwndOwner: Pointer? = null
        @JvmField var pidlRoot: Pointer? = null
        @JvmField var pszDisplayName: Pointer? = null
        @JvmField var lpszTitle: WString? = null
        @JvmField var ulFlags = 0
        @JvmField var lpfn: Pointer? = null
        @JvmField var lParam: Pointer? = null
        @JvmField var iImage = 0
    }

    override fun open(title: String, extensions: List<String>, multiple: Boolean, directories: Boolean, initial: Path?): List<Path> =
        onDialogThread {
            if (directories) return@onDialogThread listOfNotNull(browseForFolder(title))
            val file = Memory(FILE_BUFFER_CHARS * 2L).apply { clear() }
            val ofn = fileName(title, windowsFilter(extensions), file, initial)
            ofn.Flags = OFN_EXPLORER or OFN_FILEMUSTEXIST or OFN_PATHMUSTEXIST or OFN_NOCHANGEDIR or
                (if (multiple) OFN_ALLOWMULTISELECT else 0)
            if (comdlg("GetOpenFileNameW", ofn) == 0) return@onDialogThread emptyList()
            parseWindowsSelection(nulSeparated(file))
        }

    override fun save(title: String, suggestedName: String, extension: String): Path? = onDialogThread {
        val file = Memory(FILE_BUFFER_CHARS * 2L).apply { clear() }
        file.setWideString(0, suggestedFileName(suggestedName, extension))
        val ofn = fileName(title, windowsFilter(listOf(extension)), file, null)
        ofn.lpstrDefExt = extensionsFilter(listOf(extension)).firstOrNull()?.let(::WString)
        ofn.Flags = OFN_EXPLORER or OFN_OVERWRITEPROMPT or OFN_PATHMUSTEXIST or OFN_NOCHANGEDIR
        if (comdlg("GetSaveFileNameW", ofn) == 0) null else Path.of(file.getWideString(0))
    }

    private fun fileName(title: String, filter: String, file: Memory, initial: Path?) = OpenFileName().apply {
        lStructSize = size()
        lpstrFilter = Memory((filter.length + 1) * 2L).apply { setWideString(0, filter) }
        nFilterIndex = 1
        lpstrFile = file
        nMaxFile = FILE_BUFFER_CHARS
        lpstrTitle = WString(title)
        lpstrInitialDir = initial?.let { WString(it.toString()) }
    }

    private fun comdlg(function: String, ofn: OpenFileName): Int =
        NativeLibrary.getInstance("comdlg32").getFunction(function).invokeInt(arrayOf(ofn))

    private fun browseForFolder(title: String): Path? {
        val info = BrowseInfo().apply {
            pszDisplayName = Memory(MAX_PATH * 2L).apply { clear() }
            lpszTitle = WString(title)
            ulFlags = BIF_RETURNONLYFSDIRS or BIF_NEWDIALOGSTYLE
        }
        val shell32 = NativeLibrary.getInstance("shell32")
        val pidl = shell32.getFunction("SHBrowseForFolderW").invokePointer(arrayOf(info)) ?: return null
        try {
            val path = Memory(MAX_PATH * 2L).apply { clear() }
            val ok = shell32.getFunction("SHGetPathFromIDListW").invokeInt(arrayOf(pidl, path)) != 0
            return if (ok) Path.of(path.getWideString(0)) else null
        } finally {
            NativeLibrary.getInstance("ole32").getFunction("CoTaskMemFree").invokeVoid(arrayOf(pidl))
        }
    }

    /** The NUL-separated strings at the start of [buffer], up to the empty one. */
    private fun nulSeparated(buffer: Memory): List<String> {
        val parts = mutableListOf<String>()
        var offset = 0L
        while (offset < buffer.size()) {
            val part = buffer.getWideString(offset)
            if (part.isEmpty()) break
            parts += part
            offset += (part.length + 1) * 2L
        }
        return parts
    }

    /** Runs [block] on a fresh thread with OLE initialized (the folder dialog needs it) and waits for it. */
    private fun <T> onDialogThread(block: () -> T): T = blockingModal("file-picker") {
        var result: Result<T>? = null
        val thread = Thread({
            val ole32 = NativeLibrary.getInstance("ole32")
            ole32.getFunction("OleInitialize").invokeInt(arrayOf<Any?>(null))
            try {
                result = runCatching(block)
            } finally {
                ole32.getFunction("OleUninitialize").invokeVoid(emptyArray())
            }
        }, "file-picker-win32")
        thread.start()
        thread.join()
        result!!.getOrThrow()
    }
}
