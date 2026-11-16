package com.reson.player.data.library

import android.annotation.SuppressLint
import android.content.Context
import com.reson.player.domain.NasFile
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private const val FREE_SPACE_RESERVE_BYTES = 128L * 1024 * 1024
private const val SPACE_CHECK_INTERVAL_BYTES = 4L * 1024 * 1024

private val audioName = Regex("[a-f0-9]{64}_[a-f0-9]{64}\\.audio")

internal fun offlineDirectory(context: Context): File = File(context.noBackupFilesDir, "offline-music")

internal fun offlineFile(directory: File, name: String, size: Long): File? =
    if (audioName.matches(name) && size > 0) {
        File(directory, name).takeIf { it.isFile && it.length() == size }
    } else null

// Use physically free bytes so the reserve does not depend on evicting other apps' caches.
@SuppressLint("UsableSpace")
internal fun checkDownloadSpace(directory: File, remaining: Long) {
    if (directory.usableSpace - FREE_SPACE_RESERVE_BYTES < remaining) {
        throw IOException("Not enough storage for download")
    }
}

/** Resumes a partial copy of a single known file revision. The caller verifies and publishes it. */
internal fun copyToPartial(
    input: NasFile,
    expectedSize: Long,
    partial: File,
    checkSpace: (remaining: Long) -> Unit = {},
    onProgress: (Long) -> Unit = {},
    checkActive: () -> Unit,
) {
    if (expectedSize <= 0) throw IOException("The NAS file is empty")
    RandomAccessFile(partial, "rw").use { output ->
        if (output.length() > expectedSize) output.setLength(0)
        var position = output.length()
        onProgress(position)
        output.seek(position)
        val buffer = ByteArray(64 * 1024)
        var nextSpaceCheck = position
        while (position < expectedSize) {
            checkActive()
            if (position >= nextSpaceCheck) {
                checkSpace(expectedSize - position)
                nextSpaceCheck = position + SPACE_CHECK_INTERVAL_BYTES
            }
            val length = minOf(buffer.size.toLong(), expectedSize - position).toInt()
            val count = input.read(position, buffer, 0, length)
            if (count !in 1..length) throw EOFException("The NAS file ended before the download was complete")
            output.write(buffer, 0, count)
            position += count
            onProgress(position)
        }
        output.fd.sync()
    }
}
