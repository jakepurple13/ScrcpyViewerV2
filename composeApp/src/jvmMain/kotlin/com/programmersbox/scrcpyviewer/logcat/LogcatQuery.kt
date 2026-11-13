package com.programmersbox.scrcpyviewer.logcat

/** What the caller resolved for an entry's pid: its package and process name, null when unknown. */
data class EntryInfo(val pkg: String?, val process: String?)

/** How a [QuerySpan] is drawn in the filter field. */
enum class SpanKind { Key, Negation, Operator, Quoted, Error }

/** A highlighted range `[start, end)` of the query text. */
data class QuerySpan(val start: Int, val end: Int, val kind: SpanKind)

private val token = Regex(
    """(-?)([a-z]+)([~=]?):("(?:[^"\\]|\\.)*"?|\S*)|(\|)|(-?)("(?:[^"\\]|\\.)*"?|\S+)""",
)
private val ageRe = Regex("""^(\d+)([smhd])$""")
private val ageUnits = mapOf('s' to 1_000L, 'm' to 60_000L, 'h' to 3_600_000L, 'd' to 86_400_000L)

private fun unquote(v: String): String =
    if (v.startsWith('"')) v.removePrefix("\"").let { if (v.length > 1 && v.endsWith('"')) it.dropLast(1) else it } else v

private class Term(
    val neg: Boolean,
    val key: String,
    val op: String,
    val value: String,
    val level: LogLevel? = null,
    val ageMillis: Long? = null,
    val regex: Regex? = null,
)

/**
 * A parsed Android Studio style filter. Terms in a group are ANDed, groups split by `|` are ORed. A term that
 * is invalid or half typed is ignored; the first invalid one sets [error].
 */
class LogcatQuery private constructor(
    private val groups: List<List<Term>>,
    val spans: List<QuerySpan>,
    val error: String?,
) {
    val isEmpty: Boolean get() = groups.isEmpty()

    /** Positive bare and `message:` words worth highlighting in the message text. */
    val highlightWords: List<String> = groups.flatten()
        .filter { !it.neg && it.value.length > 1 && (it.key == "text" || (it.key == "message" && it.op == "")) }
        .map { it.value }

    /** True when [e] passes the query. [mine] is the picked app's package, [nowMillis] the clock for `age:`. */
    fun matches(e: LogcatEntry, info: EntryInfo, mine: String?, nowMillis: Long): Boolean =
        groups.isEmpty() || groups.any { g -> g.all { termOk(it, e, info, mine, nowMillis) } }

    private fun termOk(t: Term, e: LogcatEntry, info: EntryInfo, mine: String?, nowMillis: Long): Boolean {
        val ok = when {
            t.key == "text" -> {
                val v = t.value.lowercase()
                e.line.tag.lowercase().contains(v) || e.line.message.lowercase().contains(v)
            }
            t.key == "level" -> e.line.level >= t.level!!
            t.key == "age" -> nowMillis - e.receivedAtMillis <= t.ageMillis!!
            t.key == "is" -> if ("crash".startsWith(t.value)) e.isCrash else e.hasTrace
            t.key == "package" && t.value == "mine" && t.op == "" -> mine != null && info.pkg == mine
            else -> {
                val f = when (t.key) {
                    "tag" -> e.line.tag
                    "package" -> info.pkg.orEmpty()
                    "process" -> info.process.orEmpty()
                    else -> (listOf(e.line.message) + e.trace).joinToString("\n")
                }
                when (t.op) {
                    "~" -> t.regex!!.containsMatchIn(f)
                    "=" -> f == t.value
                    else -> f.contains(t.value, ignoreCase = true)
                }
            }
        }
        return if (t.neg) !ok else ok
    }

    companion object {
        /** Filter keys in autocomplete order, with their descriptions. */
        val KEYS: Map<String, String> = linkedMapOf(
            "tag" to "Log tag",
            "package" to "App package; mine = the app picked in Apps",
            "process" to "Process name",
            "message" to "Text in the message",
            "level" to "This level and above",
            "age" to "Newer than 30s, 5m, 1h, 1d",
            "is" to "crash or stacktrace",
        )

        fun parse(text: String): LogcatQuery {
            val groups = mutableListOf(mutableListOf<Term>())
            val spans = mutableListOf<QuerySpan>()
            var error: String? = null
            for (m in token.findAll(text)) {
                val at = m.range.first
                val g = m.groups
                if (g[5] != null) {
                    groups += mutableListOf<Term>()
                    spans += QuerySpan(at, at + 1, SpanKind.Operator)
                    continue
                }
                if (g[7] != null) {
                    val raw = g[7]!!.value
                    val neg = g[6]!!.value.isNotEmpty()
                    val value = if (raw == "-") "" else unquote(raw)
                    val start = if (neg) at + 1 else at
                    if (neg) spans += QuerySpan(at, at + 1, SpanKind.Negation)
                    if (raw.startsWith('"')) spans += QuerySpan(start, m.range.last + 1, SpanKind.Quoted)
                    if (value.isNotEmpty()) groups.last() += Term(neg, "text", "", value)
                    continue
                }
                val neg = g[1]!!.value.isNotEmpty()
                val key = g[2]!!.value
                val op = g[3]!!.value
                val raw = g[4]!!.value
                val value = unquote(raw)
                var bad: String? = null
                var level: LogLevel? = null
                var age: Long? = null
                var regex: Regex? = null
                if (value.isEmpty()) {
                    // half typed: ignored, no error
                } else if (key !in KEYS) {
                    bad = "Unknown key “$key:”. Try tag:, package:, message:, level:, age: or is:."
                } else {
                    when (key) {
                        "level" -> {
                            level = LogLevel.entries.firstOrNull { it.word.startsWith(value.lowercase()) }
                            if (level == null) bad = "level: expects verbose, debug, info, warn, error or assert."
                        }
                        "age" -> {
                            val a = ageRe.find(value)
                            age = a?.let { it.groupValues[1].toLongOrNull()?.let { n -> n * ageUnits.getValue(a.groupValues[2][0]) } }
                            if (age == null) bad = "age: expects a number and s, m, h or d, like 5m."
                        }
                        "is" -> if (!"crash".startsWith(value) && !"stacktrace".startsWith(value)) {
                            bad = "is: expects crash or stacktrace."
                        }
                        else -> if (op == "~") {
                            regex = runCatching { Regex(value, RegexOption.IGNORE_CASE) }.getOrNull()
                            if (regex == null) bad = "That regular expression doesn't parse yet."
                        }
                    }
                }
                val flagged = bad != null
                if (flagged && error == null) error = bad
                if (bad == null && value.isNotEmpty()) {
                    groups.last() += Term(neg, key, op, value, level, age, regex)
                }
                var p = at
                if (neg) spans += QuerySpan(p++, p, SpanKind.Negation)
                val keyEnd = p + key.length + 1 + op.length
                if (flagged || op.isEmpty()) {
                    spans += QuerySpan(p, keyEnd, if (flagged) SpanKind.Error else SpanKind.Key)
                } else {
                    spans += QuerySpan(p, p + key.length, SpanKind.Key)
                    spans += QuerySpan(p + key.length, p + key.length + 1, SpanKind.Operator)
                    spans += QuerySpan(keyEnd - 1, keyEnd, SpanKind.Key)
                }
                if (raw.isNotEmpty()) {
                    val kind = when {
                        flagged -> SpanKind.Error
                        raw.startsWith('"') -> SpanKind.Quoted
                        else -> null
                    }
                    if (kind != null) spans += QuerySpan(keyEnd, keyEnd + raw.length, kind)
                }
            }
            return LogcatQuery(groups.filter { it.isNotEmpty() }, spans, error)
        }
    }
}

/** The terms of [text] as written, in order, without `|` operators. */
fun queryTerms(text: String): List<String> = termRanges(text).map { text.substring(it) }

private fun termRanges(text: String): List<IntRange> =
    token.findAll(text).filter { it.groups[5] == null }.map { it.range }.toList()

/**
 * Returns [text] with [term] added ([on]) or removed. Turning a `level:` term on first removes other `level:`
 * terms so only one applies.
 */
fun withTerm(text: String, term: String, on: Boolean): String {
    val drop = termRanges(text).filter { r ->
        val t = text.substring(r)
        if (on) term.startsWith("level:") && t.startsWith("level:") && t != term else t == term
    }
    val sb = StringBuilder(text)
    for (r in drop.asReversed()) {
        var end = r.last + 1
        while (end < sb.length && sb[end].isWhitespace()) end++
        sb.delete(r.first, end)
    }
    var out = sb.toString().trimEnd()
    if (on && queryTerms(text).none { it == term }) out = if (out.isEmpty()) term else "$out $term"
    return out
}
