package com.portionspot.pos.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Collections

/**
 * Real internet reachability, read from the system [ConnectivityManager] instead of
 * assumed. The top-bar Wi-Fi indicator used to be hardcoded green; this backs it with
 * the truth.
 *
 * "Online" means the active network both advertises the INTERNET capability AND is
 * VALIDATED (the OS has confirmed traffic can actually get out) — so a captive-portal
 * Wi-Fi that hasn't been signed into reads as offline, which is what a cashier needs.
 */
object ConnectivityObserver {

    /** One-shot check of whether there is a validated internet connection right now. */
    fun currentlyOnline(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasInternet()
    }

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    /**
     * Live online/offline as a Compose [State]. Registers a [ConnectivityManager.NetworkCallback]
     * for the life of the composition (unregistered in onDispose), seeded with the current
     * state so the first frame is already correct. A set of validated networks is tracked so
     * that losing one interface (e.g. Wi-Fi) while another (mobile data) still works keeps the
     * indicator online.
     */
    @Composable
    fun rememberOnlineState(): State<Boolean> {
        val context = LocalContext.current
        val state = remember { mutableStateOf(currentlyOnline(context)) }
        DisposableEffect(Unit) {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val validated = Collections.synchronizedSet(HashSet<Network>())
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasInternet()) validated.add(network) else validated.remove(network)
                    state.value = validated.isNotEmpty()
                }

                override fun onLost(network: Network) {
                    validated.remove(network)
                    state.value = validated.isNotEmpty()
                }

                override fun onUnavailable() {
                    state.value = false
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            var registered = false
            try {
                cm?.registerNetworkCallback(request, callback)
                registered = true
            } catch (_: Exception) {
                // registerNetworkCallback can throw on some OEM/emulator states; fall back
                // to the seeded one-shot value rather than crashing.
            }
            // Re-seed in case connectivity changed between remember and registration.
            state.value = currentlyOnline(context)
            onDispose {
                if (registered) {
                    try {
                        cm?.unregisterNetworkCallback(callback)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return state
    }
}
