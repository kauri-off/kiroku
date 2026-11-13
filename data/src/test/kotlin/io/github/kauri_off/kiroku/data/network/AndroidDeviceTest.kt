package io.github.kauri_off.kiroku.data.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.Executors

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
