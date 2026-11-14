package io.github.kauri_off.kiroku.signin

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.repository.SignIn
import io.github.kauri_off.kiroku.core.repository.SignInBrowser
import io.github.kauri_off.kiroku.core.repository.SignInFailure
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.wiring.AppGraph
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.appGraph
import io.github.kauri_off.kiroku.wiring.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock

private fun graph(context: Context): AppGraph = createGraph(context, "0123abcd", Clock.systemUTC(), CoroutineScope(Dispatchers.Unconfined))

class RecordingSignIn : SignIn {
    val redirects = mutableListOf<String>()
    override val step = MutableStateFlow<SignInStep>(SignInStep.None)
    override val canStart = true

    override suspend fun start(browser: SignInBrowser) = Unit

    override fun redirected(uri: String) {
        redirects += uri
    }

    override fun acknowledge(step: SignInStep) = Unit
}

class SignInGraph(
    graph: AppGraph,
    override val signIn: RecordingSignIn,
) : AppGraph by graph,
    SignInOwner

class SignInGraphApplication :
    Application(),
    AppGraphOwner {
    override val graph by lazy { SignInGraph(graph(this), RecordingSignIn()) }
}

// The demo's graph: no MAL account, so no sign-in of its own.
class NoSignInApplication :
    Application(),
    AppGraphOwner {
    override val graph by lazy { TestGraph(Demo(Fixtures.reference, CoroutineScope(Dispatchers.Unconfined))) }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SignInGraphApplication::class)
class RedirectActivityTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val redirect = "${BuildConfig.OAUTH_REDIRECT}?code=the-code&state=the-state"

    private fun view(uri: String?) = Intent(Intent.ACTION_VIEW, uri?.let(Uri::parse), application, RedirectActivity::class.java)

    private fun redirects(): List<String> = (application as SignInGraphApplication).graph.signIn.redirects

    // The redirect activity is gone and the app's own screen is on top of what was there.
    private fun assertTheAppCameBack(
        intent: Intent,
        saved: Bundle? = null,
    ) {
        val activity = Robolectric.buildActivity(RedirectActivity::class.java, intent).create(saved).get()
        assertTrue(activity.isFinishing)
        val started = shadowOf(application).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        val flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags, started.flags and flags)
        assertNull(started.data)
        assertNull(started.extras)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a redirect goes to the graph's sign-in once, whole, and the app's screen comes back without it`() {
        assertTheAppCameBack(view(redirect))
        assertEquals(listOf(redirect), redirects())
    }

    @Test
    fun `an intent without an address is a redirect that cannot be read`() {
        assertTheAppCameBack(view(null))
        assertEquals(listOf(""), redirects())
    }

    @Test
    fun `an intent replayed from the recent tasks is no redirect`() {
        assertTheAppCameBack(view(redirect).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertEquals(emptyList<String>(), redirects())
    }

    @Test
    fun `the activity made again for its redirect does not hand it over twice`() {
        assertTheAppCameBack(view(redirect), saved = Bundle())
        assertEquals(emptyList<String>(), redirects())
    }

    @Test
    @Config(application = NoSignInApplication::class)
    fun `a graph without a sign-in fails the redirect`() {
        val signIn = application.appGraph.signIn
        signIn.acknowledge(signIn.step.value)
        assertFalse(signIn.canStart)

        assertTheAppCameBack(view(redirect))

        val failed = SignInStep.Failed(SignInFailure.Unavailable)
        assertEquals(failed, signIn.step.value)
        signIn.acknowledge(failed)
    }
}
