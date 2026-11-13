package com.programmersbox.scrcpyviewer.ui.mirror.files

import com.programmersbox.scrcpyviewer.ui.common.OpenPlatform
import com.programmersbox.scrcpyviewer.ui.common.openExternally
import com.programmersbox.scrcpyviewer.ui.common.startDetached
import java.nio.file.Path
import com.programmersbox.scrcpyviewer.ui.common.openCommand as openCommandFor

/** The argv that opens [path] with the computer's default app; see the shared `openCommand`. */
internal fun openCommand(path: Path, platform: OpenPlatform): List<String> = openCommandFor(path.toString(), platform)

/** Opens [path] with the computer's default app; false, logged, when the opener cannot start; never throws. */
fun openOnComputer(path: Path, run: (List<String>) -> Process? = ::startDetached): Boolean =
    openExternally(path.toString(), run)

internal fun openOnComputer(path: Path, platform: OpenPlatform, run: (List<String>) -> Process? = ::startDetached): Boolean =
    openExternally(path.toString(), platform, run = run)
