package com.programmersbox.scrcpyviewer.ui.common

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * This flow's values through [transform], with no coroutine in between: [StateFlow.value] is current the moment
 * this flow changes. Unlike `map { }.stateIn(...)` or `combine`, which publish a change only after their own
 * coroutine has run (`combine` yields after each value, so on the Swing dispatcher that is a later EDT event).
 */
fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> = MappedStateFlow(this, transform)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(private val source: StateFlow<T>, private val transform: (T) -> R) : StateFlow<R> {
    override val value: R get() = transform(source.value)

    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        awaitCancellation() // a StateFlow's collection never ends; neither does the source's
    }
}
