package com.reson.player.data.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reson.player.ResonApplication
import com.reson.player.domain.NasPaths
import kotlinx.coroutines.CancellationException

class OfflineWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(OfflineDownloads.TASK_KEY)?.takeIf(NasPaths::validId)
            ?: return Result.failure()
        val periodic = inputData.getBoolean(OfflineDownloads.PERIODIC_KEY, false)
        val offline = (applicationContext as ResonApplication).offline
        return try {
            when (offline.transfer(id)) {
                TransferOutcome.Complete -> Result.success()
                TransferOutcome.Continue -> Result.retry()
                TransferOutcome.RetryNas -> when {
                    runAttemptCount < MAX_NAS_RETRIES -> Result.retry()
                    periodic -> Result.success()
                    else -> Result.failure()
                }
                TransferOutcome.Failed -> if (periodic) Result.success() else Result.failure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            Result.failure()
        }
    }

    private companion object {
        const val MAX_NAS_RETRIES = 2
    }
}
