package com.programmersbox.scrcpyviewer.ui.mirror.logcat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.logcat.withTerm
import com.programmersbox.scrcpyviewer.ui.common.EmphasizedDecelerate
import java.awt.datatransfer.StringSelection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A batch with more new lines than this (the `-T 1000` backlog, a resume's read-back) appears without growing. */
private const val MAX_GROW_BATCH = 50

/** How long a pinned list keeps re-pinning after new lines, while they grow in. */
private const val STICK_MILLIS = 340L

/**
 * Which rows arrived live, for the grow-in and the crash flash. [observe] is called with every new item list: line
 * keys newer than any seen before become [fresh], unless this is the first look at lines or the batch is a flood.
 * Rows revealed by a filter change are older than the newest key seen, so they never grow.
 */
internal class LiveRows {
    private var last: List<LogcatItem>? = null
    private var newest = Long.MIN_VALUE
    private val flashed = HashSet<Long>()

    /** Keys of the lines the last batch added live. */
    var fresh: Set<Long> = emptySet()
        private set

    fun observe(items: List<LogcatItem>) {
        if (items === last) return
        last = items
        val first = newest == Long.MIN_VALUE
        val added = HashSet<Long>()
        var top = newest
        for (i in items.indices.reversed()) {
            val item = items[i] as? LogcatItem.Line ?: continue
            if (item.key <= newest) break
            if (item.key > top) top = item.key
            if (added.size <= MAX_GROW_BATCH) added += item.key
        }
        newest = top
        fresh = if (first || added.size > MAX_GROW_BATCH) emptySet() else added
    }

    /** True the first time it's asked about [key]. */
    fun flashOnce(key: Long): Boolean = flashed.add(key)
}

/**
 * Following the newest line. The list is [pinned] until the user scrolls up, and pins again at the end. While
 * pinned each change scrolls to the last row, again every frame for [STICK_MILLIS] as new rows grow in; while not,
 * [unseen] counts new lines that pass the filter.
 */
@Stable
internal class LogcatFollow(val state: LazyListState) {
    var pinned by mutableStateOf(true)
        private set
    var unseen by mutableIntStateOf(0)
        private set
    private var newest = Long.MIN_VALUE

    /** Unpins on a user scroll toward the top. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f && state.canScrollBackward) pinned = false
            return Offset.Zero
        }
    }

    suspend fun run(items: () -> List<LogcatItem>, motion: Boolean) = coroutineScope {
        launch {
            snapshotFlow { state.canScrollForward }.collect { more ->
                if (!more) {
                    pinned = true
                    unseen = 0
                }
            }
        }
        snapshotFlow(items).collectLatest { v ->
            val added = countNew(v)
            if (!pinned) {
                unseen += added
                return@collectLatest
            }
            unseen = 0
            if (v.isEmpty()) return@collectLatest
            if (motion) stickAWhile() else stick()
        }
    }

    /** Scrolls to the end now; on the pill, smoothly with [motion]. */
    suspend fun jump(motion: Boolean) {
        pinned = true
        unseen = 0
        val last = state.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        try {
            if (motion) state.animateScrollToItem(last) else state.scrollToItem(last)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
        stickAWhile()
    }

    /** Re-pins every frame for [STICK_MILLIS] while rows grow in. */
    private suspend fun stickAWhile() {
        stick()
        val start = withFrameMillis { it }
        while (pinned && withFrameMillis { it } - start < STICK_MILLIS) stick()
    }

    private suspend fun stick() {
        val last = state.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        try {
            state.scrollToItem(last)
        } catch (e: CancellationException) {
            // A user scroll took over; keep following only if this coroutine is still wanted.
            currentCoroutineContext().ensureActive()
        }
    }

    private fun countNew(v: List<LogcatItem>): Int {
        var n = 0
        var top = newest
        for (i in v.indices.reversed()) {
            val item = v[i] as? LogcatItem.Line ?: continue
            if (item.key <= newest) break
            if (item.key > top) top = item.key
            n++
        }
        val counted = if (newest == Long.MIN_VALUE) 0 else n
        newest = top
        return counted
    }
}

/**
 * The log: a column header, then the rows of [LogcatController.visible] newest at the bottom, the paused banner,
 * the "N new lines" pill, the empty-filter message and, before the first play, the start card. Rows are monospace
 * and selectable; columns follow compact view and [sheetWidth] (PID-TID and Package hide under 600 dp).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LogcatList(controller: LogcatController, sheetWidth: Dp, motion: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val live = remember { LiveRows() }
    live.observe(controller.items)
    val visible = controller.visible
    val listState = rememberLazyListState()
    val follow = remember(listState) { LogcatFollow(listState) }
    val columns = rememberLogcatColumns(controller.compact, sheetWidth < 600.dp, motion)
    val words = controller.parsed.highlightWords
    val mine = controller.mine
    val wrap = controller.wrap
    val run = controller.run
    // Entries whose fold the user flipped from the default (crashes unfolded, others folded).
    val flipped = remember { mutableStateMapOf<Long, Boolean>() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val actions = remember(controller, clipboard, scope) {
        LogcatRowActions(
            filter = { key, value, exclude ->
                val v = if (value.any { it.isWhitespace() || it == '"' || it == '|' }) "\"${value.replace("\"", "")}\"" else value
                controller.query = withTerm(controller.query, "${if (exclude) "-" else ""}$key:$v", true)
                controller.toast(if (exclude) "Hiding $value" else "Filtering to $value")
            },
            toggle = { key -> flipped[key] = flipped[key] != true },
            copyFrame = { loc ->
                scope.launch {
                    runCatching { clipboard.setClipEntry(ClipEntry(StringSelection(loc))) }
                    controller.toast("Copied $loc")
                }
            },
        )
    }
    LaunchedEffect(follow, motion) { follow.run({ controller.visible }, motion) }

    Column(modifier.background(scheme.surfaceContainerLowest)) {
        LogcatHeader(columns)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize().nestedScroll(follow.connection),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(visible, key = { it.key }, contentType = { if (it is LogcatItem.Line) 0 else 1 }) { item ->
                        val itemMotion = Modifier.animateItem(
                            fadeInSpec = if (motion) tween(240) else null,
                            placementSpec = if (motion) tween(240, easing = EmphasizedDecelerate) else null,
                            fadeOutSpec = if (motion) tween(240) else null,
                        )
                        when (item) {
                            is LogcatItem.Line -> Box(itemMotion) {
                                // Lines that came while scrolled up are announced by the pill instead.
                                val isNew = remember { item.key in live.fresh && follow.pinned }
                                val flash = remember { motion && isNew && item.entry.isCrash && live.flashOnce(item.key) }
                                val crash = item.entry.isCrash
                                val expanded = if (flipped[item.key] == true) !crash else crash
                                val row = @Composable {
                                    LogcatLineRow(item, columns, words, wrap, expanded, mine, flash, motion, actions)
                                }
                                if (motion && isNew) GrowIn(row) else row()
                            }
                            is LogcatItem.Gap -> Box(itemMotion) { LogcatGapRow(item) }
                        }
                    }
                }
            }
            LogcatEmptyMessage(run != LogcatRun.NotStarted && controller.query.isNotBlank() && visible.none { it is LogcatItem.Line }, motion)
            LogcatPausedBanner(pausedBannerText(run, controller.pausedAt), motion, Modifier.align(Alignment.TopCenter))
            LogcatNewLinesPill(
                follow.unseen,
                !follow.pinned && follow.unseen > 0,
                motion,
                onClick = { scope.launch { follow.jump(motion) } },
                Modifier.align(Alignment.BottomCenter),
            )
            LogcatStartCard(run == LogcatRun.NotStarted, motion, controller::play)
        }
    }
}

/** A live row growing from 0 height and fading in, 300 ms. */
@Composable
private fun GrowIn(content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        state,
        enter = expandVertically(tween(300, easing = EmphasizedDecelerate), expandFrom = Alignment.Top) +
            fadeIn(tween(300, easing = EmphasizedDecelerate)),
    ) { content() }
}
