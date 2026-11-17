package com.programmersbox.scrcpyviewer.telemetry

/**
 * Turns an exact number into a coarse label so events never carry precise values. [edges] must be ascending.
 * Below the first edge gives `<a`, between two edges `a-b` (lower edge inclusive, upper exclusive), at or above the
 * last edge `>=z`. For example edges [1, 5] give `<1`, `1-5`, `>=5`.
 */
fun bucket(value: Long, edges: List<Long>): String {
    if (edges.isEmpty()) return ">=0"
    if (value < edges.first()) return "<${edges.first()}"
    for (i in 0 until edges.lastIndex) {
        if (value < edges[i + 1]) return "${edges[i]}-${edges[i + 1]}"
    }
    return ">=${edges.last()}"
}

/** Durations in minutes: <1, 1-5, 5-15, 15-60, 60-240, >=240. */
val MINUTE_EDGES: List<Long> = listOf(1, 5, 15, 60, 240)

/** Durations in seconds: <1, 1-5, 5-30, 30-120, 120-600, >=600. */
val SECOND_EDGES: List<Long> = listOf(1, 5, 30, 120, 600)

/** Durations in milliseconds: <100, 100-500, 500-2000, 2000-10000, >=10000. */
val MS_EDGES: List<Long> = listOf(100, 500, 2_000, 10_000)

/** Sizes in bytes: <1 KiB, 1 KiB-100 KiB, 100 KiB-1 MiB, 1-10 MiB, 10-100 MiB, >=100 MiB. */
val BYTE_EDGES: List<Long> = listOf(1_024, 102_400, 1_048_576, 10_485_760, 104_857_600)
