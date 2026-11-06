package com.nuvio.tv.ui.screens.player

/**
 * The file's own subtitle to show when no track or add-on subtitle is in the preferred language
 * (ported from NuvioMobileKai's `findFileDefaultSubtitleTrackIndex`). Used for cloud files only,
 * whose fansub releases often ship a single full subtitle track flagged as default.
 *
 * In order: a full (non-forced) track with the same base language as a target (`spa` for
 * `es-419`), the track the file flags as default, the track the engine already selected, or the
 * only full track. Nothing (-1) when no subtitle language is preferred (subtitles off) or when the
 * audio is already in the preferred language.
 */
internal fun findFileDefaultSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>,
    selectedAudioTrack: TrackInfo?
): Int {
    val primary = targets.firstOrNull() ?: return -1
    if (selectedAudioTrack != null && baseLanguage(selectedAudioTrack.language) == baseLanguage(primary)) return -1
    val full = subtitleTracks.filterNot { it.isForced }
    if (full.isEmpty()) return -1
    for (target in targets) {
        val base = baseLanguage(target) ?: continue
        full.firstOrNull { baseLanguage(it.language) == base }?.let { return it.index }
    }
    full.firstOrNull { it.isDefault }?.let { return it.index }
    full.firstOrNull { it.isSelected }?.let { return it.index }
    return full.singleOrNull()?.index ?: -1
}

private fun baseLanguage(language: String?): String? =
    language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { PlayerSubtitleUtils.normalizeLanguageCode(it) }
        ?.substringBefore('-')
        ?.takeIf { it.isNotBlank() }
