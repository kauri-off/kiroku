package io.github.kauri_off.kiroku.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper

// What the network layer asks of the device it runs on.
interface Device {
    fun isOnline(): Boolean

    fun isMainThread(): Boolean
}

class AndroidDevice(
    context: Context,
) : Device {
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }

    override fun isOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun isMainThread(): Boolean = Looper.getMainLooper().isCurrentThread
}
