// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import java.util.Locale

/** A font file next to a subtitle. [faces] is null when the file could not be read as a font. */
internal data class FontCandidate(
    val key: String,
    val fileName: String,
    val sizeBytes: Long,
    val faces: List<FontFaceNames>?
)

internal data class FontLimits(
    /** All fonts of one subtitle together. */
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    /** Unreadable files are loaded blind only when the folder has at most this many fonts. */
    val maxBlindFiles: Int = 4
) {
    companion object {
        /**
         * 32 MB rather than 40: while the player reloads the media, libass keeps the Java copies
         * pending until the track exists and then copies them natively, so the peak is about twice
         * this on a TV with 1 GB of RAM.
         */
        const val DEFAULT_MAX_TOTAL_BYTES = 32L * 1024 * 1024
        const val DEFAULT_MAX_FILE_BYTES = 25L * 1024 * 1024
    }
}

internal data class FontPlan(
    val selected: List<FontCandidate>,
    /** Names the script uses that no readable font in the folder provides. */
    val missing: Set<String>,
    /** Matching fonts left out by the size limits. */
    val skippedForSize: List<FontCandidate>
)

/**
 * Picks the fonts of a subtitle's own folder that the script really uses: every face (regular,
 * bold, italic…) of each family a style or `\fn` names, within the size limits.
 */
internal object FontSelection {

    fun plan(used: Set<String>, candidates: List<FontCandidate>, limits: FontLimits = FontLimits()): FontPlan {
        if (used.isEmpty() || candidates.isEmpty()) return FontPlan(emptyList(), used, emptyList())
        val readable = candidates.filter { !it.faces.isNullOrEmpty() }
        // Families to load: the family of every face that carries a used name.
        val families = linkedSetOf<String>()
        val direct = linkedSetOf<String>()
        val covered = mutableSetOf<String>()
        for (name in used) {
            readable.forEach { candidate ->
                candidate.faces!!.forEach { face ->
                    if (name in face.names) {
                        covered += name
                        direct += candidate.key
                        face.family?.let(families::add)
                    }
                }
            }
        }
        val wanted = readable.filter { candidate ->
            candidate.key in direct || candidate.faces!!.any { it.family != null && it.family in families }
        }.sortedWith(compareBy<FontCandidate>({ it.key !in direct }, { it.sizeBytes }))

        val selected = mutableListOf<FontCandidate>()
        val skipped = mutableListOf<FontCandidate>()
        var total = 0L
        for (candidate in wanted) {
            if (candidate.sizeBytes > limits.maxFileBytes || total + candidate.sizeBytes > limits.maxTotalBytes) {
                skipped += candidate
                continue
            }
            selected += candidate
            total += candidate.sizeBytes
        }

        val missingNames = used - covered
        val blind = candidates.filter { it.faces.isNullOrEmpty() }
        var blindLoaded = false
        if (missingNames.isNotEmpty() && blind.isNotEmpty() && candidates.size <= limits.maxBlindFiles) {
            for (candidate in blind.sortedBy { it.sizeBytes }) {
                if (candidate.sizeBytes > limits.maxFileBytes || total + candidate.sizeBytes > limits.maxTotalBytes) continue
                selected += candidate
                total += candidate.sizeBytes
                blindLoaded = true
            }
        }
        // With unreadable fonts loaded blind we cannot tell what is still missing: no warning then.
        val missing = if (blindLoaded) emptySet() else missingNames
        return FontPlan(selected, missing, skipped)
    }

    /**
     * The order to fetch fonts from a cloud folder in: files whose name looks like a used font first,
     * so the usual case (one or two families) needs only those downloads.
     */
    fun fetchOrder(used: Set<String>, fileNames: List<String>): List<String> =
        fileNames.sortedByDescending { nameScore(used, it) }

    /** True when the file name looks like one of the used fonts (`GandhiSans-Bold.otf` for "gandhi sans"). */
    fun looksLike(used: Set<String>, fileName: String): Boolean = nameScore(used, fileName) > 0

    private fun nameScore(used: Set<String>, fileName: String): Int {
        val keys = used.map(::compact)
        val stem = compact(fileName.substringBeforeLast('.'))
        return when {
            keys.any { it == stem } -> 3
            keys.any { it.isNotEmpty() && stem.isNotEmpty() && (stem.startsWith(it) || it.startsWith(stem)) } -> 2
            keys.any { it.length >= 4 && stem.contains(it) } -> 1
            else -> 0
        }
    }

    /** True when every used name is provided by some readable face already read. */
    fun allCovered(used: Set<String>, faces: Collection<FontFaceNames>): Boolean =
        used.all { name -> faces.any { name in it.names } }

    private fun compact(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            .removeSuffix("italic").removeSuffix("bold").removeSuffix("regular")
}
