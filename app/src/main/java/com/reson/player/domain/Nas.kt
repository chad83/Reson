package com.reson.player.domain

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID

data class NasProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val username: String,
    val domain: String = "",
    val folder: List<String> = emptyList(),
)

class NasLogin(val profile: NasProfile, val password: String)

data class NasDevice(val name: String, val address: String, val port: Int)

data class NasEntry(
    val name: String,
    val directory: Boolean,
    val size: Long = 0,
    val lastWriteTime: Long = 0,
) {
    val playlist: Boolean get() = !directory && isPlaylistFile(name)
}

internal fun isPlaylistFile(name: String): Boolean =
    name.endsWith(".m3u", ignoreCase = true) || name.endsWith(".m3u8", ignoreCase = true)

interface NasProfiles {
    fun list(): List<NasProfile>
    fun login(id: String): NasLogin
    fun save(login: NasLogin)
    fun remove(id: String)
}

interface NasClient {
    fun test(login: NasLogin)
    fun shares(login: NasLogin): List<String>
    fun browse(login: NasLogin, folder: List<String>): List<NasEntry>
    fun open(login: NasLogin, path: List<String>): NasFile
    fun <T> readShare(login: NasLogin, action: (NasReadShare) -> T): T
}

interface NasReadShare {
    fun browse(folder: List<String>): List<NasEntry>
    fun open(path: List<String>): NasFile
    fun openForDownload(path: List<String>): NasFile = open(path)
}

interface NasFile : Closeable {
    val size: Long
    fun revision(): NasFileRevision = NasFileRevision(size, 0)
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

data class NasFileRevision(val size: Long, val lastWriteTime: Long) {
    val stamp: String get() = "$lastWriteTime:$size"
}

internal val NasEntry.revision: NasFileRevision get() = NasFileRevision(size, lastWriteTime)

internal fun NasProfile.sourceId(): String {
    val location = listOf(host, port, share, username, domain).joinToString("\u0000")
    return "$id:${sha256(location)}"
}

internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).toHexString()

internal inline fun NasReadShare.walkAudio(
    root: List<String>,
    checkActive: () -> Unit,
    visit: (List<String>, NasEntry) -> Unit,
) {
    val folders = ArrayDeque<List<String>>()
    folders.add(root)
    while (folders.isNotEmpty()) {
        checkActive()
        val folder = folders.removeLast()
        for (entry in browse(folder)) {
            checkActive()
            val path = folder + entry.name
            if (entry.directory) folders.add(path) else if (!entry.playlist) visit(path, entry)
        }
    }
}

interface NasDiscovery {
    suspend fun discover(): List<NasDevice>
}

enum class NasProblem(val description: String) {
    Permission("Allow local network access to connect to your NAS."),
    SocketBlocked("Android blocked the network connection. Check local network access or restart the device, then retry."),
    NoLan("Connect to the NAS’s local Wi-Fi or Ethernet network."),
    NonLocal("This address is outside the directly connected local network."),
    Unresolved("Couldn’t find that local hostname. Try the NAS’s local IP address."),
    Unreachable("Couldn’t reach the NAS. Check its address, SMB settings, and Wi-Fi, then retry."),
    Authentication("Sign-in failed. Check the NAS username, password, and domain."),
    Access("Access denied. Check the account’s read permissions for this share."),
    Missing("The share, folder, or track could not be found."),
    Invalid("Enter a local IP address or hostname, a valid SMB port, and a valid share name."),
    SourceChanged("This track belongs to a different NAS connection. Select it again from the library."),
    SavedConnection("Couldn’t read or update the saved connection. Try entering it again."),
}

internal val NasProblem.isConnectionFailure: Boolean
    get() = this == NasProblem.Unreachable || this == NasProblem.Unresolved ||
        this == NasProblem.Authentication || this == NasProblem.SocketBlocked

class NasException(val problem: NasProblem, cause: Throwable? = null) : IOException(problem.description, cause)

object NasPaths {
    fun validComponent(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            value.none { it < ' ' || it in "/\\:*?\"<>|" }

    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun validHostName(host: String): Boolean {
        if (host.length > 253 || host.isBlank()) return false
        val labels = host.split('.')
        if (labels.any { !HOST_LABEL.matches(it) }) return false
        return labels.size == 1 || host.endsWith(".local", true) ||
            host.endsWith(".lan", true) || host.endsWith(".home", true) ||
            host.endsWith(".home.arpa", true)
    }

    private val HOST_LABEL = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")

    fun isLocalAddress(address: InetAddress): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return when (bytes.size) {
            4 -> first == 10 || (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) || (first == 169 && second == 254)
            16 -> (first and 0xfe) == 0xfc || address.isLinkLocalAddress
            else -> false
        }
    }

    fun sameSubnet(address: InetAddress, local: InetAddress, prefixLength: Int): Boolean {
        val addressBytes = address.address
        val localBytes = local.address
        if (addressBytes.size != localBytes.size || prefixLength !in 1..(localBytes.size * 8)) {
            return false
        }
        val completeBytes = prefixLength / 8
        for (index in 0 until completeBytes) {
            if (addressBytes[index] != localBytes[index]) return false
        }
        val partialBits = prefixLength % 8
        if (partialBits == 0) return true
        val mask = (0xff shl (8 - partialBits)) and 0xff
        return (addressBytes[completeBytes].toInt() and mask) == (localBytes[completeBytes].toInt() and mask)
    }
}
