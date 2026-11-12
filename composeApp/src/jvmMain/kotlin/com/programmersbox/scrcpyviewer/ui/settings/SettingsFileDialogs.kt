package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.ui.common.NativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.nativeFileDialogs
import com.programmersbox.scrcpyviewer.ui.common.onPickerThread
import java.nio.file.Path

/** Native pickers for Settings, behind an interface so tests never open a window. */
interface SettingsFileDialogs {
    /** Asks for an adb executable; null when cancelled. */
    suspend fun chooseAdbFile(): Path?

    /** Asks for a folder; null when cancelled. */
    suspend fun chooseFolder(initial: Path?): Path?

    /** Asks for a folder, with [title] on the dialog; null when cancelled. */
    suspend fun chooseFolder(title: String): Path?
}

/** The platform's own open dialog, for files and folders. */
class NativeSettingsFileDialogs(private val dialogs: NativeFileDialogs = nativeFileDialogs()) : SettingsFileDialogs {
    override suspend fun chooseAdbFile(): Path? =
        onPickerThread { dialogs.open("Choose adb executable", emptyList(), multiple = false).firstOrNull() }

    override suspend fun chooseFolder(initial: Path?): Path? = pickFolder("Choose folder", initial)

    override suspend fun chooseFolder(title: String): Path? = pickFolder(title, null)

    private suspend fun pickFolder(title: String, initial: Path?): Path? =
        onPickerThread { dialogs.open(title, emptyList(), multiple = false, directories = true, initial = initial).firstOrNull() }
}
