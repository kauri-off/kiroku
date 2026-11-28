package io.github.kauri_off.kiroku.routes

import android.content.Context
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.encodeToSavedState
import androidx.test.core.app.ApplicationProvider
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.demo.DemoUserLists
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.list.ListMode
import io.github.kauri_off.kiroku.manga.MangaListKey
import io.github.kauri_off.kiroku.manga.MangaTitleKey
import io.github.kauri_off.kiroku.profile.OtherProfileKey
import io.github.kauri_off.kiroku.profile.ProfileTags
import io.github.kauri_off.kiroku.search.BrowseKey
import io.github.kauri_off.kiroku.shell.ActivityNavigation
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.shell.Destination
import io.github.kauri_off.kiroku.shell.NotBuilt
import io.github.kauri_off.kiroku.shell.NotBuiltKey
import io.github.kauri_off.kiroku.shell.SearchRoot
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.discardUnreadableComposeState
import io.github.kauri_off.kiroku.shell.navSerializer
import io.github.kauri_off.kiroku.shell.rememberShellNavigator
import io.github.kauri_off.kiroku.text.named
import io.github.kauri_off.kiroku.title.TitleKey
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.util.Locale

// Process death: the saved state comes back, every view model is new.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ProcessDeathTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))

    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test
    fun `restoring legacy recommendation placeholders leaves their titles usable`() {
        val restoration = StateRestorationTester(compose)
        val title = TitleKey(FixtureAnime.exactToWatch.id.value)
        val searchTitle = TitleKey(FixtureAnime.prequel.id.value)
        val legacy = NotBuiltKey(NotBuilt.Recommendations)
        assertEquals("io.github.kauri_off.kiroku.shell.NotBuiltKey", legacy.javaClass.name)
        assertEquals("Recommendations", legacy.screen.name)
        lateinit var navigator: ShellNavigator
        var showApp by mutableStateOf(false)
        var migration: ActivityNavigation? = null
        var registry = SaveableStateRegistry(restoredValues = null) { true }
        restoration.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                navigator = rememberShellNavigator(null, migration)
                if (showApp) {
                    KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph, navigator) }
                }
            }
        }
        val saved =
            compose.runOnIdle {
                navigator.select(Destination.Search)
                navigator.open(searchTitle)
                navigator.open(legacy)
                navigator.select(Destination.List)
                navigator.open(title)
                navigator.open(legacy)
                registry.performSave()
            }
        registry = SaveableStateRegistry(saved) { true }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create()
        migration = ActivityNavigation(activity.get().savedStateRegistry)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(Destination.List.root, title), navigator.stack)
            assertEquals(listOf(Destination.Search.root, searchTitle), navigator.stackOf(Destination.Search))
            val composeValues = registry.performSave().values.flatten()
            assertTrue(composeValues.all { it == null })
            showApp = true
        }
        compose.onNodeWithContentDescription("Episodes watched").assertExists()
        val migrated = Bundle()
        activity.saveInstanceState(migrated)
        val recreated = Robolectric.buildActivity(ComponentActivity::class.java).create(migrated)
        val dedicated = checkNotNull(ActivityNavigation(recreated.get().savedStateRegistry).restored)
        assertEquals(listOf(Destination.List.root, title), dedicated.stack)
        assertEquals(listOf(Destination.Search.root, searchTitle), dedicated.stackOf(Destination.Search))
        activity.destroy()
        recreated.destroy()
    }

    @Test
    fun `a legacy saved manga list becomes the List root while its title stays open`() {
        val title = MangaTitleKey(1)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create()
        ActivityNavigation(activity.get().savedStateRegistry).navigator = ShellNavigator.inMemory()
        val state = Bundle()
        activity.saveInstanceState(state)
        val registry = checkNotNull(state.getBundle("androidx.lifecycle.BundlableSavedStateRegistry.key"))
        val saved = checkNotNull(registry.getBundle("kiroku.shell.navigation"))
        val stack = NavBackStack<NavKey>(Destination.List.root, MangaListKey, title)
        saved.putBundle("List", encodeToSavedState(navSerializer, stack))

        val recreated = Robolectric.buildActivity(ComponentActivity::class.java).create(parcelled(state))
        val restored = checkNotNull(ActivityNavigation(recreated.get().savedStateRegistry).restored)
        assertEquals(listOf(Destination.List.root, title), restored.stack)
        assertEquals(ListMode.Manga, restored.listMode)
        assertTrue(restored.back())
        assertEquals(listOf(Destination.List.root), restored.stack)
        assertEquals(ListMode.Manga, restored.listMode)

        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph, restored) } }
        compose.onNodeWithText("My list").assertExists()
        compose.onNodeWithText("Manga").assertDoesNotExist()
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji).assertExists()
        activity.destroy()
        recreated.destroy()
    }

    @Test
    fun `signed-in legacy Compose navigation resets before the first B loader is selected`() {
        val restoration = StateRestorationTester(compose)
        val previous = OtherProfileKey("legacy_fixture")
        val a = SignInId("fixture-a")
        val b = SignInId("fixture-b")
        lateinit var navigator: ShellNavigator
        var migration: ActivityNavigation? = null
        var showApp by mutableStateOf(true)
        var checked = false
        var registry = SaveableStateRegistry(restoredValues = null) { true }
        val source =
            object : AppGraph by graph {
                override val session =
                    object : SessionRepository by graph.session {
                        override fun observeSession() = flowOf(Session.SignedIn(if (migration == null) a else b))
                    }

                override fun imageLoaderFor(session: Session): coil3.ImageLoader? {
                    if (session == Session.SignedIn(b)) {
                        assertEquals(Destination.List, navigator.current)
                        assertEquals(listOf(Destination.Profile.root), navigator.stackOf(Destination.Profile))
                        checked = true
                    }
                    return null
                }
            }
        restoration.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                navigator = rememberShellNavigator(null, migration)
                if (showApp) {
                    KirokuTheme(dark = false, dynamicColor = false) { AppRoute(source, navigator) }
                }
            }
        }
        compose.waitUntil(5_000) { navigator.sessionOwner == a }
        compose.runOnIdle {
            navigator.select(Destination.Profile)
            navigator.open(previous)
        }
        val saved = compose.runOnIdle { registry.performSave().also { showApp = false } }
        compose.waitForIdle()
        val legacy = saved.mapValues { (_, values) -> values.filterNot { it == a.value || (it as? MutableState<*>)?.value == a.value } }
        assertEquals(saved.values.sumOf { it.size } - 1, legacy.values.sumOf { it.size })
        assertTrue(legacy.values.flatten().contains(true))
        registry = SaveableStateRegistry(legacy) { true }
        migration = compose.runOnIdle { ActivityNavigation(compose.activity.savedStateRegistry) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(navigator.restoredNavigation)
            assertEquals(listOf(Destination.Profile.root, previous), navigator.stackOf(Destination.Profile))
            showApp = true
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { checked }
        assertEquals(1, navigator.restarts)
    }

    @Test
    fun `an incompatible serialized type on an inactive stack resets only that stack`() {
        val restoration = StateRestorationTester(compose)
        val title = TitleKey(FixtureAnime.exactToWatch.id.value)
        val searchTitle = TitleKey(FixtureAnime.prequel.id.value)
        lateinit var navigator: ShellNavigator
        var registry = SaveableStateRegistry(restoredValues = null) { true }
        restoration.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                navigator = rememberShellNavigator()
            }
        }
        val saved =
            compose.runOnIdle {
                navigator.restart()
                navigator.select(Destination.Search)
                navigator.open(searchTitle)
                navigator.select(Destination.List)
                navigator.open(title)
                registry.performSave()
            }
        val searchStack =
            saved.values.flatten().filterIsInstance<Bundle>().single {
                it.getBundle("0")?.getString("type") == SearchRoot::class.java.name
            }
        val incompatible = checkNotNull(searchStack.getBundle("1"))
        assertEquals(TitleKey::class.java.name, incompatible.getString("type"))
        incompatible.putString("type", "incompatible.PreviewTitle")
        assertEquals(listOf("List"), saved.values.flatten().filterIsInstance<String>())
        assertEquals(listOf(1), saved.values.flatten().filterIsInstance<Int>())
        val legacySelected =
            saved.mapValues { (_, values) ->
                values.map {
                    when (it) {
                        "List" -> mutableStateOf(Destination.Profile)
                        1 -> mutableIntStateOf(7)
                        else -> it
                    }
                }
            }
        registry = SaveableStateRegistry(legacySelected) { true }

        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(Destination.Profile, navigator.current)
            assertEquals(7, navigator.restarts)
            assertEquals(listOf(Destination.List.root, title), navigator.stackOf(Destination.List))
            assertEquals(listOf(Destination.Search.root), navigator.stackOf(Destination.Search))
            navigator.select(Destination.Search)
            navigator.open(searchTitle)
            assertEquals(listOf(Destination.Search.root, searchTitle), navigator.stack)
        }
    }

    @Test
    @Config(sdk = [30])
    fun `eager Compose unparcelling cannot erase compatible Activity navigation`() {
        val registryKey = "androidx.lifecycle.BundlableSavedStateRegistry.key"
        val composeKey = "SaveableStateRegistry:-1"
        val navigationKey = "kiroku.shell.navigation"
        val title = TitleKey(FixtureAnime.exactToWatch.id.value)
        val searchTitle = TitleKey(FixtureAnime.prequel.id.value)
        val profileTitle = TitleKey(FixtureAnime.notOnListAiring.id.value)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val navigation = ActivityNavigation(activity.get().savedStateRegistry)
        navigation.navigator =
            ShellNavigator.inMemory().apply {
                hadSession = true
                restart()
                open(title)
                select(Destination.Search)
                open(searchTitle)
                select(Destination.Profile)
                open(profileTitle)
                select(Destination.List)
            }
        val state = Bundle()
        activity.saveInstanceState(state)
        val providers = checkNotNull(state.getBundle(registryKey))
        val savedNavigation = checkNotNull(providers.getBundle(navigationKey))
        assertEquals(setOf("List", "Search", "Profile", "selected", "restarts", "hadSession"), savedNavigation.keySet())
        checkNotNull(savedNavigation.getBundle("Profile")?.getBundle("1")).putString("type", "incompatible.PreviewTitle")
        providers.putBundle(
            composeKey,
            Bundle().apply {
                putParcelableArrayList("moved-class", arrayListOf(UnreadableSelection()))
            },
        )

        val uncleanCompose = checkNotNull(parcelled(state).getBundle(registryKey)?.getBundle(composeKey))
        assertThrows(BadParcelableException::class.java) { uncleanCompose.keySet() }

        val restoredState = parcelled(state)
        discardUnreadableComposeState(restoredState)
        assertNull(restoredState.getBundle(registryKey)?.getBundle(composeKey))
        val recreated = Robolectric.buildActivity(ComponentActivity::class.java).create(restoredState)
        val restored = checkNotNull(ActivityNavigation(recreated.get().savedStateRegistry).restored)
        assertEquals(Destination.List, restored.current)
        assertEquals(1, restored.restarts)
        assertEquals(listOf(Destination.List.root, title), restored.stack)
        assertEquals(listOf(Destination.Search.root, searchTitle), restored.stackOf(Destination.Search))
        assertEquals(listOf(Destination.Profile.root), restored.stackOf(Destination.Profile))

        runBlocking { graph.session.signOut() }
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph, restored) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, restored.restarts)
            assertEquals(Destination.List, restored.current)
            assertEquals(Destination.entries.map { listOf(it.root) }, Destination.entries.map { restored.stackOf(it).toList() })
        }
        activity.destroy()
        recreated.destroy()
    }

    private fun parcelled(state: Bundle): Bundle =
        Parcel.obtain().let { parcel ->
            try {
                parcel.writeBundle(state)
                parcel.setDataPosition(0)
                checkNotNull(parcel.readBundle(javaClass.classLoader))
            } finally {
                parcel.recycle()
            }
        }

    class UnreadableSelection : Parcelable {
        override fun describeContents() = 0

        override fun writeToParcel(
            dest: Parcel,
            flags: Int,
        ) = Unit

        companion object {
            @JvmField
            val CREATOR =
                object : Parcelable.Creator<UnreadableSelection> {
                    override fun createFromParcel(source: Parcel): UnreadableSelection = throw BadParcelableException("Old selection")

                    override fun newArray(size: Int): Array<UnreadableSelection?> = arrayOfNulls(size)
                }
        }
    }

    @Test
    fun `the list's typed filter comes back after process death`() {
        var owner = Owner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        compose.onNodeWithContentDescription("Filter by title").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("kag")
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("kag")).assertExists()
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji).assertExists()
    }

    @Test
    fun `the list's tab and an open edit sheet with a typed count come back after process death`() {
        var owner = Owner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        compose.onNode(hasText("Completed") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(FixtureAnime.prequel.titles.romaji).performTouchInput { longClick() }
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Episodes watched"))
        field.performTextReplacement("1")
        field.performTextReplacement("17")
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("17")).assertExists()
        compose.onNode(hasText("Completed") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
    }

    @Test
    fun `the typed search comes back with a fresh view model after process death`() {
        var owner = Owner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        compose.onNode(hasText("Search") and hasClickAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("kagetosho")
        compose.waitForIdle()
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasSetTextAction() and hasText("kagetosho")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `another user's profile comes back after process death`() {
        var owner = Owner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        val other = Fixtures.reference.otherUserName
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasText("Open another profile"))
        compose.onNodeWithText("Open another profile").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(other)
        compose.onNode(hasText("Open") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("View list").performScrollTo()
        compose.onNodeWithText(other).assertExists()
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(other).assertExists()
        compose.onNodeWithText("View list").performScrollTo()
        compose.onNodeWithText("Completed").assertExists()
    }

    @Test
    fun `another user's statistics page comes back after process death and makes the one read the list needs`() {
        var owner = Owner()
        var shown by mutableStateOf(graph)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(shown) }
            }
        }
        val other = Fixtures.reference.otherUserName
        val statistics = statisticsOf(Fixtures.reference.otherUserList)
        val first = statistics.genres.first()
        val line = "${genreName(first.value)}:\u00A0${NumberFormat.getIntegerInstance(Locale.ENGLISH).format(first.count)}\u00A0titles"
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasText("Open another profile"))
        compose.onNodeWithText("Open another profile").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(other)
        compose.onNode(hasText("Open") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasContentDescription("Genres", substring = true))
        compose.onNodeWithContentDescription("Genres", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(line).assertExists()

        // A new process: every view model and the lists held in memory are gone.
        val restarted = TestGraph(Demo(Fixtures.reference, scope))
        owner.viewModelStore.clear()
        owner = Owner()
        shown = restarted
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Genres").assertExists()
        compose.onNodeWithContentDescription(line).assertExists()
        val wholeRead = (Fixtures.reference.otherUserList.size + DemoUserLists.PART_SIZE - 1) / DemoUserLists.PART_SIZE
        assertEquals(wholeRead, restarted.demo.userLists.requests.size)
    }

    private fun genreName(genre: Genre): String = genre.named { ApplicationProvider.getApplicationContext<Context>().getString(it) }

    // A sign-out made while the window was gone is still one when it comes back: the session was there before.
    @Test
    fun `a sign-out made while the app was gone closes the screens the window comes back with`() {
        var owner = Owner()
        var navigator: ShellNavigator? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) {
                    navigator = rememberShellNavigator()
                    AppRoute(graph, navigator!!)
                }
            }
        }
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasText("Open another profile"))
        compose.onNodeWithText("Open another profile").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(Fixtures.reference.otherUserName)
        compose.onNode(hasText("Open") and hasClickAction()).performClick()
        compose.waitForIdle()
        graph.readsHeld = true
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(2, navigator!!.stackOf(Destination.Profile).size)
        runBlocking { graph.session.signOut() }
        graph.readsHeld = false
        compose.waitForIdle()
        assertEquals(Destination.List, navigator!!.current)
        assertEquals(Destination.entries.map { listOf(it.root) }, Destination.entries.map { navigator!!.stackOf(it).toList() })
    }

    // Once the sign-out has closed the screens, a window rebuilt while signed out keeps what was opened since.
    @Test
    fun `a window rebuilt after a sign-out keeps the screens opened since`() {
        var owner = Owner()
        var navigator: ShellNavigator? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) {
                    navigator = rememberShellNavigator()
                    AppRoute(graph, navigator!!)
                }
            }
        }
        runBlocking { graph.session.signOut() }
        compose.waitForIdle()
        assertEquals(1, navigator!!.restarts)
        val browse = BrowseKey(BrowseList.ThisSeason)
        compose.runOnUiThread {
            navigator!!.select(Destination.Search)
            navigator!!.open(browse)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Year, 2026").performClick()
        compose.onNodeWithText("2025").performClick()
        compose.onNodeWithContentDescription("Season, Winter").performClick()
        compose.onNodeWithText("Fall").performClick()
        compose.waitForIdle()
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(Destination.Search, navigator!!.current)
        assertEquals(listOf(Destination.Search.root, browse), navigator!!.stackOf(Destination.Search))
        compose.onNodeWithContentDescription("Season, Fall").assertExists()
        compose.onNodeWithContentDescription("Year, 2025").assertExists()
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji, substring = true).assertExists()
    }

    @Test
    fun `restored suggestions retain their opening sign-in and are pruned after replacement`() {
        val a = Fixtures.reference.session
        val active = MutableStateFlow<Session>(a)
        val source =
            object : AppGraph by graph {
                override val session =
                    object : SessionRepository by graph.session {
                        override fun observeSession() = active
                    }
            }
        var owner = Owner()
        lateinit var navigator: ShellNavigator
        val restoration = StateRestorationTester(compose)
        val key = BrowseKey(BrowseList.Suggestions, a.signIn.value)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) {
                    navigator = rememberShellNavigator()
                    AppRoute(source, navigator)
                }
            }
        }
        compose.waitUntil(5_000) { navigator.sessionOwner == a.signIn }
        compose.runOnIdle {
            navigator.select(Destination.Search)
            navigator.open(key)
        }
        compose.waitForIdle()
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { navigator.sessionOwner == a.signIn }
        assertEquals(listOf(SearchRoot, key), navigator.stack)
        val replacement = SignInId("replacement")
        active.value = Session.SignedIn(replacement)
        compose.waitUntil(5_000) { navigator.sessionOwner == replacement }
        assertEquals(listOf(SearchRoot), navigator.stackOf(Destination.Search))
        assertEquals(Destination.Search, navigator.current)
    }
}
