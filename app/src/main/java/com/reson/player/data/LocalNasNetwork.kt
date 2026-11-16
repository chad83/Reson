package com.reson.player.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.reson.player.domain.NasException
import com.reson.player.domain.NasPaths
import com.reson.player.domain.NasProblem
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.UnknownHostException

class LocalNasNetwork(private val context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    data class Endpoint(val network: Network, val address: InetAddress, val port: Int)

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
        PackageManager.PERMISSION_GRANTED

    fun requireAccess() {
        if (!hasPermission()) throw NasException(NasProblem.Permission)
        if (localNetworks().isEmpty()) throw NasException(NasProblem.NoLan)
    }

    fun resolve(host: String, port: Int): Endpoint {
        if (!hasPermission()) throw NasException(NasProblem.Permission)
        if (port !in 1..65535) throw NasException(NasProblem.Invalid)
        val numeric = if (InetAddresses.isNumericAddress(host)) InetAddresses.parseNumericAddress(host) else null
        if (numeric != null && !NasPaths.isLocalAddress(numeric)) throw NasException(NasProblem.NonLocal)
        if (numeric == null && !NasPaths.validHostName(host)) throw NasException(NasProblem.Invalid)
        val networks = localNetworks()
        if (networks.isEmpty()) throw NasException(NasProblem.NoLan)

        var resolved = numeric != null
        for ((network, links) in networks) {
            val addresses = if (numeric != null) listOf(numeric) else resolveLocally(network, links, host)
            if (addresses.isNotEmpty()) resolved = true
            val address = addresses.sortedBy { it is Inet6Address }
                .firstOrNull { isOnLink(links, it) } ?: continue
            val scoped = if (address is Inet6Address && address.isLinkLocalAddress) {
                val networkInterface = links.interfaceName?.let(NetworkInterface::getByName) ?: continue
                Inet6Address.getByAddress(null, address.address, networkInterface)
            } else {
                address
            }
            return Endpoint(network, scoped, port)
        }
        throw NasException(if (resolved) NasProblem.NonLocal else NasProblem.Unresolved)
    }

    internal data class LocalIpv4(val network: Network, val address: Inet4Address)

    internal fun localIpv4(peer: InetAddress? = null): LocalIpv4? {
        requireAccess()
        return localNetworks().firstNotNullOfOrNull { (network, links) ->
            if (peer != null && !isOnLink(links, peer)) return@firstNotNullOfOrNull null
            links.linkAddresses.asSequence().mapNotNull { it.address as? Inet4Address }
                .firstOrNull(NasPaths::isLocalAddress)?.let { LocalIpv4(network, it) }
        }
    }

    internal fun isAvailable(endpoint: LocalIpv4): Boolean =
        hasPermission() && manager.getLinkProperties(endpoint.network)?.linkAddresses
            ?.any { it.address == endpoint.address } == true

    fun accepts(address: InetAddress): Boolean =
        localNetworks().any { (_, links) -> isOnLink(links, address) }

    internal fun accepts(endpoint: LocalIpv4, address: InetAddress): Boolean =
        hasPermission() && manager.getLinkProperties(endpoint.network)?.let {
            it.linkAddresses.any { link -> link.address == endpoint.address } && isOnLink(it, address)
        } == true

    internal fun watchLocalNetwork(callback: ConnectivityManager.NetworkCallback) {
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(),
            callback,
        )
    }

    internal fun unwatchLocalNetwork(callback: ConnectivityManager.NetworkCallback) {
        manager.unregisterNetworkCallback(callback)
    }

    private fun resolveLocally(network: Network, links: LinkProperties, host: String): List<InetAddress> {
        if (links.isPrivateDnsActive || links.dnsServers.isEmpty() ||
            links.dnsServers.any { !isOnLink(links, it) }
        ) {
            return emptyList()
        }
        val domains = links.domains.orEmpty().split(' ', ',')
        val names = if ('.' in host) listOf(host) else {
            listOf(host) + domains.filter(String::isNotBlank).map { "$host.${it.trimEnd('.')}" }
                .filter(NasPaths::validHostName)
        }
        for (name in names.distinct()) {
            try {
                return network.getAllByName("$name.").toList()
            } catch (_: UnknownHostException) {
                // Try the next local search domain.
            }
        }
        return emptyList()
    }

    @Suppress("DEPRECATION")
    private fun localNetworks(): List<Pair<Network, LinkProperties>> =
        (listOfNotNull(manager.activeNetwork) + manager.allNetworks).distinct().mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) {
                return@mapNotNull null
            }
            manager.getLinkProperties(network)?.let { network to it }
        }

    private fun isOnLink(links: LinkProperties, address: InetAddress): Boolean =
        NasPaths.isLocalAddress(address) && links.linkAddresses.any {
            NasPaths.sameSubnet(address, it.address, it.prefixLength)
        }
}
