package com.reson.player.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import com.reson.player.domain.NasDevice
import com.reson.player.domain.NasDiscovery
import com.reson.player.domain.NasException
import com.reson.player.domain.NasProblem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MdnsNasDiscovery(context: Context, private val lan: LocalNasNetwork) : NasDiscovery {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)

    override suspend fun discover(): List<NasDevice> {
        lan.requireAccess()
        val lock = wifi?.takeUnless { modernNsd() }?.createMulticastLock("reson-smb-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val devices = linkedMapOf<Pair<String, Int>, NasDevice>()
        try {
            withTimeoutOrNull(6500) {
                services().collect { service ->
                    val resolved = resolve(service) ?: return@collect
                    @Suppress("DEPRECATION")
                    val address = resolved.host ?: return@collect
                    if (resolved.port !in 1..65535 || !lan.accepts(address)) return@collect
                    val host = address.hostAddress ?: return@collect
                    devices[host to resolved.port] = NasDevice(resolved.serviceName, host, resolved.port)
                }
            }
        } finally {
            lock?.release()
        }
        return devices.values.sortedBy { it.name.lowercase() }
    }

    private fun services() = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                trySend(service)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(NasException(NasProblem.Unreachable))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        manager.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
    }.buffer(Channel.BUFFERED)

    @Suppress("DEPRECATION")
    private suspend fun resolve(service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(serviceInfo)
                }
            }
            manager.resolveService(service, listener)
            continuation.invokeOnCancellation {
                if (Build.VERSION.SDK_INT >= 34) {
                    runCatching { manager.stopServiceResolution(listener) }
                }
            }
        }

    private fun modernNsd(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
}
