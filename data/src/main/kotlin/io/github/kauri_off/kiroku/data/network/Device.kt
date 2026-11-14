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

// What the network layer asks of the device it runs on; its connection is the screens' too, so the two agree.
interface Device : Connection {
    fun isOnline(): Boolean

    fun isMainThread(): Boolean
}

class AndroidDevice(
    context: Context,
) : Device {
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }

    // The system names no active network for an app whose default network is blocked.
    override fun isOnline(): Boolean {
        val network = connectivity.activeNetwork
        return online(network?.let(connectivity::getNetworkCapabilities), blocked = network == null)
    }

    override fun observeOnline(): Flow<Boolean> =
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    private var capabilities: NetworkCapabilities? = null
                    private var blocked = false

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        this.capabilities = capabilities
                        trySend(online(capabilities, blocked))
                    }

                    override fun onBlockedStatusChanged(
                        network: Network,
                        blocked: Boolean,
                    ) {
                        this.blocked = blocked
                        trySend(online(capabilities, blocked))
                    }

                    override fun onLost(network: Network) {
                        capabilities = null
                        blocked = false
                        trySend(false)
                    }
                }
            trySend(isOnline())
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }.conflate().distinctUntilChanged()

    override fun isMainThread(): Boolean = Looper.getMainLooper().isCurrentThread
}

// Online: a default network that reaches the internet and is not blocked for the app.
private fun online(
    capabilities: NetworkCapabilities?,
    blocked: Boolean,
): Boolean = !blocked && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
