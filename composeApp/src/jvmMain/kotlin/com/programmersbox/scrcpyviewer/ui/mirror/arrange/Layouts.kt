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
    if (windows.isEmpty() || work.width <= 0 || work.height <= 0) return emptyMap()
    return when (layout) {
        ArrangeLayout.AsPlaced -> emptyMap()
        ArrangeLayout.Grid -> grid(windows, work, gapPx, null)
        ArrangeLayout.SideBySide -> grid(windows, work, gapPx, windows.size)
        ArrangeLayout.Focus -> focus(windows, work, focus, gapPx)
        ArrangeLayout.Cascade -> cascade(windows, work, cascadeDx, cascadeDy)
    }.mapValues { (_, r) -> r.insideOf(work) }
}

/** Shrinks to the [work] size if needed and moves inside it, so no result ever leaves the work area. */
private fun Rectangle.insideOf(work: Rectangle): Rectangle {
    val w = width.coerceIn(1, work.width)
    val h = height.coerceIn(1, work.height)
    return Rectangle(x.coerceIn(work.x, work.x + work.width - w), y.coerceIn(work.y, work.y + work.height - h), w, h)
}

/**
 * Largest size of [w] (whole units, rounded down) inside a [cw] x [ch] box, keeping the device aspect. Degenerate
 * frame sizes count as 1 and a box smaller than the chrome shrinks the window to the box (aspect is given up).
 */
private fun fit(w: ArrangeWindow, cw: Double, ch: Double): Pair<Int, Int> {
    val fw = w.frame.width.coerceAtLeast(1)
    val fh = w.frame.height.coerceAtLeast(1)
    val lock = AspectLock(fw, fh, w.chromeW.toDouble(), w.chromeH.toDouble())
    val boxW = floor(cw).coerceAtLeast(1.0)
    val boxH = floor(ch).coerceAtLeast(1.0)
    var width = boxW.coerceAtLeast(w.chromeW + 1.0)
    if (lock.outerHeight(width) > boxH) {
        width = w.chromeW + (boxH - w.chromeH).coerceAtLeast(1.0) * fw / fh
    }
    width = width.coerceAtMost(boxW)
    val wi = floor(width).toInt().coerceAtLeast(1)
    // Height comes from the integer width so the aspect holds to within a unit.
    val hi = floor(lock.outerHeight(wi.toDouble())).coerceAtMost(boxH).toInt().coerceAtLeast(1)
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

private fun cascade(windows: List<ArrangeWindow>, work: Rectangle, dx: Int, dy: Int): Map<String, Rectangle> {
    var wrap = 0
    var step = 0
    return windows.associate { w ->
        val (fw, fh) = fit(w, work.width * CASCADE_W, work.height * CASCADE_H)
        var x = work.x + wrap * (dx / 4 + 1) + step * dx
        var y = work.y + wrap * (dy / 4 + 1) + step * dy
        if (step > 0 && (x + fw > work.x + work.width || y + fh > work.y + work.height)) {
            // Past the edge: start again at the top-left, nudged a little more each restart so no window lands on another.
            wrap++
            step = 0
            x = work.x + wrap * (dx / 4 + 1)
            y = work.y + wrap * (dy / 4 + 1)
        }
        step++
        w.serial to Rectangle(x, y, fw, fh)
    }
}
