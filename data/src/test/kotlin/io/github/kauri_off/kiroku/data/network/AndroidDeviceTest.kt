package io.github.kauri_off.kiroku.data.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidDeviceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val device = AndroidDevice(context)

    private fun capabilities(vararg held: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { held.forEach(shadowOf(it)::addCapability) }

    @Test
    fun `the device is online with a network that reaches the internet`() {
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(device.isOnline())
    }

    @Test
    fun `the device is offline with a network that does not reach the internet`() {
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities())
        assertFalse(device.isOnline())
    }

    @Test
    fun `the device is offline without a network`() {
        shadowOf(connectivity).setDefaultNetworkActive(false)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        assertFalse(device.isOnline())
    }

    @Test
    fun `the connection says at once whether the device is online, then each change, as a request would judge it`() =
        runTest {
            val online = capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, online)
            val seen = mutableListOf<Boolean>()
            val watching = backgroundScope.launch { device.observeOnline().collect { seen += it } }
            runCurrent()
            val callback = shadowOf(connectivity).networkCallbacks.single()
            val network = connectivity.activeNetwork!!
            callback.onLost(network)
            runCurrent()
            callback.onCapabilitiesChanged(network, online)
            runCurrent()
            callback.onCapabilitiesChanged(network, online)
            runCurrent()
            callback.onCapabilitiesChanged(network, capabilities())
            runCurrent()
            assertEquals(listOf(true, false, true, false), seen)
            watching.cancel()
            runCurrent()
            assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
        }

    // The system names no active network to a blocked app, as with a lockdown VPN reconnecting, and tells its callbacks.
    @Test
    fun `a blocked network is offline to the connection and to a request alike`() =
        runTest {
            val network = connectivity.activeNetwork!!
            val online = capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(connectivity).setNetworkCapabilities(network, online)
            val info = connectivity.activeNetworkInfo
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { device.observeOnline().collect { seen += it } }
            runCurrent()
            val callback = shadowOf(connectivity).networkCallbacks.single()
            callback.onCapabilitiesChanged(network, online)
            runCurrent()

            shadowOf(connectivity).setActiveNetworkInfo(null)
            callback.onBlockedStatusChanged(network, true)
            runCurrent()
            assertFalse(device.isOnline())
            assertEquals(listOf(true, false), seen)

            shadowOf(connectivity).setActiveNetworkInfo(info)
            callback.onBlockedStatusChanged(network, false)
            runCurrent()
            assertTrue(device.isOnline())
            assertEquals(listOf(true, false, true), seen)
        }

    @Test
    fun `the connection starts offline without a network`() =
        runTest {
            shadowOf(connectivity).setDefaultNetworkActive(false)
            shadowOf(connectivity).setActiveNetworkInfo(null)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { device.observeOnline().collect { seen += it } }
            runCurrent()
            assertEquals(listOf(false), seen)
        }

    @Test
    fun `the main thread is told from another`() {
        assertTrue(device.isMainThread())
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertFalse(executor.submit(device::isMainThread).get())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `the library asks for the network, as OkHttp declares, and for its state`() {
        val asked =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
                .orEmpty()
        assertTrue(Manifest.permission.INTERNET in asked)
        assertTrue(Manifest.permission.ACCESS_NETWORK_STATE in asked)
    }
}
