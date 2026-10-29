package com.nuvio.tv.data.anilist

private val SEASON_FORMATS = setOf("TV", "TV_SHORT", "ONA")

/**
 * One AniList entry that ARM maps to an IMDb id, with the TVDB season ARM files it under.
 * Several entries share a season when AniList splits it into parts or cours.
 */
internal data class AniListSeasonCandidate(
    val mediaId: Long,
    val format: String?,
    val tvdbSeason: Int?,
    /** Null while airing or unknown: the entry then takes every remaining episode. */
    val episodes: Int?,
    /** yyyymmdd of the first airing, for ordering parts. */
    val startKey: Long,
    val title: String = "",
)

/**
 * The AniList entries one season of a show (or a movie) is bound to: usually one, several when
 * AniList splits the season into parts or cours. Built once when the user starts watching, saved,
 * and reused by every later sync (see [AniListLink]).
 */
internal data class AniListLinkPlan(
    val parts: List<AniListLinkPart>,
    /** One entry counting the whole show (One Piece): episodes go across seasons. */
    val absolute: Boolean = false,
)

/**
 * Binds a show's [season] (or a movie) to its AniList entries:
 * - parts and cours of one TVDB season (Attack on Titan S3, Spy x Family S1, Re:Zero S2) in airing
 *   order, each starting where the previous one ends;
 * - specials (season 0) only bind to entries ARM files under season 0;
 * - one long-running entry without season data (One Piece) binds with absolute numbering;
 * - movies bind to the first movie entry.
 * Returns null when no entry fits, so the caller reports "not found" rather than guessing.
 */
internal fun aniListLinkPlan(
    candidates: List<AniListSeasonCandidate>,
    season: Int?,
    isMovie: Boolean,
): AniListLinkPlan? {
    if (candidates.isEmpty()) return null
    if (isMovie) {
        val movie = candidates.filter { it.format.equals("MOVIE", ignoreCase = true) }.minByOrNull { it.startKey }
            ?: candidates.singleOrNull()
            ?: return null
        return AniListLinkPlan(listOf(movie.toPart(firstEpisode = 1)))
    }
    val wantedSeason = season ?: 1
    val episodic = candidates
        .filterNot { it.format.equals("MOVIE", ignoreCase = true) }
        .sortedWith(compareBy({ it.startKey }, { it.mediaId }))
    if (episodic.isEmpty()) return null

    val inSeason = episodic.filter { it.tvdbSeason == wantedSeason }.let { entries ->
        if (wantedSeason == 0) entries else entries.filter { it.isSeasonFormat }.ifEmpty { entries }
    }
    if (inSeason.isNotEmpty()) {
        var first = 1
        val parts = inSeason.mapIndexed { index, entry ->
            entry.toPart(first).also {
                val count = entry.episodes?.takeIf { it > 0 }
                // A part without an episode count (still airing) takes every later episode.
                first = if (count == null || index == inSeason.lastIndex) first else first + count
            }
        }
        return AniListLinkPlan(parts)
    }
    if (wantedSeason == 0) return null

    val series = episodic.filter { it.isSeasonFormat }
    if (series.isEmpty()) return null
    if (series.size == 1 && series.single().tvdbSeason == null) {
        return AniListLinkPlan(listOf(series.single().toPart(firstEpisode = 1)), absolute = true)
    }
    if (series.all { it.tvdbSeason == null }) {
        // No season data at all: the n-th series entry, as before ARM carried seasons.
        return AniListLinkPlan(listOf(series[(wantedSeason - 1).coerceIn(0, series.lastIndex)].toPart(firstEpisode = 1)))
    }
    return null
}

/** The entry and its own episode number for [episode] (or the show-wide [absoluteEpisode]). */
internal fun AniListLinkPlan.target(episode: Int?, absoluteEpisode: Int? = null): AniListTarget =
    targetIn(parts, absolute, episode, absoluteEpisode)

internal fun targetIn(
    parts: List<AniListLinkPart>,
    absolute: Boolean,
    episode: Int?,
    absoluteEpisode: Int?,
): AniListTarget {
    val wanted = (episode ?: 1).coerceAtLeast(1)
    if (absolute) return AniListTarget(parts.first().mediaId, (absoluteEpisode ?: wanted).coerceAtLeast(1))
    val part = parts.lastOrNull { it.firstEpisode <= wanted } ?: parts.first()
    return AniListTarget(part.mediaId, (wanted - part.firstEpisode + 1).coerceAtLeast(1))
}

/** True when [episode] lies past every part of a bound season whose parts all have a known size. */
internal fun List<AniListLinkPart>.endsBefore(episode: Int): Boolean {
    val last = lastOrNull() ?: return true
    val count = last.episodes?.takeIf { it > 0 } ?: return false
    return episode > last.firstEpisode + count - 1
}

private fun AniListSeasonCandidate.toPart(firstEpisode: Int) = AniListLinkPart(
    mediaId = mediaId,
    title = title,
    firstEpisode = firstEpisode,
    episodes = episodes,
)

/** Picks the AniList entry and its own episode number; see [aniListLinkPlan]. */
internal fun chooseAniListTarget(
    candidates: List<AniListSeasonCandidate>,
    season: Int?,
    episode: Int?,
    isMovie: Boolean,
    absoluteEpisode: Int? = null,
): AniListTarget? {
    val plan = aniListLinkPlan(candidates, season, isMovie) ?: return null
    return if (isMovie) AniListTarget(plan.parts.first().mediaId, 1) else plan.target(episode, absoluteEpisode)
}

/** True when [chooseAniListTarget] would count episodes across the whole show. */
internal fun needsAbsoluteEpisode(candidates: List<AniListSeasonCandidate>, isMovie: Boolean): Boolean {
    if (isMovie) return false
    val series = candidates.filter { it.isSeasonFormat }
    return series.size == 1 && series.single().tvdbSeason == null
}

private val AniListSeasonCandidate.isSeasonFormat: Boolean
    get() = format?.uppercase() in SEASON_FORMATS

internal fun AniListMedia.startKey(): Long {
    val date = startDate
    return ((date?.year ?: 9999).toLong() * 10_000L) + ((date?.month ?: 12) * 100L) + (date?.day ?: 31)
}

/** The status and progress to save when [episode] of an entry is watched, or null to leave it. */
internal data class AniListProgressUpdate(val status: String, val progress: Int)

/**
 * The list rules for a watched episode:
 * - no entry, Planning or any other status becomes Watching;
 * - the last episode makes it Completed;
 * - Rewatching stays Rewatching until the last episode; nothing ever turns an entry into
 *   Rewatching;
 * - a Completed entry is never written again: saving it again shows up on AniList as watched again;
 * - progress never goes back, and the comparison uses the progress as it would be saved (cut to
 *   the entry's episode count): episode 23 of a season reaching an 11-episode entry at 11 is no
 *   change. Null means "send nothing".
 */
internal fun aniListProgressUpdate(current: AniListEntry?, episode: Int, totalEpisodes: Int): AniListProgressUpdate? {
    if (episode <= 0) return null
    val progress = if (totalEpisodes > 0) episode.coerceAtMost(totalEpisodes) else episode
    if (current != null) {
        if (current.status == AniListListStatus.COMPLETED) return null
        if (current.progress >= progress) return null
    }
    val status = when {
        totalEpisodes > 0 && progress >= totalEpisodes -> AniListListStatus.COMPLETED
        current?.status == AniListListStatus.REPEATING -> AniListListStatus.REPEATING
        else -> AniListListStatus.CURRENT
    }
    return AniListProgressUpdate(status, progress)
}
