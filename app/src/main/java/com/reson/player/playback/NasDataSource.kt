package com.reson.player.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.reson.player.domain.NasClient
import com.reson.player.domain.NasException
import com.reson.player.domain.NasFile
import com.reson.player.domain.NasPaths
import com.reson.player.domain.NasProblem
import com.reson.player.domain.NasProfiles
import com.reson.player.domain.sourceId
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal object NasTrackUri {
    const val SCHEME = "reson-nas"

    fun create(id: String, share: String, path: List<String>): Uri =
        Uri.Builder().scheme(SCHEME).authority(id).appendPath(share).apply {
            path.forEach(::appendPath)
        }.build()

    fun accepts(uri: Uri): Boolean = acceptsFolder(uri) && uri.pathSegments.size >= 2

    fun acceptsFolder(uri: Uri): Boolean = uri.scheme == SCHEME &&
        uri.encodedAuthority?.let(NasPaths::validId) == true &&
        uri.pathSegments.isNotEmpty() && uri.pathSegments.all(NasPaths::validComponent) &&
        uri.encodedQuery == null && uri.encodedFragment == null
}

@OptIn(UnstableApi::class)
internal class NasDataSource(private val profiles: NasProfiles, private val nas: NasClient) :
    BaseDataSource(true) {
    private var uri: Uri? = null
    private var range: NasReadRange? = null

    override fun open(dataSpec: DataSpec): Long {
        val target = dataSpec.uri
        if (!NasTrackUri.accepts(target)) throw NasException(NasProblem.Invalid)
        transferInitializing(dataSpec)
        val login = profiles.login(requireNotNull(target.authority))
        val segments = target.pathSegments
        if (login.profile.share != segments.first() || login.profile.sourceId() != dataSpec.key) {
            throw NasException(NasProblem.SourceChanged)
        }
        val file = nas.open(login, segments.drop(1))
        val opened = try {
            NasReadRange(file, dataSpec.position, dataSpec.length.takeUnless { it == C.LENGTH_UNSET.toLong() })
        } catch (_: PositionOutsideNasFile) {
            file.close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        } catch (error: Exception) {
            runCatching { file.close() }
            throw error
        }
        range = opened
        uri = target
        transferStarted(dataSpec)
        return opened.reportedLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = checkNotNull(range).read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val opened = range ?: return
        range = null
        uri = null
        try {
            opened.close()
        } finally {
            transferEnded()
        }
    }
}

internal class PositionOutsideNasFile : IOException()

internal class NasReadRange(
    private val file: NasFile,
    position: Long,
    requestedLength: Long?,
) : AutoCloseable {
    private var cursor = position
    private var remaining = file.size - position
    val reportedLength: Long = requestedLength ?: remaining

    init {
        if (position < 0 || position > file.size) throw PositionOutsideNasFile()
        if (requestedLength != null) remaining = minOf(requestedLength, remaining)
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val requested = minOf(length.toLong(), remaining).toInt()
        val count = file.read(cursor, buffer, offset, requested)
        if (count == -1) {
            remaining = 0
        } else if (count in 1..requested) {
            cursor += count
            remaining -= count
        } else {
            throw NasException(NasProblem.Unreachable)
        }
        return count
    }

    override fun close() = file.close()
}

@OptIn(UnstableApi::class)
internal class ResonDataSource(
    private val content: DataSource,
    private val nas: DataSource,
    private val offlineFile: (Uri, String?) -> File?,
) : DataSource {
    private val local = FileDataSource()
    private var source: DataSource? = null
    private var offlineUri: Uri? = null

    override fun addTransferListener(listener: TransferListener) {
        content.addTransferListener(listener)
        nas.addTransferListener(listener)
        local.addTransferListener(listener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = dataSpec.uri
        val localOnly = target.scheme == OfflineTrackUri.SCHEME
        if (localOnly || target.scheme == NasTrackUri.SCHEME) {
            val file = try {
                offlineFile(target, dataSpec.key)
            } catch (error: RuntimeException) {
                if (localOnly) throw IOException("Couldn’t read the downloaded copy", error)
                null
            }
            if (file != null) {
                try {
                    val length = local.open(dataSpec.withUri(file.toUri()))
                    source = local
                    offlineUri = target
                    return length
                } catch (error: IOException) {
                    runCatching { local.close() }
                    if (localOnly) throw error
                }
            }
            if (localOnly) throw FileNotFoundException("Downloaded copy is unavailable")
        }
        val selected = when (target.scheme) {
            "content" -> content
            NasTrackUri.SCHEME -> nas
            else -> throw IOException("Unsupported audio source")
        }
        source = selected
        return selected.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(source).read(buffer, offset, length)

    override fun getUri(): Uri? = offlineUri ?: source?.uri

    override fun close() {
        val selected = source
        source = null
        offlineUri = null
        selected?.close()
    }
}
