package com.programmersbox.scrcpyviewer.logcat

/** One autocomplete row; accepting it replaces `[replaceStart, replaceEnd)` of the text with [insert]. */
data class Suggestion(
    val label: String,
    val description: String,
    val replaceStart: Int,
    val replaceEnd: Int,
    val insert: String,
)

/** What the filter can complete from: tag line counts, known packages and processes, the picked app. */
data class SuggestionContext(
    val tags: Map<String, Int>,
    val packages: Set<String>,
    val processes: Set<String>,
    val mine: String?,
)

private val valueToken = Regex("""^(-?)([a-z]+)([~=]?):(.*)$""")

/** The rest of a key after the caret, up to its `:` (`g~:` of `ta|g~:x`). */
private val keyRest = Regex("""^[a-z]*([~=]?):""")
private const val MAX_SUGGESTIONS = 7

/**
 * Up to 7 suggestions for the token at [caret], matched on its part before the caret. Without a `:` it lists
 * matching keys (inserting `key:`, keeping a leading `-`); after `key:` it lists matching values (inserting the
 * value plus a space, quoted when it has whitespace). Matching is a case-insensitive prefix; a value already fully
 * typed is skipped.
 *
 * With the caret inside the token, accepting replaces the rest of it too: a key up to its `:` (`ta|g:x` gives
 * `tag:x`, keeping the value and a `~` or `=`), a value up to the next whitespace, which the value's own space
 * then takes the place of.
 */
fun suggest(text: String, caret: Int, ctx: SuggestionContext): List<Suggestion> {
    var start = caret
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    var tokenEnd = caret
    while (tokenEnd < text.length && !text[tokenEnd].isWhitespace()) tokenEnd++
    val tok = text.substring(start, caret)
    val m = valueToken.matchEntire(tok)
    if (m == null) {
        if (':' in tok) return emptyList()
        val neg = if (tok.startsWith("-")) "-" else ""
        val word = tok.removePrefix("-")
        val rest = keyRest.find(text.substring(caret, tokenEnd))
        val end = rest?.let { caret + it.value.length } ?: tokenEnd
        val op = rest?.groupValues?.get(1).orEmpty()
        return LogcatQuery.KEYS.filter { it.key.startsWith(word, ignoreCase = true) }.map { (k, d) ->
            Suggestion(neg + k + ":", d, start, end, neg + k + op + ":")
        }.take(MAX_SUGGESTIONS)
    }
    val key = m.groupValues[2]
    val typed = m.groupValues[4]
    val pre = tok.dropLast(typed.length)
    val vals: List<Pair<String, String>> = when (key) {
        "level" -> LogLevel.entries.map { it.word to "" }
        "package" -> listOf("mine" to (ctx.mine?.let { "picked in Apps: $it" } ?: "Pick an app in Apps")) +
            ctx.packages.map { it to "" }
        "process" -> ctx.processes.map { it to "" }
        "is" -> listOf("crash" to "app crashes", "stacktrace" to "lines with a stack trace")
        "age" -> listOf("30s", "5m", "1h", "1d").map { it to "" }
        "tag" -> ctx.tags.entries.sortedByDescending { it.value }.map { it.key to "${it.value} lines" }
        else -> return emptyList()
    }
    val needle = typed.removePrefix("\"")
    // The inserted value ends in a space: it stands in for one already after the token.
    val end = if (tokenEnd < text.length) tokenEnd + 1 else tokenEnd
    return vals.filter { (v, _) -> v.startsWith(needle, ignoreCase = true) && v != typed }
        .take(MAX_SUGGESTIONS)
        .map { (v, d) ->
            val shown = if (v.any { it.isWhitespace() }) "\"$v\"" else v
            Suggestion(pre + v, d, start, end, "$pre$shown ")
        }
}

/** The text with the suggestion applied, and the caret placed right after the inserted text. */
fun Suggestion.applyTo(text: String): Pair<String, Int> =
    text.substring(0, replaceStart) + insert + text.substring(replaceEnd) to replaceStart + insert.length
