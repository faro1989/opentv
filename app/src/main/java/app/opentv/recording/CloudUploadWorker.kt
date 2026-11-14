/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.opentv.core.ServiceLocator
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads a finished CLOUD recording from the local staging folder to the user's WebDAV cloud,
 * then points the recording row at its cloud locator so the library shows — and plays — it from
 * there. Runs under WorkManager: it waits for a network, survives the app being closed or the box
 * rebooting, and retries with back-off if the upload fails (the local file stays playable meanwhile).
 */
class CloudUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_ID, -1L)
        val graph = ServiceLocator.get(applicationContext)
        val recording = graph.recordingRepository.byId(id) ?: return@withContext Result.success()
        val file = File(recording.filePath)
        if (!file.exists() || CloudClient.isCloud(recording.filePath)) return@withContext Result.success()

        val settings = graph.settings
        if (app.opentv.vpn.VpnController.get(applicationContext).isBlocked()) return@withContext Result.retry()
        try {
            val locator = CloudClient.upload(CloudClient.config(settings), file)
            graph.recordingRepository.setFilePath(id, locator)
            if (!settings.cloudKeepLocal.value) file.delete()
            Log.i(TAG, "Recording $id uploaded to $locator")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Upload of recording $id failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "CloudUpload"
        private const val KEY_ID = "recording_id"
        private const val MAX_ATTEMPTS = 8

        /**
         * Queue the upload. Respects "Wi-Fi only" (waits for an unmetered network) and, unless
         * [force] (the "sync now" button), "upload recordings automatically".
         */
        fun enqueue(context: Context, recordingId: Long, force: Boolean = false) {
            val settings = ServiceLocator.get(context).settings
            if (!force && !settings.cloudAutoUpload.value) return
            val network = if (settings.syncWifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
                .setInputData(workDataOf(KEY_ID to recordingId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("cloud-upload-$recordingId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
