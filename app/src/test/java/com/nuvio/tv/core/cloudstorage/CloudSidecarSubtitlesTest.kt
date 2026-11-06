package com.nuvio.tv.core.cloudstorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudSidecarSubtitlesTest {
    private fun file(path: String) = CloudFile(path, path, path.substringAfterLast('/'), 1, 0, path.substringBeforeLast('/'))

    private val video = file("/ANIME/Juniku/[VCB-Studio] Fantasy Bishoujo Juniku Ojisan to 02 [Ma10p_1080p][x265_flac].mkv")

    @Test
    fun subtitlesNamedAfterTheVideoBelongToIt() {
        val subtitles = listOf(
            file("/ANIME/Juniku/[VCB-Studio] Fantasy Bishoujo Juniku Ojisan to 02 [Ma10p_1080p][x265_flac].spa.ass"),
            file("/ANIME/Juniku/[VCB-Studio] Fantasy Bishoujo Juniku Ojisan to 02 [Ma10p_1080p][x265_flac].chs.ass"),
            file("/ANIME/Juniku/[VCB-Studio] Fantasy Bishoujo Juniku Ojisan to 03 [Ma10p_1080p][x265_flac].spa.ass"),
            file("/ANIME/Other/[VCB-Studio] Fantasy Bishoujo Juniku Ojisan to 02 [Ma10p_1080p][x265_flac].spa.ass"),
        )
        val found = CloudSidecarSubtitles.find(video, subtitles)
        assertEquals(listOf("spa", "chi"), found.map { it.language })
        assertTrue(found.first().label.startsWith("SPA"))
    }

    @Test
    fun differentlyNamedSubtitlesMatchByTitleAndEpisode() {
        val found = CloudSidecarSubtitles.find(video, listOf(file("/ANIME/Juniku/Fantasy Bishoujo Juniku Ojisan to - 02 [Español].srt")))
        assertEquals(listOf("spa"), found.map { it.language })
    }

    @Test
    fun unknownLanguageStaysUndetermined() {
        assertEquals("und", CloudSidecarSubtitles.languageOf(""))
        assertEquals("eng", CloudSidecarSubtitles.languageOf("en"))
        assertEquals("spa", CloudSidecarSubtitles.languageOf("forced.es"))
    }
}
