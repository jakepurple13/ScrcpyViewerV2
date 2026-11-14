package com.programmersbox.scrcpyviewer.ui.mirror.shots.markup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The drawing tools of the markup editor. */
enum class MarkupTool { Pen, Arrow, Box, Pixelate }

/** The four mark colors; [argb] is opaque. Red is the default. */
enum class MarkupColor(val argb: Int, val label: String) {
    Red(0xFFE53935.toInt(), "Red"),
    Yellow(0xFFFBC02D.toInt(), "Yellow"),
    Blue(0xFF1E88E5.toInt(), "Blue"),
    Black(0xFF111111.toInt(), "Black"),
}

/** A point in image pixels. */
data class MarkPoint(val x: Float, val y: Float)

/** One thing drawn on the screenshot, in image pixels (so it looks the same at any zoom). */
sealed interface Mark {
    /** Freehand stroke through [points]. */
    data class Pen(val color: Int, val points: List<MarkPoint>) : Mark

    /** Line from [from] to [to] with a filled head at [to]. */
    data class Arrow(val color: Int, val from: MarkPoint, val to: MarkPoint) : Mark

    /** Stroked rectangle between two opposite corners. */
    data class Box(val color: Int, val from: MarkPoint, val to: MarkPoint) : Mark

    /** The rectangle between two opposite corners replaced by blocks of the original's average color. */
    data class Pixelate(val from: MarkPoint, val to: MarkPoint) : Mark
}

/** A drag shorter than this many image pixels adds nothing. */
const val MIN_DRAG_PX = 2f

/** Stroke width in image pixels: 0.8% of the shorter side, at least 3. */
fun strokeWidth(width: Int, height: Int): Float = max(3f, min(width, height) * 0.008f)

/** Pixelate block edge in image pixels: 2.5% of the shorter side, at least 8. */
fun blockSize(width: Int, height: Int): Int = max(8, (min(width, height) * 0.025f).roundToInt())

/** The three corners of an arrow head: [tip] is the arrow's end; the wings are 4x [stroke] long at 26 degrees. */
data class ArrowHead(val tip: MarkPoint, val left: MarkPoint, val right: MarkPoint)

fun arrowHead(from: MarkPoint, to: MarkPoint, stroke: Float): ArrowHead {
    var dx = to.x - from.x
    var dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len < 1e-6f) {
        dx = 1f
        dy = 0f
    } else {
        dx /= len
        dy /= len
    }
    // Unit vector pointing from the tip back along the shaft.
    val bx = -dx
    val by = -dy
    val headLen = 4f * stroke
    val a = 26.0 * PI / 180.0
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    fun wing(sign: Float) = MarkPoint(
        to.x + headLen * (bx * c - sign * by * s),
        to.y + headLen * (sign * bx * s + by * c),
    )
    return ArrowHead(to, wing(1f), wing(-1f))
}

/** Whether [this] is too small to keep: a drag shorter than [MIN_DRAG_PX]. */
internal fun Mark.isTooSmall(): Boolean = when (this) {
    is Mark.Pen -> points.isEmpty() || points.maxOf { hypot(it.x - points[0].x, it.y - points[0].y) } < MIN_DRAG_PX
    is Mark.Arrow -> hypot(to.x - from.x, to.y - from.y) < MIN_DRAG_PX
    is Mark.Box -> hypot(to.x - from.x, to.y - from.y) < MIN_DRAG_PX
    is Mark.Pixelate -> hypot(to.x - from.x, to.y - from.y) < MIN_DRAG_PX
}

/**
 * The marks drawn on a [width] x [height] screenshot, with undo.
 *
 * [marks] is a snapshot-state list: reading it in a composable or draw scope recomposes/redraws when a mark is added
 * or undone. [dirty] and [canUndo] are state-backed too. Not thread-safe: use it from the UI thread.
 */
class MarkupDocument(val width: Int, val height: Int) {
    private val _marks = mutableStateListOf<Mark>()
    private var cleanMarks by mutableStateOf<List<Mark>>(emptyList())

    val marks: List<Mark> get() = _marks

    val canUndo: Boolean get() = _marks.isNotEmpty()

    /** True when the marks differ from those at the last [markClean] (or from none): not yet saved or copied. */
    val dirty: Boolean get() = _marks.isNotEmpty() && _marks.toList() != cleanMarks

    /** Adds [mark]; false (and nothing added) for a drag shorter than 2 px. A pen's points are copied. */
    fun add(mark: Mark): Boolean {
        if (mark.isTooSmall()) return false
        // The caller may keep adding to the list it built the stroke in.
        _marks.add(if (mark is Mark.Pen) mark.copy(points = mark.points.toList()) else mark)
        return true
    }

    /** Removes and returns the last mark, or null when there is none. */
    fun undo(): Mark? {
        if (_marks.isEmpty()) return null
        val removed = _marks.removeAt(_marks.lastIndex)
        return removed
    }

    /** Records the current marks as saved or copied, so [dirty] is false until they change. */
    fun markClean() {
        cleanMarks = _marks.toList()
    }
}
