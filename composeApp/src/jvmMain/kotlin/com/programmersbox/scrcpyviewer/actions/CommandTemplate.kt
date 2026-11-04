package com.programmersbox.scrcpyviewer.actions

/** `{name}` variables in run-once commands; `{{` and `}}` are literal braces. */
object CommandTemplate {
    private val name = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Distinct variable names, in first-appearance order. */
    fun variables(command: String): List<String> {
        val found = LinkedHashSet<String>()
        scan(command) { variable, _ -> found += variable; null }
        return found.toList()
    }

    /** Replaces known variables as-is; unknown ones stay as `{name}`; `{{`/`}}` become `{`/`}`. */
    fun substitute(command: String, values: Map<String, String>): String =
        scan(command) { variable, _ -> values[variable] }

    private fun scan(command: String, onVariable: (name: String, raw: String) -> String?): String {
        val out = StringBuilder()
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                c == '{' && command.startsWith("{{", i) -> { out.append('{'); i += 2 }
                c == '}' && command.startsWith("}}", i) -> { out.append('}'); i += 2 }
                c == '{' -> {
                    val end = command.indexOf('}', i + 1)
                    val candidate = if (end > i) command.substring(i + 1, end) else null
                    if (candidate != null && name.matches(candidate)) {
                        val raw = command.substring(i, end + 1)
                        out.append(onVariable(candidate, raw) ?: raw)
                        i = end + 1
                    } else {
                        out.append(c); i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
