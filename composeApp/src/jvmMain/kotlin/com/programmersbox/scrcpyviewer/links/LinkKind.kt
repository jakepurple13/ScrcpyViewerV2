package com.programmersbox.scrcpyviewer.links

enum class LinkKind { Web, Scheme, Intent, NotALink }

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.+)$", RegexOption.DOT_MATCHES_ALL)

/** The kind of [text] and the chip label shown for it. */
fun linkKind(text: String): Pair<LinkKind, String> {
    val match = SCHEME.matchEntire(text.trim()) ?: return LinkKind.NotALink to "Not a link"
    return when (val scheme = match.groupValues[1].lowercase()) {
        "http", "https" -> LinkKind.Web to "Web link"
        "intent" -> LinkKind.Intent to "Intent URI"
        else -> LinkKind.Scheme to "$scheme: link"
    }
}
