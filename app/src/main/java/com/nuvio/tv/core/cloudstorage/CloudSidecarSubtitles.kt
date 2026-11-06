package com.nuvio.tv.core.cloudstorage

/** A subtitle file that belongs to a video, with the language its name says. */
internal data class CloudSidecarSubtitle(val file: CloudFile, val language: String, val label: String)

/**
 * Finds the subtitle files released next to a video, the way fansub groups ship them:
 * `Show - 02 [1080p].ass`, `Show - 02 [1080p].spa.srt`, `Show - 02.chs.ass`. A subtitle belongs
 * to the video when it sits in the same folder and its name starts with the video's name, or,
 * failing that, when it reads as the same title and episode.
 */
internal object CloudSidecarSubtitles {
    fun find(video: CloudFile, subtitles: List<CloudFile>): List<CloudSidecarSubtitle> {
        val sameFolder = subtitles.filter { it.parentPath == video.parentPath }
        if (sameFolder.isEmpty()) return emptyList()
        val base = video.name.substringBeforeLast('.').lowercase()
        val byName = sameFolder.filter { it.name.lowercase().startsWith(base) }
        val matches = byName.ifEmpty {
            val release = CloudReleaseParser.parse(video.name)
            val key = release.title.titleKey()
            if (key == null || release.episode == null) emptyList() else sameFolder.filter { subtitle ->
                val parsed = CloudReleaseParser.parse(subtitle.name)
                parsed.episode == release.episode && parsed.season == release.season &&
                    parsed.title.titleKey()?.matches(key) == true
            }
        }
        return matches.take(MAX_PER_VIDEO).map { subtitle ->
            val tag = subtitle.name.substringBeforeLast('.').lowercase()
                .removePrefix(base).trim('.', '_', '-', ' ', '[', ']', '(', ')')
            val language = languageOf(tag)
            CloudSidecarSubtitle(
                file = subtitle,
                language = language,
                label = listOfNotNull(
                    tag.takeIf { it.isNotBlank() && it.length <= 24 }?.uppercase(),
                    subtitle.name.substringAfterLast('.').uppercase(),
                ).joinToString(" · "),
            )
        }
    }

    /** ISO 639-2 codes, as subtitle add-ons report them. */
    internal fun languageOf(tag: String): String {
        val tokens = tag.lowercase().split('.', '_', '-', ' ', '[', ']').filter { it.isNotBlank() }
        for (token in tokens.asReversed()) {
            LANGUAGES[token]?.let { return it }
        }
        return "und"
    }

    private val LANGUAGES = mapOf(
        "es" to "spa", "spa" to "spa", "esp" to "spa", "spanish" to "spa", "español" to "spa", "espanol" to "spa",
        "lat" to "spa", "latino" to "spa", "es-419" to "spa", "esla" to "spa",
        "en" to "eng", "eng" to "eng", "english" to "eng",
        "pt" to "por", "por" to "por", "ptbr" to "por", "pt-br" to "por",
        "fr" to "fre", "fre" to "fre", "fra" to "fre", "french" to "fre",
        "de" to "ger", "ger" to "ger", "deu" to "ger", "german" to "ger",
        "it" to "ita", "ita" to "ita", "italian" to "ita",
        "ja" to "jpn", "jp" to "jpn", "jpn" to "jpn", "japanese" to "jpn",
        "zh" to "chi", "chi" to "chi", "chs" to "chi", "cht" to "chi", "sc" to "chi", "tc" to "chi",
        "gb" to "chi", "big5" to "chi", "zho" to "chi",
        "ru" to "rus", "rus" to "rus", "ar" to "ara", "ara" to "ara", "ko" to "kor", "kor" to "kor",
    )

    private const val MAX_PER_VIDEO = 6
}
