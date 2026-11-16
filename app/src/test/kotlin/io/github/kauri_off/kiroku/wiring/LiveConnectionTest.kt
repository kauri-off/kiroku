package io.github.kauri_off.kiroku.wiring

import android.net.ConnectivityManager
import android.net.Network
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.data.network.AndroidDevice
import io.github.kauri_off.kiroku.data.network.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

// Section 2.3: a connection the system will not report on is reported and taken as online, as a request takes it.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LiveConnectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reported = mutableListOf<Throwable>()

    // Says [first] values, then throws [failure]; each collection is one registration with the system.
    private class FailingDevice(
        private val failure: Exception,
        private vararg val first: Boolean,
    ) : Device {
        val registrations = AtomicInteger()

        override fun isOnline() = true

        override fun observeOnline(): Flow<Boolean> =
            flow {
                first.forEach { emit(it) }
                throw failure
            }.onStart { registrations.incrementAndGet() }

        override fun isMainThread() = false
    }

    private fun connection(
        scope: CoroutineScope,
        device: Device,
    ): Connection =
        liveConnection(LiveParts(context, LiveSource.Account, "0123abcd", Clock.systemUTC(), scope, reported::add, device = device))

    @Test
    fun `a watcher the system refuses at once is reported and the connection is online`() =
        runTest {
            val failure = SecurityException()
            val connection = connection(backgroundScope, FailingDevice(failure))
            assertEquals(true, connection.observeOnline().first())
            assertEquals(listOf<Throwable>(failure), reported)
        }

    @Test
    fun `a watcher that fails after saying offline leaves the connection online, not offline for good`() =
        runTest {
            val failure = IllegalStateException()
            val connection = connection(backgroundScope, FailingDevice(failure, false))
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { connection.observeOnline().collect { seen += it } }
            runCurrent()
            assertEquals(listOf(false, true), seen)
            assertEquals(listOf<Throwable>(failure), reported)
        }

    @Test
    fun `the next screen to wait for the connection, after all have stopped, asks the system again`() =
        runTest {
            val device = FailingDevice(SecurityException())
            val connection = connection(backgroundScope, device)
            val one = backgroundScope.launch { connection.observeOnline().collect {} }
            runCurrent()
            one.cancel()
            runCurrent()
            assertEquals(true, connection.observeOnline().first())
            assertEquals(2, device.registrations.get())
            assertEquals(2, reported.size)
        }

    @Test
    fun `a watcher that does not fail is not reported`() =
        runTest {
            val device =
                object : Device {
                    val online = MutableStateFlow(false)

                    override fun isOnline() = online.value

                    override fun observeOnline(): Flow<Boolean> = online

                    override fun isMainThread() = false
                }
            assertEquals(false, connection(backgroundScope, device).observeOnline().first())
            assertEquals(emptyList<Throwable>(), reported)
        }

    @Test
    @Config(shadows = [RefusingConnectivity::class])
    fun `the device's own watcher, refused by the system, is reported and the connection ends online`() =
        runTest {
            val connection = connection(backgroundScope, AndroidDevice(context))
            connection.observeOnline().first { it }
            assertTrue(reported.toString(), reported.single() is SecurityException)
        }

    @Test
    @Config(shadows = [SilentConnectivity::class])
    fun `the device's own watcher, whose first look at the network throws, is reported and the connection is online`() =
        runTest {
            val connection = connection(backgroundScope, AndroidDevice(context))
            assertEquals(true, connection.observeOnline().first())
            assertTrue(reported.toString(), reported.single() is SecurityException)
        }
}

// The system as some devices are reported to answer an app's default network callback.
@Implements(ConnectivityManager::class)
class RefusingConnectivity : ShadowConnectivityManager() {
    @Implementation
    override fun registerDefaultNetworkCallback(networkCallback: ConnectivityManager.NetworkCallback): Unit = throw SecurityException()
}

@Implements(ConnectivityManager::class)
class SilentConnectivity : ShadowConnectivityManager() {
    @Implementation
    override fun getActiveNetwork(): Network = throw SecurityException()
}
