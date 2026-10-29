package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.domain.model.Video

/** Separate overlay: importing never calls Nuvio history or another tracking provider. */
data class AniListEpisodeOverlay(
    val watched: Set<Pair<Int, Int>> = emptySet(),
    val rewatchNext: Pair<Int, Int>? = null
)

internal fun aniListOverlay(links: List<AniListLink>, entries: List<AniListEntry>, videos: List<Video>, directLinks: List<AniListLink> = emptyList()): AniListEpisodeOverlay {
    val byId = entries.associateBy(AniListEntry::mediaId)
    val episodes = videos.mapNotNull { v ->
        val s = v.season ?: return@mapNotNull null
        val e = v.episode?.takeIf { it > 0 } ?: return@mapNotNull null
        ShowEpisode(s, e)
    }.distinctBy { it.season to it.episode }
    val available = episodes.mapTo(hashSetOf()) { it.season to it.episode }
    val parts = links.flatMap { link ->
        val season = link.key.substringAfterLast("|s", "").toIntOrNull() ?: return@flatMap emptyList()
        importLinkedSeason(link, season, byId, episodes)
    }
    val watched = parts.flatMap { it.episodes }.map { it.season to it.episode }.filterTo(linkedSetOf()) { it in available }
    var directResume: Pair<Int, Int>? = null
    // AIOmetadata may put several MAL/Kitsu entries in a single TV season. Its video ID is
    // the verified entry identity; do not apply the first part's prefix to all later videos.
    for (video in videos) {
        val s = video.season ?: continue
        val e = video.episode?.takeIf { it > 0 } ?: continue
        val direct = parseAniListAnimeVideoId(video.id) ?: continue
        val ownEpisode = direct.episode?.takeIf { it > 0 } ?: continue
        val entry = entries.firstOrNull { entry -> when (direct.prefix) {
            "anilist" -> entry.mediaId == direct.id
            "mal" -> entry.media?.idMal == direct.id
            else -> false
        } }
        // For mapped Kitsu/AniDB ids refreshTitle persists a direct entry link per video identity.
        val linked = directLinks.firstOrNull { it.key == "${direct.prefix}:${direct.id}|s1" }
        val resolved = entry ?: linked?.parts?.singleOrNull()?.mediaId?.let(byId::get)
        watched.remove(s to e)
        if (resolved != null && (s > 0 || resolved.media?.format in setOf("SPECIAL", "OVA"))) {
            if (ownEpisode <= aniListWatchedCount(resolved, resolved.media?.episodes)) watched.add(s to e)
            if (resolved.status == AniListListStatus.REPEATING && ownEpisode == resolved.progress + 1) directResume = s to e
        }
    }
    return AniListEpisodeOverlay(
        watched = watched,
        rewatchNext = directResume ?: parts.firstNotNullOfOrNull { it.resume }?.let { it.season to it.episode }?.takeIf { it in available }
    )
}

internal fun aniListNextEpisode(videos: List<Video>, overlay: AniListEpisodeOverlay): Video? {
    overlay.rewatchNext?.let { (s, e) -> videos.firstOrNull { it.season == s && it.episode == e && it.available != false }?.let { return it } }
    return videos.filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 && it.available != false }
        .sortedWith(compareBy({ it.season }, { it.episode }))
        .firstOrNull { (it.season!! to it.episode!!) !in overlay.watched }
}

internal fun TrackingMediaReference.toPendingSend(): AniListPendingSend {
    val type = catalog?.contentType ?: "series"
    val id = catalog?.contentId ?: ids.imdb ?: stableKey
    return AniListPendingSend(aniListSendKey(id, type, episode?.season), title ?: id, type, id,
        catalog?.videoId, episode?.season, episode?.number)
}

internal fun AniListPendingSend.toReference() = buildTrackingMediaReference(
    contentType, contentId, videoId, title, seasonNumber = season, episodeNumber = episode
)

/** TV safety adapter: keep Mobile's verified ARM plan, refuse its inferred-season fallback. */
internal fun verifiedAniListLinkPlan(candidates: List<AniListSeasonCandidate>, season: Int?, movie: Boolean): AniListLinkPlan? {
    if (candidates.any { it.format == null }) return null
    if (!movie && candidates.none { it.tvdbSeason == (season ?: 1) } && !needsAbsoluteEpisode(candidates, false)) return null
    if (!movie && season == 0 && candidates.none { it.tvdbSeason == 0 }) return null
    val plan = aniListLinkPlan(candidates, season, movie) ?: return null
    if (plan.parts.dropLast(1).any { it.episodes == null || it.episodes <= 0 }) return null
    if (plan.parts.size > 1) {
        val selected = candidates.filter { c -> plan.parts.any { it.mediaId == c.mediaId } }
        if (selected.any { it.startKey == Long.MAX_VALUE || it.startKey >= 99990000 } || selected.map { it.startKey }.distinct().size != selected.size) return null
    }
    return plan
}
