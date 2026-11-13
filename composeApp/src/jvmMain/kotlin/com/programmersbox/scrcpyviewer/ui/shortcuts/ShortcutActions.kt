package com.programmersbox.scrcpyviewer.ui.shortcuts

/** The settings page's row groups. */
enum class ShortcutGroup(val label: String) {
    MultiDevice("Multi-device input"),
    Arrange("Arrange mirror windows"),
}

/** Everything that can have a shortcut. [id] is the key in `Settings.shortcuts`. */
enum class ShortcutAction(val id: String, val label: String, val group: ShortcutGroup) {
    Follow("follow", "Send input to other windows", ShortcutGroup.MultiDevice),
    ArrangeFree("arrange-free", "As placed", ShortcutGroup.Arrange),
    ArrangeGrid("arrange-grid", "Grid", ShortcutGroup.Arrange),
    ArrangeRow("arrange-row", "Side by side", ShortcutGroup.Arrange),
    ArrangeFocus("arrange-focus", "Focus the front window", ShortcutGroup.Arrange),
    ArrangeCascade("arrange-cascade", "Cascade", ShortcutGroup.Arrange),
    ;

    companion object {
        fun fromId(id: String): ShortcutAction? = entries.firstOrNull { it.id == id }
    }
}
