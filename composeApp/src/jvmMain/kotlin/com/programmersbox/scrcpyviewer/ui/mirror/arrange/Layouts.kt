package com.programmersbox.scrcpyviewer.ui.mirror.arrange

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.ui.mirror.AspectLock
import java.awt.Rectangle
import kotlin.math.ceil
import kotlin.math.floor

enum class ArrangeLayout { AsPlaced, Grid, SideBySide, Focus, Cascade }

/** A mirror window's device [frame] plus the [chromeW] x [chromeH] around the stage (title bar, toolbar, sheet). */
data class ArrangeWindow(val serial: String, val frame: IntSize, val chromeW: Int, val chromeH: Int)

private const val FOCUS_SHARE = 0.62
private const val CASCADE_W = 0.32
private const val CASCADE_H = 0.62

/**
 * Target outer bounds per serial inside [work], in window units; [gapPx] is the gap in the same units.
 * [ArrangeLayout.AsPlaced] returns empty: the arranger restores remembered bounds itself.
 */
fun layout(
    layout: ArrangeLayout,
    windows: List<ArrangeWindow>,
    work: Rectangle,
    focus: String?,
    gapPx: Int,
    cascadeDx: Int = 34,
    cascadeDy: Int = 30,
): Map<String, Rectangle> {
    if (windows.isEmpty()) return emptyMap()
    return when (layout) {
        ArrangeLayout.AsPlaced -> emptyMap()
        ArrangeLayout.Grid -> grid(windows, work, gapPx, null)
        ArrangeLayout.SideBySide -> grid(windows, work, gapPx, windows.size)
        ArrangeLayout.Focus -> focus(windows, work, focus, gapPx)
        ArrangeLayout.Cascade -> cascade(windows, work, cascadeDx, cascadeDy)
    }
}

/** Largest size of [w] (whole units, rounded down) inside a [cw] x [ch] box, keeping the device aspect. */
private fun fit(w: ArrangeWindow, cw: Double, ch: Double): Pair<Int, Int> {
    val lock = AspectLock(w.frame.width, w.frame.height, w.chromeW.toDouble(), w.chromeH.toDouble())
    var width = floor(cw).coerceAtLeast(w.chromeW + 1.0)
    if (lock.outerHeight(width) > ch) {
        width = w.chromeW + (ch - w.chromeH).coerceAtLeast(1.0) * w.frame.width / w.frame.height
    }
    val wi = floor(width).toInt()
    // Height comes from the integer width so the aspect holds to within a unit.
    val hi = floor(lock.outerHeight(wi.toDouble())).toInt()
    return wi to hi
}

private fun centred(w: ArrangeWindow, x: Double, y: Double, cw: Double, ch: Double): Rectangle {
    val (fw, fh) = fit(w, cw, ch)
    return Rectangle(floor(x + (cw - fw) / 2).toInt(), floor(y + (ch - fh) / 2).toInt(), fw, fh)
}

private fun grid(windows: List<ArrangeWindow>, work: Rectangle, gap: Int, fixedCols: Int?): Map<String, Rectangle> {
    val n = windows.size
    var best: Triple<Int, Double, Double>? = null
    var bestArea = -1.0
    for (cols in (fixedCols?.let { listOf(it) } ?: (1..n).toList())) {
        val rows = ceil(n / cols.toDouble()).toInt()
        val cw = (work.width - gap * (cols + 1.0)) / cols
        val ch = (work.height - gap * (rows + 1.0)) / rows
        val area = windows.sumOf { w -> fit(w, cw, ch).let { it.first.toDouble() * it.second } }
        if (area > bestArea) {
            bestArea = area
            best = Triple(cols, cw, ch)
        }
    }
    val (cols, cw, ch) = best!!
    return windows.mapIndexed { i, w ->
        val c = i % cols
        val r = i / cols
        w.serial to centred(w, work.x + gap + c * (cw + gap), work.y + gap + r * (ch + gap), cw, ch)
    }.toMap()
}

private fun focus(windows: List<ArrangeWindow>, work: Rectangle, focus: String?, gap: Int): Map<String, Rectangle> {
    val main = windows.firstOrNull { it.serial == focus } ?: windows.first()
    val rest = windows.filter { it !== main }
    val mainW = if (rest.isEmpty()) work.width.toDouble() else work.width * FOCUS_SHARE
    val out = LinkedHashMap<String, Rectangle>()
    out[main.serial] = centred(main, work.x + gap.toDouble(), work.y + gap.toDouble(), mainW - 2 * gap, work.height - 2.0 * gap)
    if (rest.isNotEmpty()) {
        val cellH = (work.height - gap * (rest.size + 1.0)) / rest.size
        val colW = work.width - mainW - gap
        rest.forEachIndexed { i, w ->
            out[w.serial] = centred(w, work.x + mainW, work.y + gap + i * (cellH + gap), colW, cellH)
        }
    }
    return out
}

private fun cascade(windows: List<ArrangeWindow>, work: Rectangle, dx: Int, dy: Int): Map<String, Rectangle> =
    windows.mapIndexed { i, w ->
        val (fw, fh) = fit(w, work.width * CASCADE_W, work.height * CASCADE_H)
        val x = (work.x + i * dx).coerceAtMost(work.x + work.width - fw)
        val y = (work.y + i * dy).coerceAtMost(work.y + work.height - fh)
        w.serial to Rectangle(x, y, fw, fh)
    }.toMap()
