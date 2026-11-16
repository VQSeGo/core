package com.maxrave.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.maxrave.logger.Logger
import org.koin.mp.KoinPlatform.getKoin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * "No network" here means no network under the VPN either. A VPN tunnel stays up in airplane mode
 * (seen 2026-11-16 with a non-bypassable one on the phone): the active network is still the VPN, still
 * VALIDATED, only its transports shrink to VPN alone, and every request in it waits out a DNS timeout.
 * So the device counts as offline only when no non-VPN network with INTERNET is listed AND the default
 * network has no transport but VPN. Either side seeing a real link keeps it online: a wrong "offline"
 * would refuse every stream, a wrong "online" only costs the timeout it always did.
 */
actual fun watchDeviceNetwork(onChange: (hasNetwork: Boolean) -> Unit) {
    val context: Context = getKoin().get()
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return
    // Non-VPN networks with INTERNET, as far as the app is told about them (a non-bypassable VPN may
    // keep them from it: then this stays empty and the default network below decides alone).
    val links = ConcurrentHashMap.newKeySet<Network>()
    // Whether the app's default network (the VPN, when one is up) runs over a real link.
    val defaultHasLink = AtomicBoolean(activeHasLink(connectivityManager))
    val last = AtomicReference<Boolean?>(null)

    fun report() {
        val hasNetwork = links.isNotEmpty() || defaultHasLink.get()
        if (last.getAndSet(hasNetwork) != hasNetwork) {
            Logger.i("Offline", "device network ${if (hasNetwork) "up" else "down"} (links=${links.size})")
            onChange(hasNetwork)
        }
    }

    val linkRequest =
        NetworkRequest
            .Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    try {
        connectivityManager.registerNetworkCallback(
            linkRequest,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    links += network
                    report()
                }

                override fun onLost(network: Network) {
                    links -= network
                    report()
                }
            },
        )
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    defaultHasLink.set(networkCapabilities.hasLinkTransport())
                    report()
                }

                override fun onLost(network: Network) {
                    defaultHasLink.set(false)
                    report()
                }
            },
        )
        report()
    } catch (e: RuntimeException) {
        // SecurityException on some devices, TooManyRequestsException past the per-app callback limit.
        Logger.w("Offline", "device network not watched: ${e.message}")
    }
}

private fun activeHasLink(connectivityManager: ConnectivityManager): Boolean =
    try {
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)?.hasLinkTransport() == true
    } catch (e: SecurityException) {
        // Some devices throw from these calls (b/163342798): no verdict, so not offline.
        true
    }

private fun NetworkCapabilities.hasLinkTransport(): Boolean = LINK_TRANSPORTS.any { hasTransport(it) }

private val LINK_TRANSPORTS =
    intArrayOf(
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_CELLULAR,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_BLUETOOTH,
        NetworkCapabilities.TRANSPORT_USB,
    )
