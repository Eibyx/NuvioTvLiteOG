package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from NuvioMobileKai's FileDefaultSubtitleTrackTest. */
class FileDefaultSubtitleTrackTest {
    private val japanese = TrackInfo(index = 0, name = "Japanese", language = "jpn", isSelected = true)
    private val tracks = listOf(
        TrackInfo(index = 0, name = "Signs", language = "eng", isForced = true, isDefault = true),
        TrackInfo(index = 1, name = "Full Subtitles", language = null, isDefault = true),
        TrackInfo(index = 2, name = "Other", language = "chi")
    )

    @Test
    fun theFilesDefaultTrackShowsWhenNothingIsInThePreferredLanguage() {
        assertEquals(1, findFileDefaultSubtitleTrackIndex(tracks, listOf("es"), japanese))
    }

    @Test
    fun genericSpanishWhenLatinAmericanSpanishIsPreferred() {
        val fansub = listOf(TrackInfo(index = 0, name = "Animo-Alonso18p", language = "spa"))
        assertEquals(0, findFileDefaultSubtitleTrackIndex(fansub, listOf("es-419"), japanese))
    }

    @Test
    fun theTrackThePlayerSelectedOrTheOnlyOne() {
        val untagged = listOf(
            TrackInfo(index = 0, name = "A", language = null),
            TrackInfo(index = 1, name = "B", language = null, isSelected = true)
        )
        assertEquals(1, findFileDefaultSubtitleTrackIndex(untagged, listOf("es-419"), japanese))
        assertEquals(0, findFileDefaultSubtitleTrackIndex(untagged.take(1), listOf("es-419"), japanese))
    }

    @Test
    fun notWhenTheAudioIsAlreadyInThePreferredLanguage() {
        val spanish = TrackInfo(index = 0, name = "Español", language = "spa", isSelected = true)
        assertEquals(-1, findFileDefaultSubtitleTrackIndex(tracks, listOf("es"), spanish))
        assertEquals(-1, findFileDefaultSubtitleTrackIndex(tracks, listOf("es-419"), spanish))
    }

    @Test
    fun notWhenSubtitlesAreOffOrOnlyForcedTracksExist() {
        assertEquals(-1, findFileDefaultSubtitleTrackIndex(tracks, emptyList(), japanese))
        assertEquals(-1, findFileDefaultSubtitleTrackIndex(tracks.take(1), listOf("es"), japanese))
    }
}
