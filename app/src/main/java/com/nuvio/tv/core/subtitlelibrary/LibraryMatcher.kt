// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import com.nuvio.tv.core.cloudstorage.CloudReleaseParser
import com.nuvio.tv.core.cloudstorage.titleKey
import java.util.Locale
import kotlinx.serialization.Serializable

/** A file or folder in one of the user's subtitle folders, whatever the origin. */
@Serializable
data class LibraryEntry(
    /** Origin-specific id: a cloud file id, a document id or an absolute path. */
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long = 0,
    val modifiedEpochMs: Long = 0,
    /** Cloud path (TeraBox needs it with the id to make a download link); empty elsewhere. */
    val path: String = ""
)

/** A subtitle file found for the episode, with the folder it sits in (for its fonts). */
internal data class EpisodeSubtitle(
    val file: LibraryEntry,
    /** Names of the folders between the anime folder and the file (`["Temporada 2"]`), possibly empty. */
    val subfolders: List<String>,
    val folder: LibraryEntry
)

internal sealed interface AnimeFolderMatch {
    data class Found(val folder: LibraryEntry, val linked: Boolean) : AnimeFolderMatch
    /** Several folders fit equally well: the user links one ("Vincular carpeta…"). */
    data class Ambiguous(val folders: List<LibraryEntry>) : AnimeFolderMatch
    data object None : AnimeFolderMatch
}

internal object LibraryMatcher {
    val SUBTITLE_EXTENSIONS = setOf("ass", "ssa", "srt", "vtt")
    private val FONT_FOLDER_NAMES = setOf("fonts", "fuentes", "attachments", "font", "fuente")

    fun isSubtitleName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SUBTITLE_EXTENSIONS

    fun isFontFolder(name: String): Boolean = name.trim().lowercase(Locale.ROOT) in FONT_FOLDER_NAMES

    /**
     * The anime's folder among the root's folders: the linked one if it still exists, otherwise the
     * single folder whose name matches the title or one of its aliases. Never guesses between two.
     */
    fun animeFolder(folders: List<LibraryEntry>, titles: List<String>, linkedFolderId: String?): AnimeFolderMatch {
        val candidates = folders.filter { it.isFolder }
        if (linkedFolderId != null) {
            candidates.firstOrNull { it.id == linkedFolderId }?.let { return AnimeFolderMatch.Found(it, linked = true) }
        }
        val keys = titles.mapNotNull { it.titleKey() }
        if (keys.isEmpty()) return AnimeFolderMatch.None
        val matched = candidates.filter { folder ->
            val folderKeys = listOfNotNull(
                folder.name.titleKey(),
                CloudReleaseParser.parse(folder.name).title.titleKey()
            )
            folderKeys.any { folderKey -> keys.any { it.matches(folderKey) } }
        }
        return when (matched.size) {
            0 -> AnimeFolderMatch.None
            1 -> AnimeFolderMatch.Found(matched.single(), linked = false)
            else -> {
                // An exact compact match beats a fuzzy one.
                val exact = matched.filter { folder ->
                    val compact = folder.name.titleKey()?.compact
                    keys.any { it.compact == compact }
                }
                if (exact.size == 1) AnimeFolderMatch.Found(exact.single(), linked = false) else AnimeFolderMatch.Ambiguous(matched)
            }
        }
    }

    /** Season number of a subfolder (`Temporada 2`, `Season 2`, `S2`), or null for any other folder. */
    fun seasonOfFolder(name: String): Int? = CloudReleaseParser.folderSeason(name)

    /**
     * The subtitle files for [season]/[episode] (or the absolute number) in the anime folder's tree.
     * [files] are (subfolders, file) pairs for every subtitle in the anime folder and its season folders.
     */
    fun episodeSubtitles(
        files: List<EpisodeSubtitle>,
        season: Int?,
        episode: Int?,
        absoluteEpisode: Int?
    ): List<EpisodeSubtitle> {
        if (episode == null) return emptyList()
        val hasSeasonFolders = files.any { file -> file.subfolders.any { seasonOfFolder(it) != null } }
        return files.filter { candidate ->
            if (!isSubtitleName(candidate.file.name)) return@filter false
            val folderSeason = candidate.subfolders.firstNotNullOfOrNull(::seasonOfFolder)
            val stem = candidate.file.name.substringBeforeLast('.')
            val parsed = CloudReleaseParser.parse(stem, candidate.subfolders)
            if (parsed.isExtra) return@filter false
            val fileEpisode = parsed.episode ?: return@filter false
            val fileSeason = folderSeason ?: parsed.season
            when {
                season == null -> fileEpisode == episode
                fileSeason != null && fileSeason == season && fileEpisode == episode -> true
                fileSeason != null && fileSeason != season -> false
                // No season anywhere: season 1, or the absolute number for later seasons.
                fileSeason == null && !hasSeasonFolders && season == 1 -> fileEpisode == episode
                fileSeason == null && absoluteEpisode != null -> fileEpisode == absoluteEpisode
                fileSeason == null && hasSeasonFolders -> false
                else -> false
            }
        }.sortedWith(compareBy({ it.subfolders.size }, { it.file.name.lowercase(Locale.ROOT) }))
    }

    /** A language code from tags in the file name (`.es.ass`, `[Spanish]`, `Español`), or null. */
    fun languageOf(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
        val suffix = stem.substringAfterLast('.', "").takeIf { it.length in 2..6 && it.all { c -> c.isLetter() || c == '-' } }
        LANGUAGE_TAGS[suffix]?.let { return it }
        val words = stem.split(Regex("""[^\p{L}]+""")).filter { it.isNotBlank() }
        return words.firstNotNullOfOrNull { LANGUAGE_WORDS[it] }
    }

    private val LANGUAGE_TAGS = mapOf(
        "es" to "es", "spa" to "es", "esp" to "es", "es-419" to "es", "es-es" to "es", "lat" to "es", "latino" to "es",
        "en" to "en", "eng" to "en", "pt" to "pt", "por" to "pt", "pt-br" to "pt", "ja" to "ja", "jpn" to "ja",
        "fr" to "fr", "fre" to "fr", "fra" to "fr", "de" to "de", "ger" to "de", "deu" to "de", "it" to "it", "ita" to "it"
    )
    private val LANGUAGE_WORDS = mapOf(
        "español" to "es", "espanol" to "es", "spanish" to "es", "castellano" to "es", "latino" to "es",
        "english" to "en", "inglés" to "en", "ingles" to "en", "portugues" to "pt", "português" to "pt",
        "japanese" to "ja", "japonés" to "ja", "french" to "fr", "francés" to "fr", "german" to "de", "italian" to "it"
    )
}
