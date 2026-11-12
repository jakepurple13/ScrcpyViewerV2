package com.programmersbox.scrcpyviewer

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.Dispatchers

/**
 * Tests run scenes on the Swing thread (see `onEdt`), and lifecycles check they are on `Dispatchers.Main`'s thread.
 * The app's Main is Tao's event loop (Nucleus' factory, priority 100), which tests never start: this makes Main the
 * Swing thread in tests only (registered in src/jvmTest/resources/META-INF/services).
 */
@OptIn(InternalCoroutinesApi::class)
class TestMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = Int.MAX_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = Dispatchers.Swing
}
