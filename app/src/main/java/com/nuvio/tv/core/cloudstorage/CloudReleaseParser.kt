package com.nuvio.tv.core.cloudstorage

/**
 * What a video file name says about itself. [episode] without [season] is either the episode of
 * the first season or an absolute number (fansub style `Title - 12`); the matcher decides with the
 * title's episode list. Numbers that are a resolution, a year, a bitrate, a version or a checksum
 * are never read as an episode.
 */
internal data class ParsedRelease(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val lastEpisode: Int? = null,
    val group: String? = null,
    val resolution: String? = null,
    val videoCodec: String? = null,
    val audio: String? = null,
    val source: String? = null,
    val container: String? = null,
    /**
     * OVA, OAD, SP, Special(s), Extra(s), Recap or Movie in the file name or in its folder: TMDB
     * files these under season 0, so they only match specials.
     */
    val isSpecial: Boolean = false,
    /** Blu-ray extras (NCOP, NCED, PV, CM, Menu, Preview, Trailer): never a movie or an episode. */
    val isExtra: Boolean = false,
    /** The title before a special keyword was cut off (`Gintama The Movie`), for movie requests. */
    val fullTitle: String = title,
) {
    val isEpisode: Boolean get() = episode != null
}

internal object CloudReleaseParser {
    private val leadingGroup = Regex("""^\s*[\[【(]([^\]】)]{1,60})[\]】)]\s*""")
    private val bracketed = Regex("""[\[【(]([^\]】)]*)[\]】)]""")
    private val dimensions = Regex("""(?i)(?<![0-9])(\d{3,4})\s*[x×]\s*(\d{3,4})(?![0-9])""")
    private val resolutionToken = Regex("""(?i)(?<![0-9a-z])(2160|1440|1080|720|576|540|480|360)[pi](?![0-9a-z])""")
    private val uhdToken = Regex("""(?i)(?<![0-9a-z])(4k|uhd)(?![0-9a-z])""")
    private val bitrate = Regex("""(?i)(?<![0-9a-z])\d+(?:\.\d+)?\s*(?:k|m)bps(?![0-9a-z])""")
    private val audioChannels = Regex("""(?i)(?<![0-9])[257]\.[01](?![0-9])""")
    private val bitDepth = Regex("""(?i)(?<![0-9a-z])(8|10|12)\s*-?\s*bits?(?![0-9a-z])""")
    private val codecToken = Regex("""(?i)(?<![0-9a-z])(x\.?26[45]|h\.?26[45]|hevc|avc|av1|vp9|xvid|divx)(?![0-9a-z])""")
    private val audioToken = Regex("""(?i)(?<![0-9a-z])(aac|flac|opus|ac3|eac3|e-ac-3|dd\+?|ddp|truehd|dts(?:-hd)?|mp3|atmos|dual[ .-]?audio)(?![a-z])""")
    private val sourceToken = Regex("""(?i)(?<![0-9a-z])(bd(?:rip|remux)?|blu-?ray|web-?dl|web-?rip|webrip|web|hdtv|dvd(?:rip)?|remux|tv)(?![0-9a-z])""")
    private val checksum = Regex("""(?i)^[0-9a-f]{8}$""")
    private val version = Regex("""(?i)(?<=\d)v\d\b""")

    private val seasonEpisode = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})\s*[ ._-]?\s*e(\d{1,4})(?:\s*-?\s*e?(\d{1,4}))?(?![0-9])""")
    private val crossed = Regex("""(?i)(?<![0-9a-z])(\d{1,2})x(\d{1,4})(?![0-9a-z])""")
    private val wordSeasonEpisode = Regex(
        """(?i)(?:season|temporada|saison|staffel|stagione)\s*(\d{1,2})\s*[-–:,._ ]*\s*(?:episode|episodio|épisode|episódio|capitulo|capítulo|cap|ep)\.?\s*(\d{1,4})""",
    )
    private val wordSeason = Regex("""(?i)(?:season|temporada|saison|staffel|stagione)\s*(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)\s+season|(?<![a-z0-9])s(\d{1,2})(?![a-z0-9])""")
    private val wordEpisode = Regex("""(?i)(?<![a-z])(?:episode|episodio|épisode|episódio|capitulo|capítulo|cap|ep|e|#)\s*\.?\s*(\d{1,4})(?![0-9])""")
    private val dashNumber = Regex("""(?:^|\s)[-–]\s*(\d{1,4})(?:v\d)?(?=\s|$)""")
    private val bareNumber = Regex("""(?<![0-9a-z])(\d{1,4})(?:v\d)?(?![0-9a-z])""", RegexOption.IGNORE_CASE)
    private val specialToken = Regex(
        """(?i)(?<![a-z0-9])(ovas?|oads?|sp|specials?|especial(?:es)?|extras?|recaps?|movies?|pel[ií]culas?)(?=\d|[^a-z0-9]|$)""",
    )
    private val extraToken = Regex(
        """(?<![A-Za-z0-9])((?i:ncop|nced|creditless|menu|preview|trailer|teaser)|PV|CM)(?=\d|[^A-Za-z0-9]|$)""",
    )

    /** Only digits, ranges and punctuation follow: `OVA 01`, `SP2`, `NCOP1`, `Specials`. */
    private val trailingNumbersOnly = Regex("""^[\s\d\-–_.,&+#v]*$""", RegexOption.IGNORE_CASE)

    /**
     * Where a special or extra keyword starts in [text], or null. A keyword at the very start counts
     * only when nothing but numbers follows, so titles such as `Special A` or `Trailer Park Boys`
     * stay titles.
     */
    private fun keywordStart(regex: Regex, text: String): Int? {
        for (match in regex.findAll(text)) {
            val before = text.substring(0, match.range.first)
            if (before.any { it.isLetterOrDigit() }) return match.range.first
            if (trailingNumbersOnly.matches(text.substring(match.range.last + 1))) return match.range.first
        }
        return null
    }

    /** A folder such as `Specials`, `OVA`, `Hellsing Ultimate OVA` or `Extras 1-3`. */
    private fun isKeywordFolder(regex: Regex, folder: String): Boolean {
        val text = bracketed.replace(folder.replace('_', ' ').replace('.', ' '), " ").replace(Regex("""\s+"""), " ").trim()
        return regex.findAll(text).any { trailingNumbersOnly.matches(text.substring(it.range.last + 1)) }
    }

    private val yearToken = Regex("""(?<![0-9])(19[2-9]\d|20[0-4]\d)(?![0-9])""")

    fun parse(fileName: String, folderNames: List<String> = emptyList()): ParsedRelease {
        val container = fileName.substringAfterLast('.', "").lowercase().takeIf { it in CLOUD_VIDEO_EXTENSIONS }
        var text = if (container != null) fileName.substringBeforeLast('.') else fileName

        var group: String? = null
        leadingGroup.find(text)?.let { match ->
            val inner = match.groupValues[1].trim()
            if (!inner.looksTechnical()) group = inner
            text = text.substring(match.range.last + 1)
        }

        val tags = StringBuilder()
        var year: Int? = null
        text = bracketed.replace(text) { match ->
            val inner = match.groupValues[1]
            val innerYear = yearToken.matchEntire(inner.trim())?.value?.toIntOrNull()
            if (innerYear != null) {
                year = innerYear
            } else {
                tags.append(' ').append(inner)
            }
            " "
        }

        val technical = "$text $tags"
        val tagText = tags.toString()
        var isSpecial = specialToken.findAll(tagText).any() // `[OVA]`, `(Special)`
        var isExtra = extraToken.findAll(tagText).any() // `[NCOP]`, `(PV)`
        val resolution = resolutionOf(technical)
        val videoCodec = codecToken.find(technical)?.value?.let(::normalizeCodec)
        val audio = audioToken.find(technical)?.value?.uppercase()?.replace(Regex("[ .-]"), "")
        val source = sourceToken.find(technical)?.value?.let(::normalizeSource)

        // Remove everything technical before looking for numbers.
        text = text.replace('_', ' ')
        text = dimensions.replace(text, " ")
        text = bitrate.replace(text, " ")
        text = resolutionToken.replace(text, " ")
        text = uhdToken.replace(text, " ")
        text = audioChannels.replace(text, " ")
        text = bitDepth.replace(text, " ")
        text = codecToken.replace(text, " ")
        text = text.replace(Regex("""(?<=[A-Za-z])\.(?=[A-Za-z0-9])|(?<=[0-9])\.(?=[A-Za-z])"""), " ")
        text = text.replace(Regex("""\s+"""), " ").trim()

        // `SP01`, `OVA2`, `NCOP1`: split the keyword from its number so both are read.
        text = text.replace(Regex("""(?i)(?<![a-z])(sp|ovas?|oads?|ncop|nced|pv|cm)(\d)"""), "$1 $2")

        var season: Int? = null
        var episode: Int? = null
        var lastEpisode: Int? = null
        var titleEnd = text.length

        fun take(range: IntRange) {
            titleEnd = minOf(titleEnd, range.first)
        }

        seasonEpisode.find(text)?.let { match ->
            season = match.groupValues[1].toInt()
            episode = match.groupValues[2].toInt()
            lastEpisode = match.groupValues[3].toIntOrNull()?.takeIf { it > episode!! }
            take(match.range)
        }
        if (episode == null) {
            wordSeasonEpisode.find(text)?.let { match ->
                season = match.groupValues[1].toInt()
                episode = match.groupValues[2].toInt()
                take(match.range)
            }
        }
        if (episode == null) {
            crossed.find(text)?.let { match ->
                season = match.groupValues[1].toInt()
                episode = match.groupValues[2].toInt()
                take(match.range)
            }
        }
        if (episode == null) {
            wordSeason.find(text)?.let { match ->
                season = (match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }).toIntOrNull()
                take(match.range)
            }
            val searchFrom = if (season != null) titleEnd else 0
            wordEpisode.find(text, searchFrom)?.let { match ->
                episode = match.groupValues[1].toInt()
                take(match.range)
            }
        }
        if (episode == null) {
            // A file named only `01.mkv` (or `01v2`): the folders carry the title.
            Regex("""^(\d{1,4})(?:v\d)?$""", RegexOption.IGNORE_CASE).matchEntire(text)?.let { match ->
                val value = match.groupValues[1].toInt()
                if (!(match.groupValues[1].length == 4 && value.isYear())) {
                    episode = value
                    titleEnd = 0
                }
            }
        }
        if (episode == null) {
            dashNumber.find(text)?.let { match ->
                val value = match.groupValues[1].toInt()
                if (!value.isYear()) {
                    episode = value
                    take(match.range)
                }
            }
        }
        if (episode == null) {
            // `Title 01 BD`: the first standalone number after at least one title word.
            bareNumber.findAll(text).firstOrNull { match ->
                val value = match.groupValues[1]
                match.range.first > 0 &&
                    text.substring(0, match.range.first).any { it.isLetter() } &&
                    !(value.length == 4 && value.toInt().isYear()) &&
                    !checksum.matches(value)
            }?.let { match ->
                episode = match.groupValues[1].toInt()
                take(match.range)
            }
        }

        if (year == null) {
            yearToken.findAll(text).firstOrNull { it.range.first > 0 }?.let { match ->
                year = match.value.toInt()
                if (episode == null) titleEnd = minOf(titleEnd, match.range.first)
            }
        }
        val sourceStart = sourceToken.find(text)?.range?.first
        if (sourceStart != null && sourceStart > 0) titleEnd = minOf(titleEnd, sourceStart)

        val fullTitle = cleanTitle(text.substring(0, titleEnd.coerceIn(0, text.length)))
        keywordStart(extraToken, text)?.let { start ->
            isExtra = true
            titleEnd = minOf(titleEnd, start)
        }
        keywordStart(specialToken, text)?.let { start ->
            isSpecial = true
            titleEnd = minOf(titleEnd, start)
        }
        var title = cleanTitle(text.substring(0, titleEnd.coerceIn(0, text.length)))

        // Folder context: a title or season the file name itself does not carry.
        val folders = folderNames.filter { it.isNotBlank() }.reversed()
        // `Show/Specials/01.mkv`, `Show/OVA/…`: the two nearest folders can make a file a special.
        if (folders.take(2).any { isKeywordFolder(specialToken, it) }) isSpecial = true
        if (folders.take(1).any { isKeywordFolder(extraToken, it) }) isExtra = true
        if (season == null && episode != null) {
            folders.firstNotNullOfOrNull { folder -> folderSeason(folder) }?.let { season = it }
        }
        if (title.isBlank() || title.isGenericEpisodeWord()) {
            // The nearest folder with a title of its own (`Season 2` alone has none).
            title = folders.firstNotNullOfOrNull { folder -> parse(folder).title.takeIf { it.isNotBlank() } }.orEmpty()
        }

        return ParsedRelease(
            title = title,
            year = year,
            season = season,
            episode = episode,
            lastEpisode = lastEpisode,
            group = group,
            resolution = resolution,
            videoCodec = videoCodec,
            audio = audio,
            source = source,
            container = container,
            isSpecial = isSpecial,
            isExtra = isExtra,
            fullTitle = fullTitle.ifBlank { title },
        )
    }

    /** `Season 2`, `S02`, `Temporada 2` or `2nd Season` in a folder name. */
    fun folderSeason(folder: String): Int? {
        val match = wordSeason.find(folder.replace('_', ' ').replace('.', ' ')) ?: return null
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }).toIntOrNull()
    }

    private fun resolutionOf(text: String): String? {
        resolutionToken.find(text)?.let { return "${it.groupValues[1]}p" }
        if (uhdToken.containsMatchIn(text)) return "2160p"
        dimensions.find(text)?.let { match ->
            val height = match.groupValues[2].toInt()
            return when {
                height >= 2000 -> "2160p"
                height >= 1000 -> "1080p"
                height >= 700 -> "720p"
                height >= 560 -> "576p"
                else -> "480p"
            }
        }
        return null
    }

    private fun normalizeCodec(value: String): String = when (value.lowercase().replace(".", "")) {
        "x265", "h265", "hevc" -> "HEVC"
        "x264", "h264", "avc" -> "H.264"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        else -> value.uppercase()
    }

    private fun normalizeSource(value: String): String = when (value.lowercase().replace("-", "")) {
        "bd", "bdrip", "bluray" -> "BD"
        "bdremux", "remux" -> "Remux"
        "webdl", "web" -> "WEB-DL"
        "webrip" -> "WEBRip"
        "hdtv", "tv" -> "TV"
        else -> value.uppercase()
    }

    private fun String.looksTechnical(): Boolean =
        resolutionToken.containsMatchIn(this) || dimensions.containsMatchIn(this) || codecToken.containsMatchIn(this) ||
            checksum.matches(trim())

    private fun Int.isYear(): Boolean = this in 1920..2049

    private fun cleanTitle(value: String): String =
        value.replace(Regex("""[\s\-–_:,.+]+$"""), "")
            .replace(Regex("""^[\s\-–_:,.]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.isGenericEpisodeWord(): Boolean =
        lowercase() in setOf("episode", "episodio", "ep", "e", "capitulo", "capítulo", "cap")
}
