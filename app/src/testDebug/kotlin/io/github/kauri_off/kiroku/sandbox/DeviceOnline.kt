package io.github.kauri_off.kiroku.sandbox

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

// The test's device with a network that reaches the internet, which the sandbox answers only over, until [online] says otherwise.
class DeviceOnline : ExternalResource() {
    override fun before() = online(true)

    fun online(online: Boolean) {
        val connectivity = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        if (online) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
    }
}
