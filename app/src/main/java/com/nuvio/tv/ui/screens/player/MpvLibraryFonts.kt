// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0) and adapted to NuvioTvLiteOG.
package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * mpv's `sub-fonts-dir` for the user's subtitle folders.
 *
 * In mpv 0.41 (`mpv-android-lib` 0.1.12, `mpv v0.41.0-174`) every subtitle decoder builds its own
 * libass library when it is created (`sd_ass` init → `mp_ass_init` → `ass_set_fonts_dir`) and libass
 * reads every font of that directory into memory. A decoder is created by `sub-add`.
 *
 * LiteOG never set `sub-fonts-dir` before this feature, so mpv kept its default. To leave every
 * other subtitle exactly as it was, the option is changed only right before a folder subtitle with
 * fonts is added (to a directory with just that subtitle's fonts) and put back to its original value
 * before any other `sub-add`.
 *
 * Files are hard links where the file system allows (no extra space), copies otherwise, and each
 * appears through a rename, so mpv never reads half a font.
 */
internal object MpvLibraryFonts {
    private const val TAG = "KaiSubtitleFonts"
    private const val ACTIVE_DIR = "mpv-sub-fonts-active"
    private const val FOLDER_FONT_PREFIX = "kai-"

    private val fontsByPath = ConcurrentHashMap<String, List<Pair<String, File>>>()

    fun register(subtitlePath: String, fonts: List<Pair<String, File>>) {
        if (fonts.isEmpty()) fontsByPath.remove(subtitlePath) else fontsByPath[subtitlePath] = fonts
    }

    fun clear() = fontsByPath.clear()

    fun hasFontsFor(subtitlePath: String?): Boolean = subtitlePath != null && !fontsByPath[subtitlePath].isNullOrEmpty()

    fun activeDir(context: Context): File = File(context.noBackupFilesDir, ACTIVE_DIR)

    /**
     * Makes the active directory hold exactly the fonts registered for [subtitlePath] and returns it,
     * or null when there are none or the directory could not be written (then mpv keeps its own).
     */
    @Synchronized
    fun prepare(context: Context, subtitlePath: String?): File? {
        val fonts = subtitlePath?.let { fontsByPath[it] }.orEmpty()
        if (fonts.isEmpty()) return null
        val dir = activeDir(context)
        return runCatching {
            check(dir.isDirectory || dir.mkdirs())
            val wanted = LinkedHashMap<String, File>()
            fonts.forEachIndexed { index, (name, file) ->
                val safe = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(80)
                wanted["$FOLDER_FONT_PREFIX$index-$safe"] = file
            }
            dir.listFiles().orEmpty().forEach { existing ->
                if (existing.name !in wanted || existing.name.endsWith(".tmp")) existing.delete()
            }
            wanted.forEach { (name, source) ->
                val target = File(dir, name)
                if (target.isFile && target.length() == source.length()) return@forEach
                val temporary = File(dir, "$name.tmp")
                temporary.delete()
                val linked = runCatching { android.system.Os.link(source.absolutePath, temporary.absolutePath); true }.getOrDefault(false)
                if (!linked) source.copyTo(temporary, overwrite = true)
                check(temporary.renameTo(target)) { "rename failed" }
            }
            dir
        }.onFailure {
            Log.w(TAG, "mpv font directory not updated: ${it::class.java.simpleName}")
        }.getOrNull()
    }
}
