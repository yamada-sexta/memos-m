package org.example.memosm.data.media

import android.content.Context
import java.io.File

/**
 * Single owner of the offline-media directory layout:
 * `{root}/{accountId}/{attachmentName}/{filename}`.
 *
 * Phase 2 moved the root from `cacheDir/offline_media` to
 * `filesDir/offline_media`: the OS may purge cacheDir at any time, which made
 * the offline-first store unreliable. The legacy location is only kept here so
 * the one-time migration in [AttachmentCacheManager] can find old downloads.
 */
object OfflineMediaPaths {
    private const val OFFLINE_MEDIA_DIR = "offline_media"

    /** Current root: survives OS cache purges (filesDir/offline_media). */
    fun rootDir(context: Context): File = File(context.filesDir, OFFLINE_MEDIA_DIR)

    /** Pre-Phase-2 root (cacheDir/offline_media); only read during migration. */
    fun legacyRootDir(context: Context): File = File(context.cacheDir, OFFLINE_MEDIA_DIR)
}
