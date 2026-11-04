package com.programmersbox.scrcpyviewer.ui.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.SceneStrategyScope
import kotlin.test.Test
import kotlin.test.assertEquals

class TopLevelNavigatorTest {
    private val D = TopLevelRoute.Devices
    private val P = TopLevelRoute.Presets
    private val A = TopLevelRoute.Actions
    private val S = TopLevelRoute.Settings

    @Test
    fun `starts on Devices with every destination kept below it`() {
        val nav = TopLevelNavigator()
        assertEquals(D, nav.current)
        assertEquals(listOf(P, A, S, D), nav.backStack.toList())
    }

    @Test
    fun `selecting a tab moves it to the top and keeps the others`() {
        val nav = TopLevelNavigator()
        nav.select(P)
        assertEquals(P, nav.current)
        assertEquals(listOf(A, S, D, P), nav.backStack.toList())
        nav.select(S)
        assertEquals(listOf(A, D, P, S), nav.backStack.toList())
        nav.select(D)
        assertEquals(listOf(A, P, S, D), nav.backStack.toList())
        nav.select(S)
        assertEquals(listOf(A, P, D, S), nav.backStack.toList())
    }

    @Test
    fun `reselecting the current tab changes nothing`() {
        val nav = TopLevelNavigator(P)
        val before = nav.backStack.toList()
        nav.select(P)
        nav.select(P)
        assertEquals(before, nav.backStack.toList())
    }

    @Test
    fun `every destination appears exactly once after any switches`() {
        val nav = TopLevelNavigator()
        listOf(S, P, P, D, S, D, P).forEach(nav::select)
        assertEquals(TopLevelRoute.all.toSet(), nav.backStack.toSet())
        assertEquals(4, nav.backStack.size)
    }

    @Test
    fun `tab scene shows only the top entry and offers no back`() {
        val entries = listOf(P, S, D).map { route -> NavEntry<TopLevelRoute>(route, contentKey = route) {} }
        val scene = with(TabSceneStrategy<TopLevelRoute>()) { SceneStrategyScope<TopLevelRoute>().calculateScene(entries) }
        assertEquals(listOf<TopLevelRoute>(D), scene.entries.map { it.contentKey })
        assertEquals(emptyList(), scene.previousEntries)
    }

    @Test
    fun `rail order lists every route once`() {
        assertEquals(listOf(D, P, A, S), TopLevelRoute.all)
    }
}
