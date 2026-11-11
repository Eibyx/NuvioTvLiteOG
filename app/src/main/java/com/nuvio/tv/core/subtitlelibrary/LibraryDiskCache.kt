// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Downloaded subtitles and fonts: `<root>/<origin>/<hash(fileId+size+mtime)>.<ext>`, least recently
 * used first out once the total passes the limit. A file that did not change (same id, size and
 * modification time) is served from here again.
 */
internal class LibraryDiskCache(
    private val root: File,
    private val maxBytes: () -> Long,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    fun keyFor(fileId: String, sizeBytes: Long, modifiedEpochMs: Long, fileName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$fileId|$sizeBytes|$modifiedEpochMs".toByteArray(Charsets.UTF_8))
        val hash = digest.take(16).joinToString("") { "%02x".format(it) }
        val extension = fileName.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        return "$hash.$extension"
    }

    private fun originDir(origin: String): File =
        File(root, origin.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "other" })

    /** The cached copy, marked as just used; null when it is not cached. */
    fun get(origin: String, key: String): File? = synchronized(lock) {
        val file = File(originDir(origin), key)
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(now())
        file
    }

    /**
     * Writes a new entry through a temporary file and renames it into place, then trims the cache
     * (never removing [keep]). A failed write leaves nothing behind.
     */
    fun put(origin: String, key: String, keep: Set<File> = emptySet(), write: (OutputStream) -> Unit): File {
        val dir = originDir(origin)
        check(dir.isDirectory || dir.mkdirs()) { "cache directory unavailable" }
        val target = File(dir, key)
        val temporary = File(dir, "$key.part")
        try {
            temporary.outputStream().use(write)
            synchronized(lock) {
                if (target.exists()) target.delete()
                check(temporary.renameTo(target)) { "could not store cache entry" }
                target.setLastModified(now())
            }
        } finally {
            temporary.delete()
        }
        trim(keep + target)
        return target
    }

    fun totalBytes(): Long = synchronized(lock) { entries().sumOf { it.length() } }

    /** Removes the least recently used entries until the cache fits its limit. */
    fun trim(keep: Set<File> = emptySet()) = synchronized(lock) {
        val limit = maxBytes().coerceAtLeast(0L)
        val files = entries().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        val keepPaths = keep.map { it.absolutePath }.toSet()
        for (file in files) {
            if (total <= limit) break
            if (file.absolutePath in keepPaths) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    fun clear() = synchronized(lock) { root.deleteRecursively() }

    private fun entries(): List<File> =
        root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.toList()
}
