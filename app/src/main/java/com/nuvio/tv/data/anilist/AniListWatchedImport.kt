package com.nuvio.tv.data.anilist

import kotlin.math.abs

/** One episode of a show as its detail page numbers it, with its air date as an epoch day. */
internal data class ShowEpisode(val season: Int, val episode: Int, val airDay: Long? = null)

/** A season episode of the detail page. */
internal data class SeasonEpisode(val season: Int, val episode: Int)

/**
 * What one AniList entry says, in the detail page's numbering: which episodes are watched, how many
 * of how many ("AniList: 11 of 11 watched") and, while rewatching, where to resume.
 */
internal data class AniListImportedPart(
    val mediaId: Long,
    val title: String,
    val status: String?,
    val watched: Int,
    val total: Int?,
    val episodes: List<SeasonEpisode>,
    /** Rewatching: the episode the rewatch is at, so playback resumes there. */
    val resume: SeasonEpisode? = null,
)

/**
 * How many episodes of an entry count as watched: everything when Completed or Rewatching (a
 * rewatch means it was all seen once), the saved progress otherwise, nothing when there is no
 * entry. Never more than the part has.
 */
internal fun aniListWatchedCount(entry: AniListEntry?, partEpisodes: Int?): Int {
    entry ?: return 0
    val total = partEpisodes?.takeIf { it > 0 } ?: entry.media?.episodes?.takeIf { it > 0 }
    val count = when (entry.status) {
        AniListListStatus.COMPLETED, AniListListStatus.REPEATING -> total ?: entry.effectiveProgress()
        else -> entry.progress
    }
    return if (total != null) count.coerceIn(0, total) else count.coerceAtLeast(0)
}

/**
 * The inverse of the send mapping ([AniListLinkPlan.target]): each linked part's progress becomes
 * season episodes of the detail page. Part 2 of Mushoku Tensei starts at S1E12, so its progress 12
 * marks S1E12-E23. An absolute link (One Piece) walks the show's episodes in order; without that
 * list it cannot place anything.
 */
internal fun importLinkedSeason(
    link: AniListLink,
    season: Int,
    entries: Map<Long, AniListEntry?>,
    showEpisodes: List<ShowEpisode> = emptyList(),
): List<AniListImportedPart> {
    val ordered = if (link.absolute) showEpisodes.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode })) else emptyList()
    return link.parts.map { part ->
        val entry = entries[part.mediaId]
        val total = part.episodes?.takeIf { it > 0 } ?: entry?.media?.episodes?.takeIf { it > 0 }
        val count = aniListWatchedCount(entry, total)
        val episodes = if (link.absolute) {
            ordered.take(count).map { SeasonEpisode(it.season, it.episode) }
        } else {
            (0 until count).map { SeasonEpisode(season, part.firstEpisode + it) }
        }
        val resume = entry?.takeIf { it.status == AniListListStatus.REPEATING && (total == null || it.progress < total) }?.let { rewatch ->
            if (link.absolute) {
                ordered.getOrNull(rewatch.progress)?.let { SeasonEpisode(it.season, it.episode) }
            } else {
                SeasonEpisode(season, part.firstEpisode + rewatch.progress)
            }
        }
        AniListImportedPart(
            mediaId = part.mediaId,
            title = part.title,
            status = entry?.status,
            watched = count,
            total = total,
            episodes = episodes,
            resume = resume,
        )
    }
}

/**
 * When no saved link or season table places an entry: the block of the show's episodes that aired
 * when the entry did. The entry's first episode is matched to the episode that aired closest to its
 * start date (within [toleranceDays]), and the block runs for [count] episodes from there, in
 * airing order. Null when nothing aired close enough.
 */
internal fun aniListDateBlock(
    showEpisodes: List<ShowEpisode>,
    startDay: Long,
    count: Int,
    toleranceDays: Long = 10,
): List<SeasonEpisode>? {
    if (count <= 0) return null
    val aired = showEpisodes
        .filter { it.season > 0 && it.airDay != null }
        .sortedWith(compareBy({ it.airDay }, { it.season }, { it.episode }))
    val first = aired.minByOrNull { abs(it.airDay!! - startDay) } ?: return null
    if (abs(first.airDay!! - startDay) > toleranceDays) return null
    val from = aired.indexOf(first)
    return aired.drop(from).take(count).map { SeasonEpisode(it.season, it.episode) }
}

/** yyyymmdd (as [AniListMedia.startKey] gives it) to an epoch day; null for unknown parts. */
internal fun aniListStartDay(media: AniListMedia): Long? {
    val date = media.startDate ?: return null
    return epochDay(date.year ?: return null, date.month ?: return null, date.day ?: 1)
}

/** "2021-01-11" or "2021-01-11T00:00:00.000Z" to an epoch day. */
internal fun releasedEpochDay(released: String?): Long? {
    val text = released?.trim()?.takeIf { it.length >= 10 } ?: return null
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(5, 7).toIntOrNull() ?: return null
    val day = text.substring(8, 10).toIntOrNull() ?: return null
    return epochDay(year, month, day)
}

/** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's days_from_civil). */
internal fun epochDay(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val m = month.toLong()
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}
