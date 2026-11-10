package io.github.kauri_off.kiroku.wiring

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock

class TestGraphApplication :
    Application(),
    AppGraphOwner {
    override val graph: AppGraph = createGraph("0123abcd", Clock.systemUTC(), CoroutineScope(Dispatchers.Unconfined))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestGraphApplication::class)
class AppGraphOwnerTest {
    @Test
    fun `a test application substitutes the graph that code with a context obtains`() {
        val application = RuntimeEnvironment.getApplication() as TestGraphApplication
        assertSame(application.graph, application.appGraph)
        assertSame(application.graph, application.baseContext.appGraph)
    }
}
