package com.programmersbox.scrcpyviewer.ui.mirror

/** The serials with an open mirror window right now. Lets screens ask without depending on [MirrorController]. */
class OpenMirrors(private val serials: () -> Set<String>) {
    fun serials(): Set<String> = serials.invoke()
}
