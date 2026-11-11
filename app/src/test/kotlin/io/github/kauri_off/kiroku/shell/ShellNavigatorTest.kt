package io.github.kauri_off.kiroku.shell

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigatorTest {
    private data class Page(
        val n: Int,
    ) : NavKey

    private val navigator = ShellNavigator.inMemory()

    @Test
    fun theAppOpensOnTheListAtItsRoot() {
        assertEquals(Destination.List, navigator.current)
        assertEquals(listOf(ListRoot), navigator.stack)
    }

    @Test
    fun tappingTheCurrentDestinationAgainPopsItToItsRoot() {
        navigator.open(Page(1))
        navigator.open(Page(2))
        navigator.select(Destination.List)
        assertEquals(Destination.List, navigator.current)
        assertEquals(listOf(ListRoot), navigator.stack)
    }

    @Test
    fun tappingAnotherDestinationDoesNotPopEither() {
        navigator.open(Page(1))
        navigator.select(Destination.Search)
        navigator.open(Page(2))
        navigator.select(Destination.List)
        assertEquals(listOf(ListRoot, Page(1)), navigator.stack)
        assertEquals(listOf(SearchRoot, Page(2)), navigator.stackOf(Destination.Search))
    }

    @Test
    fun eachDestinationKeepsItsStack() {
        navigator.open(Page(1))
        navigator.open(Page(2))
        navigator.select(Destination.Search)
        assertEquals(listOf(SearchRoot), navigator.stack)
        navigator.open(Page(3))
        navigator.select(Destination.Profile)
        navigator.select(Destination.List)
        assertEquals(listOf(ListRoot, Page(1), Page(2)), navigator.stack)
        navigator.select(Destination.Search)
        assertEquals(listOf(SearchRoot, Page(3)), navigator.stack)
        assertEquals(listOf(ProfileRoot), navigator.stackOf(Destination.Profile))
    }

    @Test
    fun backPopsTheCurrentStackFirst() {
        navigator.select(Destination.Search)
        navigator.open(Page(1))
        assertTrue(navigator.back())
        assertEquals(Destination.Search, navigator.current)
        assertEquals(listOf(SearchRoot), navigator.stack)
    }

    @Test
    fun backFromTheSearchRootGoesToList() {
        navigator.open(Page(1))
        navigator.select(Destination.Search)
        assertEquals(listOf(ListRoot, Page(1), SearchRoot), navigator.backStack)
        assertTrue(navigator.back())
        assertEquals(Destination.List, navigator.current)
        assertEquals(listOf(ListRoot, Page(1)), navigator.stack)
    }

    @Test
    fun theBackStackIsTheListsStackUnderTheCurrentOne() {
        navigator.open(Page(1))
        assertEquals(listOf(ListRoot, Page(1)), navigator.backStack)
        navigator.select(Destination.Profile)
        navigator.open(Page(2))
        assertEquals(listOf(ListRoot, Page(1), ProfileRoot, Page(2)), navigator.backStack)
        assertEquals(listOf(SearchRoot), navigator.stackOf(Destination.Search))
    }

    @Test
    fun backFromTheProfileRootGoesToList() {
        navigator.select(Destination.Profile)
        assertTrue(navigator.back())
        assertEquals(Destination.List, navigator.current)
    }

    @Test
    fun backFromTheListRootLeavesTheApp() {
        assertEquals(listOf(ListRoot), navigator.backStack)
        assertFalse(navigator.back())
        assertEquals(Destination.List, navigator.current)
        assertEquals(listOf(ListRoot), navigator.stack)
    }
}
