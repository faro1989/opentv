/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.core.ServiceLocator
import app.opentv.recording.CloudClient
import app.opentv.recording.CloudUploadWorker
import app.opentv.recording.RecordingStorage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cross-device sync through the user's OWN cloud — the server-less answer to a "Sync ID" account.
 *
 * Exactly like [NasSync], but over WebDAV: every device signed in to the same cloud drops its
 * [SyncBundle] as `<folder>/sync/<deviceId>.json` and merges everyone else's through [SyncEngine].
 * Favourites, hidden channels, watch positions and cloud recordings converge on every box, phone
 * and tablet that uses the same cloud login — with no account at OpenTV and no server of ours.
 */
class CloudSync(private val graph: ServiceLocator.Graph) {

    private val engine = SyncEngine(graph)

    sealed interface Result {
        data class Success(val merged: Int, val peers: Int, val uploadsQueued: Int) : Result
        data object NotConfigured : Result
        data class Failed(val message: String?) : Result
    }

    /** Push this device's bundle, merge the others, and queue any recordings still waiting to upload. */
    suspend fun sync(context: Context): Result = withContext(Dispatchers.IO) {
        val settings = graph.settings
        if (!settings.cloudConfigured) return@withContext Result.NotConfigured
        val cfg = CloudClient.config(settings)
        val outcome = runCatching {
            val own = "${settings.syncDeviceId}.json"
            CloudClient.writeSyncFile(cfg, own, engine.encode(engine.gather()))
            var merged = SyncEngine.MergeResult()
            var peers = 0
            for (name in CloudClient.listSyncFiles(cfg).filter { it != own }) {
                val bundle = runCatching { engine.decode(CloudClient.readSyncFile(cfg, name)) }.getOrNull() ?: continue
                merged += engine.apply(bundle)
                peers++
            }
            // "Sync now" also flushes recordings parked in the upload queue (e.g. auto-upload off).
            var queued = 0
            graph.recordingRepository.all()
                .filter { RecordingStorage.isCloudStaged(context, it.filePath) }
                .forEach { CloudUploadWorker.enqueue(context, it.id, force = true); queued++ }
            Result.Success(merged.total, peers, queued)
        }.getOrElse { Result.Failed(it.message) }
        settings.markCloudSync(outcome is Result.Success)
        outcome
    }

    /** Background half of "Echtzeit-Sync": WorkManager's shortest period, 15 minutes, plus app start. */
    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): androidx.work.ListenableWorker.Result {
            val graph = ServiceLocator.get(applicationContext)
            if (!graph.settings.cloudLiveSync.value) return androidx.work.ListenableWorker.Result.success()
            return when (CloudSync(graph).sync(applicationContext)) {
                is Result.Failed -> androidx.work.ListenableWorker.Result.retry()
                else -> androidx.work.ListenableWorker.Result.success()
            }
        }
    }

    companion object {
        private const val WORK = "cloud-live-sync"

        /** (Re)schedule or cancel the periodic sync to match the current settings. */
        fun schedule(context: Context) {
            val settings = ServiceLocator.get(context).settings
            val wm = WorkManager.getInstance(context)
            if (!settings.cloudLiveSync.value || !settings.cloudConfigured) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val network = if (settings.syncWifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
