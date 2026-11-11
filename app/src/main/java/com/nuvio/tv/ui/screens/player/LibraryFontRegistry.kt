// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.ui.screens.player

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fonts of the user's subtitle folders, per ExoPlayer: sidecar track id → font files.
 *
 * libass forgets every font when the media item changes (`AssHandler.onMediaItemTransition`),
 * and an external ASS file reaches libass through a media reload. The fonts therefore go in when
 * Media3 creates that file's parser (`SubtitleParser.Factory.create(format)` on the loading
 * thread), right before `AssHandler.createTrack`, which flushes them to native code.
 * `format.id` there is the `SubtitleConfiguration` id as set by `toSubtitleConfiguration`; later
 * track groups may carry a `<n>:` prefix, so ids are matched by suffix as well.
 */
internal class LibraryFontRegistry {
    private val fontsByTrackId = ConcurrentHashMap<String, List<Pair<String, File>>>()

    fun register(trackId: String, fonts: List<Pair<String, File>>) {
        if (fonts.isEmpty()) fontsByTrackId.remove(trackId) else fontsByTrackId[trackId] = fonts
    }

    /** New episode or stream: nothing of the previous one may reach the next. */
    fun clear() = fontsByTrackId.clear()

    fun isEmpty(): Boolean = fontsByTrackId.isEmpty()

    internal fun fontsFor(formatId: String?): List<Pair<String, File>> {
        if (formatId.isNullOrEmpty() || fontsByTrackId.isEmpty()) return emptyList()
        fontsByTrackId[formatId]?.let { return it }
        return fontsByTrackId.entries.firstOrNull { (trackId, _) -> formatId.endsWith(":$trackId") || formatId.endsWith(trackId) }
            ?.value.orEmpty()
    }

    /**
     * Hands the fonts registered for [formatId] to [addFont] one at a time, each read just before
     * and dropped right after, so at most one font is held here. Returns how many were added.
     */
    fun addFontsFor(formatId: String?, addFont: (name: String, data: ByteArray) -> Unit): Int {
        val fonts = fontsFor(formatId)
        if (fonts.isEmpty()) return 0
        var added = 0
        for ((name, file) in fonts) {
            val data = runCatching { file.readBytes() }.getOrNull() ?: continue
            runCatching { addFont(name, data) }.onSuccess { added++ }
        }
        runCatching { Log.i(TAG, "libass: $added/${fonts.size} folder fonts added for an external subtitle") }
        return added
    }

    private companion object {
        const val TAG = "KaiSubtitleFonts"
    }
}
