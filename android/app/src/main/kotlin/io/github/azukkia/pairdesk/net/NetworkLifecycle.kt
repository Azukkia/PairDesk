package io.github.azukkia.pairdesk.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Android glue of [PairDeskNetwork]: keeps the network running while the app
 * is in the foreground (sessions hold it on their own) and retries the relays
 * as soon as the device's connectivity comes back.
 */
class NetworkLifecycle(
    private val context: Context,
    private val network: PairDeskNetwork,
) : DefaultLifecycleObserver {

    /** Must be called on the main thread (Application.onCreate). */
    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(net: Network) {
                    network.onNetworkAvailable()
                }
            })
        } catch (e: RuntimeException) {
            // SecurityException on some OEM builds, or too many callbacks: the
            // transports still retry on their own schedule.
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        network.acquire(FOREGROUND)
    }

    override fun onStop(owner: LifecycleOwner) {
        network.release(FOREGROUND)
    }

    companion object {
        const val FOREGROUND = "foreground"
    }
}
