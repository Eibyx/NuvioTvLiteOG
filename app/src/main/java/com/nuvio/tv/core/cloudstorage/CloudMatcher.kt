package com.nuvio.tv.core.cloudstorage

/** A title the matcher can compare file names against, optionally tied to one season (AniList entries). */
internal data class CloudTitleAlias(
    val title: String,
    val seasonHint: Int? = null,
)

/** What the user asked streams for, with everything known about the title. */
internal data class CloudMatchRequest(
    val isSeries: Boolean,
    /** The id the title was opened with: `tt…`, `tmdb:…`, `anilist:…`, `kitsu:…`. */
    val contentIds: Set<String>,
    val aliases: List<CloudTitleAlias>,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** The same episode counted from the first episode of the show, for fansub numbering. */
    val absoluteEpisode: Int? = null,
    /** What the stream card shows: the catalogue's (TMDB) title, episode title and runtime. */
    val displayTitle: String? = null,
    val episodeTitle: String? = null,
    val runtimeMinutes: Int? = null,
)

internal class CloudMatch(val file: CloudFile, val release: ParsedRelease, val manual: Boolean)

/**
 * Finds the files of a library that are the requested movie or episode. Only matches with
 * evidence: an alias equal to the file's title (or its folder's), the right year for movies, and
 * the right season/episode (or absolute number) for series. Manual links always win.
 */
internal object CloudMatcher {
    fun match(
        files: List<CloudFile>,
        request: CloudMatchRequest,
        manualLinks: List<CloudManualLink> = emptyList(),
        parse: (CloudFile) -> ParsedRelease = { file -> CloudReleaseParser.parse(file.name, file.folderNames()) },
    ): List<CloudMatch> {
        val linksByFile = manualLinks.associateBy { it.fileId }
        val aliasKeys = request.aliases.mapNotNull { alias -> alias.title.titleKey()?.let { it to alias } }
        if (aliasKeys.isEmpty() && linksByFile.isEmpty()) return emptyList()
        // Cheap pre-check before the name parser: a file can only match an alias whose words
        // appear in its path. Keeps searches light on large indexes and slow TV boxes.
        val aliasTokens = aliasKeys.flatMap { it.first.tokens }.toSet()
        return files.mapNotNull { file ->
            if (file.id !in linksByFile && !file.mayContainAny(aliasTokens)) return@mapNotNull null
            val link = linksByFile[file.id]
            if (link != null) {
                val linked = link.contentId in request.contentIds &&
                    (!request.isSeries || (link.season == request.season && link.episode == request.episode))
                return@mapNotNull if (linked) CloudMatch(file, parse(file), manual = true) else null
            }
            val release = parse(file)
            // Blu-ray extras (NCOP, NCED, PV, CM, menus, previews, trailers) are never the title.
            if (release.isExtra) return@mapNotNull null
            val matchedAliases = matchingAliases(release, file, aliasKeys, includeFullTitle = !request.isSeries)
            if (matchedAliases.isEmpty()) return@mapNotNull null
            val ok = if (request.isSeries) {
                episodeMatches(release, request, matchedAliases)
            } else {
                !release.isEpisode && yearMatches(release.year, request.year)
            }
            if (ok) CloudMatch(file, release, manual = false) else null
        }.sortedWith(
            compareByDescending<CloudMatch> { it.manual }
                .thenByDescending { it.release.resolution?.removeSuffix("p")?.toIntOrNull() ?: it.file.height ?: 0 }
                .thenByDescending { it.file.sizeBytes },
        )
    }

    private fun matchingAliases(
        release: ParsedRelease,
        file: CloudFile,
        aliasKeys: List<Pair<TitleKey, CloudTitleAlias>>,
        includeFullTitle: Boolean,
    ): List<CloudTitleAlias> {
        val candidates = buildList {
            release.title.titleKey()?.let(::add)
            // Movies keep the keyword: `Gintama The Movie` is the title `Gintama: The Movie`.
            if (includeFullTitle && release.fullTitle != release.title) release.fullTitle.titleKey()?.let(::add)
            // A file named only `01.mkv` inside `Kotoura-san/`: the folders carry the title.
            file.folderNames().asReversed().take(3).forEach { folder ->
                CloudReleaseParser.parse(folder).title.titleKey()?.let(::add)
            }
        }
        return aliasKeys.filter { (aliasKey, _) -> candidates.any { it.matches(aliasKey) } }.map { it.second }
    }

    private fun episodeMatches(
        release: ParsedRelease,
        request: CloudMatchRequest,
        aliases: List<CloudTitleAlias>,
    ): Boolean {
        val season = request.season ?: return false
        val episode = request.episode ?: return false
        // An AniList entry tied to another season (or to specials, season 0: TMDB files OVAs such as
        // Hellsing: The Dawn under the show) is evidence for that season only.
        val relevant = aliases.filter { it.seasonHint == null || it.seasonHint == season }
        if (relevant.isEmpty()) return false
        val first = release.episode ?: return false
        val last = release.lastEpisode ?: first
        fun covers(number: Int) = number in first..last
        // OVA, OAD, SP, specials, extras, recaps and movies are TMDB season 0 only, whatever season
        // the name or its folder carries (`Show S02 OVA 1`, `Season 2/Specials/01.mkv`).
        if (release.isSpecial) return season == 0 && covers(episode)
        if (release.season != null) return release.season == season && covers(episode)
        // No season in the name: first season, an AniList entry for this season, or an absolute number.
        if (season == 1 && covers(episode)) return true
        if (relevant.any { it.seasonHint == season } && covers(episode)) return true
        val absolute = request.absoluteEpisode ?: return false
        return absolute != episode && covers(absolute)
    }

    private fun yearMatches(fileYear: Int?, requestYear: Int?): Boolean =
        fileYear == null || requestYear == null || kotlin.math.abs(fileYear - requestYear) <= 1
}

private fun CloudFile.mayContainAny(tokens: Set<String>): Boolean {
    val compact = path.foldAccents().filter { it.isLetterOrDigit() }
    return tokens.any { compact.contains(it) }
}

internal fun CloudFile.folderNames(): List<String> =
    parentPath.split('/').filter { it.isNotBlank() }

/** A title reduced to what survives punctuation, accents, case and spacing differences. */
internal class TitleKey(val compact: String, val tokens: Set<String>) {
    fun matches(other: TitleKey): Boolean {
        if (compact == other.compact) return true
        if (tokens.size < 2 || other.tokens.size < 2) return false
        val shared = tokens.intersect(other.tokens).size
        val union = tokens.union(other.tokens).size
        return shared.toDouble() / union >= 0.85
    }
}

private val articles = setOf("the", "a", "an")

internal fun String.titleKey(): TitleKey? {
    val words = foldAccents()
        .lowercase()
        .replace('&', ' ')
        .split(Regex("""[^\p{L}\p{N}]+"""))
        .filter { it.isNotBlank() }
    val meaningful = words.filterIndexed { index, word -> !(index == 0 && word in articles && words.size > 1) }
    val compact = meaningful.joinToString("")
    if (compact.length < 2) return null
    return TitleKey(compact, meaningful.toSet())
}

private val accentMap: Map<Char, Char> = buildMap {
    "áàäâãåā".forEach { put(it, 'a') }
    "éèëêē".forEach { put(it, 'e') }
    "íìïîī".forEach { put(it, 'i') }
    "óòöôõøō".forEach { put(it, 'o') }
    "úùüûū".forEach { put(it, 'u') }
    put('ñ', 'n'); put('ç', 'c'); put('ý', 'y'); put('ÿ', 'y')
}

private fun String.foldAccents(): String =
    lowercase().map { accentMap[it] ?: it }.joinToString("")

/** The episode counted from the start of the show, leaving specials out. */
internal fun absoluteEpisodeNumber(episodesPerSeason: Map<Int, Int>, season: Int, episode: Int): Int? {
    if (season <= 0 || episode <= 0) return null
    if (season == 1) return episode
    var before = 0
    for (previous in 1 until season) before += episodesPerSeason[previous] ?: return null
    return before + episode
}
