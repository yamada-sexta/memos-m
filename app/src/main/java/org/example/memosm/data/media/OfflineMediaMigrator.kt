package org.example.memosm.data.media

import java.io.File

/**
 * Pure file-move logic for the cacheDir -> filesDir offline-media migration.
 * Kept free of Android/Room dependencies so it is unit-testable on the JVM.
 */
object OfflineMediaMigrator {

    /**
     * Move [source] (a file under [legacyRoot]) to the same relative location
     * under [root] and return it at its new location. Returns null when the
     * source is not under [legacyRoot], or when neither source nor target
     * exists.
     *
     * Idempotent, so a run that crashed mid-migration simply resumes:
     * - source gone, target present: a previous run moved the file but died
     *   before the DB row was updated - the target is adopted as-is.
     * - both present, same size: a previous run moved/copied the file but died
     *   before deleting the source - the source is dropped.
     * The move itself is an atomic rename with a copy + verify + delete
     * fallback (renameTo fails across filesystems).
     */
    fun movePreservingPath(legacyRoot: File, root: File, source: File): File? {
        // relativeToOrNull happily produces ".." segments for files outside
        // legacyRoot, which would let the target escape root - reject those.
        val relative = source.relativeToOrNull(legacyRoot)
            ?.takeUnless { rel -> rel.path.split('/', '\\').any { it == ".." } }
            ?: return null
        val target = File(root, relative.path)
        if (!source.exists()) {
            return target.takeIf { it.exists() }
        }
        if (target.exists()) {
            if (target.length() == source.length()) {
                source.delete()
                return target
            }
            target.delete()
        }
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return target
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            target.delete()
            return null
        }
        if (target.length() != source.length()) {
            target.delete()
            return null
        }
        source.delete()
        return target
    }
}
