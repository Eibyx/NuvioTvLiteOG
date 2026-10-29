package com.nuvio.tv.data.anilist

/** Mobile's read-before-write transaction and progress/status rules, isolated for transport tests. */
internal class AniListProgressSender(private val api: AniListApiClient) {
    suspend fun send(account: AniListAuthScope, target: AniListTarget,
        saved: suspend (AniListEntry, AniListMedia) -> Unit = { _, _ -> }): AniListDelivery {
        val (media, current) = api.mediaById(account, target.mediaId)
            ?: return AniListDelivery.NotFound("Entrada AniList ausente")
        val update = aniListProgressUpdate(current, target.episode, media.totalEpisodeCount())
            ?: return AniListDelivery.AlreadyUpToDate
        // A finite entry cannot establish the mapping of a later cour/season. In particular,
        // do not clamp an unknown absolute episode onto the end of an earlier AniList entry.
        if (media.totalEpisodeCount() > 0 && target.episode > media.totalEpisodeCount())
            return AniListDelivery.NotFound("Episodio fuera del rango verificado de la entrada AniList")
        val entry = api.saveEntry(account, target.mediaId, status = update.status, progress = update.progress)
        saved(entry, media)
        return AniListDelivery.Sent(media.displayTitle(), entry.progress)
    }
}
