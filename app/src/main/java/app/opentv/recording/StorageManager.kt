/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import android.os.StatFs
import app.opentv.core.ServiceLocator
import app.opentv.data.model.RecordingStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The numbers behind the "Speicher & Cloud" overview — how full the device is and what OpenTV
 * itself is using it for — plus the optional "clean up old recordings when space runs low".
 */
object StorageManager {

    data class Breakdown(
        val totalBytes: Long,
        val freeBytes: Long,
        val recordingsBytes: Long,
        val uploadQueueBytes: Long,
        val guideBytes: Long,
        val imageCacheBytes: Long,
        val otherCacheBytes: Long,
    ) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
        val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
    }

    private fun sizeOf(f: File?): Long = when {
        f == null || !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    suspend fun breakdown(context: Context): Breakdown = withContext(Dispatchers.IO) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val stat = StatFs(base.absolutePath)
        val imageCache = File(context.cacheDir, "image_cache")
        val db = context.getDatabasePath("opentv.db")
        Breakdown(
            totalBytes = stat.totalBytes,
            freeBytes = stat.availableBytes,
            recordingsBytes = sizeOf(RecordingStorage.internalDir(context)),
            uploadQueueBytes = sizeOf(RecordingStorage.cloudStagingDir(context)),
            // The guide lives in the database (plus its WAL side files).
            guideBytes = sizeOf(db) + sizeOf(File(db.path + "-wal")) + sizeOf(File(db.path + "-shm")),
            imageCacheBytes = sizeOf(imageCache),
            otherCacheBytes = (sizeOf(context.cacheDir) - sizeOf(imageCache)).coerceAtLeast(0),
        )
    }

    /** Empties the picture cache (logos/posters reload on demand). Returns bytes freed. */
    suspend fun clearImageCache(context: Context): Long = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "image_cache")
        val before = sizeOf(dir)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        before
    }

    /**
     * When auto-cleanup is on and free space is under the threshold, deletes the OLDEST finished
     * recordings stored on this device until there's room again. Only local files are touched —
     * never anything on USB, the NAS or in the cloud. Returns how many recordings were removed.
     */
    suspend fun cleanupIfNeeded(context: Context): Int = withContext(Dispatchers.IO) {
        val graph = ServiceLocator.get(context)
        if (!graph.settings.autoCleanup.value) return@withContext 0
        val threshold = graph.settings.cleanupThresholdGb * 1024L * 1024L * 1024L
        val base = context.getExternalFilesDir(null) ?: return@withContext 0
        fun free() = StatFs(base.absolutePath).availableBytes
        if (free() >= threshold) return@withContext 0

        val internal = RecordingStorage.internalDir(context).absolutePath
        val candidates = graph.recordingRepository.all()
            .filter { it.status == RecordingStatus.COMPLETED && it.filePath.startsWith(internal) }
            .sortedBy { it.endedAtMillis }
        var removed = 0
        for (rec in candidates) {
            if (free() >= threshold) break
            runCatching { File(rec.filePath).delete() }
            graph.recordingRepository.delete(rec.id)
            removed++
        }
        removed
    }
}
