package com.programmersbox.scrcpyviewer.ui.mirror.files

/** Where a device's Files tab was last: the open folder's [path] and the folders visited before it ([history], oldest first). */
data class FilesLocation(val path: String, val history: List<String>)
