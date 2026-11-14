package io.github.kauri_off.kiroku.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import io.github.kauri_off.kiroku.core.repository.Connection
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

// What the network layer asks of the device it runs on.
interface Device {
    fun isOnline(): Boolean

    fun isMainThread(): Boolean
}

// The request's "No connection" and the screens' connection are one judgement: a default network that reaches the internet.
class AndroidDevice(
    context: Context,
) : Device,
    Connection {
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }

    override fun isOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network).reachesInternet()
    }

    override fun observeOnline(): Flow<Boolean> =
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        trySend(capabilities.reachesInternet())
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }
                }
            trySend(isOnline())
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }.conflate().distinctUntilChanged()

    override fun isMainThread(): Boolean = Looper.getMainLooper().isCurrentThread

    private fun NetworkCapabilities?.reachesInternet(): Boolean = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
