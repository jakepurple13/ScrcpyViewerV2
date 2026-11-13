package com.programmersbox.scrcpyviewer.ui.shortcuts

import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.mirror.Zoom
import java.awt.event.KeyEvent as AwtKeyEvent

/** Why a recorded chord is refused. */
enum class ShortcutProblem {
    /** No Cmd/Option/Ctrl (Ctrl/Alt off macOS): typing would stop reaching the device. */
    NeedsModifier,

    /** The system or the app already owns it. */
    Reserved,

    /** Ctrl+Alt arrived as AltGr (Windows/Linux): it types characters on international layouts. */
    AltGr,

    /** Meta is the Windows/Super key off macOS, which the OS claims. */
    WinKey,
}

/** A key chord. [keyCode] is an AWT virtual key code (what Compose's `nativeKeyCode` gives under Tao). */
data class Shortcut(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    /** Stored form: modifier letters (C, A, S, M) then `:` and the key code, e.g. `AM:71`. */
    fun encode(): String = buildString {
        if (ctrl) append('C')
        if (alt) append('A')
        if (shift) append('S')
        if (meta) append('M')
        append(':').append(keyCode)
    }

    /** macOS: symbols in the order ⌃⌥⇧⌘ + key ("⌥⌘G"). Elsewhere "Ctrl+Alt+Shift+G". */
    fun label(mac: Boolean = Os.isMac): String {
        val key = keyName(keyCode, mac)
        return if (mac) {
            buildString {
                if (ctrl) append('⌃')
                if (alt) append('⌥')
                if (shift) append('⇧')
                if (meta) append('⌘')
                append(key)
            }
        } else {
            buildList {
                if (ctrl) add("Ctrl")
                if (alt) add("Alt")
                if (shift) add("Shift")
                if (meta) add("Win")
                add(key)
            }.joinToString("+")
        }
    }

    /** The AWT extended modifier mask of this chord's modifiers. */
    val modifiersEx: Int
        get() = (if (ctrl) AwtKeyEvent.CTRL_DOWN_MASK else 0) or
            (if (alt) AwtKeyEvent.ALT_DOWN_MASK else 0) or
            (if (shift) AwtKeyEvent.SHIFT_DOWN_MASK else 0) or
            (if (meta) AwtKeyEvent.META_DOWN_MASK else 0)

    companion object {
        /** Null for anything [encode] would not have produced. */
        fun decode(text: String): Shortcut? {
            val parts = text.split(':')
            if (parts.size != 2) return null
            val mods = parts[0]
            if (mods != "CASM".filter { it in mods }) return null
            if (mods.length != mods.toSet().size) return null
            val code = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return Shortcut(code, 'C' in mods, 'A' in mods, 'S' in mods, 'M' in mods)
        }
    }
}

private fun keyName(code: Int, mac: Boolean): String = when (code) {
    in AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z, in AwtKeyEvent.VK_0..AwtKeyEvent.VK_9 -> code.toChar().toString()
    AwtKeyEvent.VK_UP -> if (mac) "↑" else "Up"
    AwtKeyEvent.VK_DOWN -> if (mac) "↓" else "Down"
    AwtKeyEvent.VK_LEFT -> if (mac) "←" else "Left"
    AwtKeyEvent.VK_RIGHT -> if (mac) "→" else "Right"
    AwtKeyEvent.VK_SPACE -> "Space"
    AwtKeyEvent.VK_ENTER -> if (mac) "↩" else "Enter"
    AwtKeyEvent.VK_ESCAPE -> if (mac) "⎋" else "Esc"
    AwtKeyEvent.VK_BACK_SPACE -> if (mac) "⌫" else "Backspace"
    AwtKeyEvent.VK_DELETE -> if (mac) "⌦" else "Delete"
    AwtKeyEvent.VK_TAB -> if (mac) "⇥" else "Tab"
    AwtKeyEvent.VK_COMMA -> ","
    AwtKeyEvent.VK_PERIOD -> "."
    AwtKeyEvent.VK_SLASH -> "/"
    AwtKeyEvent.VK_SEMICOLON -> ";"
    AwtKeyEvent.VK_QUOTE -> "'"
    AwtKeyEvent.VK_OPEN_BRACKET -> "["
    AwtKeyEvent.VK_CLOSE_BRACKET -> "]"
    AwtKeyEvent.VK_MINUS -> "-"
    AwtKeyEvent.VK_EQUALS -> "="
    AwtKeyEvent.VK_BACK_QUOTE -> "`"
    AwtKeyEvent.VK_BACK_SLASH -> "\\"
    in AwtKeyEvent.VK_NUMPAD0..AwtKeyEvent.VK_NUMPAD9 -> "Num ${code - AwtKeyEvent.VK_NUMPAD0}"
    AwtKeyEvent.VK_ADD -> "Num +"
    AwtKeyEvent.VK_SUBTRACT -> "Num -"
    AwtKeyEvent.VK_MULTIPLY -> "Num *"
    AwtKeyEvent.VK_DIVIDE -> "Num /"
    AwtKeyEvent.VK_DECIMAL -> "Num ."
    in AwtKeyEvent.VK_F1..AwtKeyEvent.VK_F12 -> "F${code - AwtKeyEvent.VK_F1 + 1}"
    in AwtKeyEvent.VK_F13..AwtKeyEvent.VK_F24 -> "F${code - AwtKeyEvent.VK_F13 + 13}"
    else -> AwtKeyEvent.getKeyText(code)
}

private val modifierKeys = setOf(
    AwtKeyEvent.VK_SHIFT, AwtKeyEvent.VK_CONTROL, AwtKeyEvent.VK_ALT, AwtKeyEvent.VK_META,
    AwtKeyEvent.VK_ALT_GRAPH, AwtKeyEvent.VK_WINDOWS,
)

private val macReserved = setOf(
    AwtKeyEvent.VK_Q, AwtKeyEvent.VK_W, AwtKeyEvent.VK_H, AwtKeyEvent.VK_M, AwtKeyEvent.VK_COMMA,
    AwtKeyEvent.VK_TAB, AwtKeyEvent.VK_V,
).map { Shortcut(it, meta = true) }.toSet() + Shortcut(AwtKeyEvent.VK_Q, shift = true, meta = true)

private val otherReserved = setOf(
    Shortcut(AwtKeyEvent.VK_F4, alt = true),
    Shortcut(AwtKeyEvent.VK_V, ctrl = true),
    Shortcut(AwtKeyEvent.VK_DELETE, ctrl = true, alt = true),
)

/**
 * Why [s] cannot be a shortcut, or null. Needs Cmd, Option or Ctrl on macOS (Ctrl or Alt elsewhere); refuses system
 * chords, the zoom keys, AltGr ([altGr]: `modifiersEx and ALT_GRAPH_DOWN_MASK != 0`), the Windows key off macOS, and any of [extraReserved] (callers add anything dynamic, e.g. the mouse-capture release key).
 */
fun validate(
    s: Shortcut,
    mac: Boolean = Os.isMac,
    extraReserved: Set<Shortcut> = emptySet(),
    altGr: Boolean = false,
): ShortcutProblem? {
    if (s.keyCode in modifierKeys) return ShortcutProblem.NeedsModifier
    if (!mac && s.meta) return ShortcutProblem.WinKey
    // AWT reports AltGr as Ctrl+Alt plus ALT_GRAPH_DOWN_MASK: pass `altGr` from the event's modifiersEx.
    if (!mac && (altGr)) return ShortcutProblem.AltGr
    val hasMod = if (mac) s.ctrl || s.alt || s.meta else s.ctrl || s.alt
    if (!hasMod) return ShortcutProblem.NeedsModifier
    if (s in (if (mac) macReserved else otherReserved)) return ShortcutProblem.Reserved
    if (s in extraReserved) return ShortcutProblem.Reserved
    if (Zoom.shortcut(s.keyCode, s.modifiersEx, mac) != null) return ShortcutProblem.Reserved
    return null
}

/** The other action that already owns [s] in [map] (action id to encoded shortcut), or null. */
fun conflict(map: Map<String, String>, action: ShortcutAction, s: Shortcut): ShortcutAction? {
    val enc = s.encode()
    return ShortcutAction.entries.firstOrNull { it != action && map[it.id] == enc }
}
