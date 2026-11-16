package com.reson.player.data

import android.net.InetAddresses
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN
import com.hierynomus.mssmb2.SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.reson.player.domain.LocalTrackMetadata
import com.reson.player.domain.NasClient
import com.reson.player.domain.NasEntry
import com.reson.player.domain.NasReadShare
import com.reson.player.domain.isPlaylistFile
import com.reson.player.domain.NasException
import com.reson.player.domain.NasFile
import com.reson.player.domain.NasFileRevision
import com.reson.player.domain.NasLogin
import com.reson.player.domain.NasPaths
import com.reson.player.domain.NasProblem
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class SmbNasClient(private val lan: LocalNasNetwork) : NasClient {
    override fun test(login: NasLogin) = smbOperation {
        connect(login).use { transport ->
            if (login.profile.share.isNotBlank()) {
                transport.disk(login.profile.share).openReadOnlyDirectory("").use { }
            }
        }
    }

    override fun shares(login: NasLogin): List<String> = smbOperation {
        connect(login).use { transport ->
            val rpc = SMBTransportFactories.SRVSVC.getTransport(transport.session)
            val shares = try {
                ServerService(rpc).shares1
            } catch (error: NoClassDefFoundError) {
                // DCE/RPC can throw a desktop-only exception when rejecting a malformed reply.
                if (error.message?.contains("java/rmi/UnmarshalException") != true) throw error
                throw NasException(NasProblem.Unreachable, error)
            }
            shares.filter { it.type == 0 && NasPaths.validComponent(it.netName) }
                .map { it.netName }
                .distinct()
                .sortedBy { it.lowercase() }
        }
    }

    override fun browse(login: NasLogin, folder: List<String>): List<NasEntry> =
        readShare(login) { it.browse(folder) }

    override fun <T> readShare(login: NasLogin, action: (NasReadShare) -> T): T =
        smbOperation { connect(login) }.use { transport ->
            val disk = smbOperation { transport.disk(login.profile.share) }
            action(object : NasReadShare {
                override fun browse(folder: List<String>): List<NasEntry> = smbOperation { disk.browseFolder(folder) }

                override fun open(path: List<String>): NasFile = openTrack(path, SMB2ShareAccess.ALL)

                override fun openForDownload(path: List<String>): NasFile =
                    openTrack(path, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ))

                private fun openTrack(path: List<String>, shareAccess: Set<SMB2ShareAccess>): NasFile =
                    smbOperation {
                        val file = disk.openTrack(path, shareAccess)
                        try {
                            SmbFile(file, file.length)
                        } catch (error: Exception) {
                            runCatching { file.close() }
                            throw error
                        }
                    }
            })
        }

    private fun DiskShare.browseFolder(folder: List<String>): List<NasEntry> =
        openReadOnlyDirectory(sharePath(folder)).use { directory ->
            directory.list().mapNotNull { entry ->
                val name = entry.fileName
                val flags = entry.fileAttributes
                if (!NasPaths.validComponent(name) ||
                    (flags and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value) != 0L
                ) return@mapNotNull null
                val isDirectory = (flags and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                if (isDirectory || LocalTrackMetadata.supports(name, isMusic = false) || isPlaylistFile(name)) {
                    NasEntry(name, isDirectory, entry.endOfFile, entry.lastWriteTime.windowsTimeStamp)
                } else {
                    null
                }
            }.sortedWith(compareBy<NasEntry> { !it.directory }.thenBy { it.name.lowercase() }.thenBy { it.name })
        }

    override fun open(login: NasLogin, path: List<String>): NasFile = smbOperation {
        val transport = connect(login)
        try {
            val file = transport.disk(login.profile.share).openTrack(path)
            SmbFile(file, file.length, transport::close)
        } catch (error: Exception) {
            transport.close()
            throw error
        }
    }

    private fun DiskShare.openTrack(
        path: List<String>,
        shareAccess: Set<SMB2ShareAccess> = SMB2ShareAccess.ALL,
    ): File {
        if (path.isEmpty() || !LocalTrackMetadata.supports(path.last(), isMusic = false)) {
            throw NasException(NasProblem.Invalid)
        }
        return openFile(
            sharePath(path),
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            shareAccess,
            FILE_OPEN,
            EnumSet.of(FILE_OPEN_REPARSE_POINT),
        )
    }

    private fun connect(login: NasLogin): Transport {
        val profile = login.profile
        val endpoint = lan.resolve(profile.host, profile.port)
        val sockets = PinnedSocketFactory(endpoint)
        val config = SmbConfig.builder()
            .withSocketFactory(sockets)
            .withAuthenticators(NtlmAuthenticator.Factory())
            .withSigningRequired(true)
            .withEncryptData(true)
            .withDfsEnabled(false)
            .withTimeout(7, TimeUnit.SECONDS)
            .withReadBufferSize(128 * 1024)
            .build()
        val client = SMBClient(config)
        try {
            val connection = client.connect(requireNotNull(endpoint.address.hostAddress), endpoint.port)
            val password = login.password.toCharArray()
            val session = try {
                connection.authenticate(AuthenticationContext(profile.username, password, profile.domain))
            } finally {
                password.fill('\u0000')
            }
            return Transport(client, sockets, session)
        } catch (error: Exception) {
            sockets.close()
            runCatching { client.close() }
            throw error
        }
    }

    private fun sharePath(components: List<String>): String {
        if (components.any { !NasPaths.validComponent(it) }) throw NasException(NasProblem.Invalid)
        return components.joinToString("\\")
    }

    private fun DiskShare.openReadOnlyDirectory(path: String) = openDirectory(
        path,
        EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
        null,
        SMB2ShareAccess.ALL,
        FILE_OPEN,
        EnumSet.of(FILE_OPEN_REPARSE_POINT),
    )

    private class Transport(
        private val client: SMBClient,
        private val sockets: PinnedSocketFactory,
        val session: Session,
    ) : Closeable {
        fun disk(share: String): DiskShare {
            if (!NasPaths.validComponent(share)) throw NasException(NasProblem.Invalid)
            return session.connectShare(share) as? DiskShare ?: throw NasException(NasProblem.Missing)
        }

        override fun close() {
            try {
                runCatching { client.close() }
            } finally {
                sockets.close()
            }
        }
    }

    private class SmbFile(
        private val file: File,
        override val size: Long,
        private val closeTransport: () -> Unit = {},
    ) : NasFile {
        override fun revision(): NasFileRevision = smbOperation {
            val info = file.fileInformation
            NasFileRevision(info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.windowsTimeStamp)
        }

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = smbOperation {
            file.read(buffer, position, offset, minOf(length, 64 * 1024))
        }

        override fun close() {
            try {
                runCatching { file.close() }
            } finally {
                closeTransport()
            }
        }
    }

    private class PinnedSocketFactory(private val endpoint: LocalNasNetwork.Endpoint) : SocketFactory() {
        private var opened: Socket? = null

        override fun createSocket(host: String, port: Int): Socket {
            if (port != endpoint.port || !InetAddresses.isNumericAddress(host) ||
                !InetAddresses.parseNumericAddress(host).address.contentEquals(endpoint.address.address) ||
                opened != null
            ) throw NasException(NasProblem.NonLocal)
            val socket = endpoint.network.socketFactory.createSocket()
            opened = socket
            return try {
                socket.connect(InetSocketAddress(endpoint.address, port), 4000)
                socket
            } catch (error: Exception) {
                close()
                throw error
            }
        }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            createSocket(host.hostAddress ?: throw IOException("Missing address"), port)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            throw NasException(NasProblem.NonLocal)

        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
            throw NasException(NasProblem.NonLocal)

        fun close() {
            runCatching { opened?.close() }
        }
    }
}

private inline fun <T> smbOperation(block: () -> T): T = try {
    block()
} catch (error: Exception) {
    throw NasException(classifySmbError(error))
}

internal fun classifySmbError(error: Throwable): NasProblem {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<NasException>().firstOrNull()?.let { return it.problem }
    if (causes.any { it is SocketException && it.message?.startsWith("socket failed: EPERM") == true }) {
        return NasProblem.SocketBlocked
    }
    val status = causes.filterIsInstance<SMBApiException>().firstOrNull()?.status
    return when (status) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED ->
            NasProblem.Authentication
        NtStatus.STATUS_ACCESS_DENIED -> NasProblem.Access
        NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_NO_SUCH_FILE,
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> NasProblem.Missing
        else -> NasProblem.Unreachable
    }
}
