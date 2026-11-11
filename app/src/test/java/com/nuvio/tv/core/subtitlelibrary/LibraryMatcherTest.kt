// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMatcherTest {
    private fun folder(name: String) = LibraryEntry(id = "id-$name", name = name, isFolder = true)
    private fun file(name: String) = LibraryEntry(id = "id-$name", name = name, isFolder = false, sizeBytes = 10)
    private fun sub(name: String, vararg subfolders: String) =
        EpisodeSubtitle(file(name), subfolders.toList(), folder(subfolders.lastOrNull() ?: "anime"))

    private val roots = listOf(folder("Frieren"), folder("Dandadan"), folder("Sousou no Frieren Extras"), folder("Kusuriya no Hitorigoto"))

    @Test
    fun `folder by title or by alias`() {
        val byTitle = LibraryMatcher.animeFolder(roots, listOf("Dandadan"), null)
        assertEquals("Dandadan", (byTitle as AnimeFolderMatch.Found).folder.name)
        val byAlias = LibraryMatcher.animeFolder(roots, listOf("The Apothecary Diaries", "Kusuriya no Hitorigoto"), null)
        assertEquals("Kusuriya no Hitorigoto", (byAlias as AnimeFolderMatch.Found).folder.name)
    }

    @Test
    fun `a linked folder wins and ambiguity is not guessed`() {
        val linked = LibraryMatcher.animeFolder(roots, listOf("Dandadan"), "id-Frieren")
        assertTrue(linked is AnimeFolderMatch.Found && linked.linked && linked.folder.name == "Frieren")
        val twins = listOf(folder("Fate Zero"), folder("Fate-Zero"), folder("Fate Zero 2"))
        val result = LibraryMatcher.animeFolder(twins.drop(2) + listOf(folder("Fate  Zero"), folder("Fate_Zero")), listOf("Fate Zero"), null)
        assertTrue(result.toString(), result is AnimeFolderMatch.Ambiguous)
        assertEquals(AnimeFolderMatch.None, LibraryMatcher.animeFolder(roots, listOf("Naruto"), null))
    }

    @Test
    fun `episode in the folder root and in season folders`() {
        val files = listOf(
            sub("[Grupo] Frieren - 01.ass"),
            sub("[Grupo] Frieren - 02.ass"),
            sub("FuenteA.ttf")
        )
        assertEquals(listOf("[Grupo] Frieren - 02.ass"), LibraryMatcher.episodeSubtitles(files, 1, 2, 2).map { it.file.name })
        val seasons = listOf(
            sub("Dandadan - 03.ass", "Temporada 1"),
            sub("Dandadan S2 - 03.ass", "Temporada 2"),
            sub("Dandadan - 03 [Carteles].ass", "Season 2")
        )
        assertEquals(
            setOf("Dandadan S2 - 03.ass", "Dandadan - 03 [Carteles].ass"),
            LibraryMatcher.episodeSubtitles(seasons, 2, 3, 15).map { it.file.name }.toSet()
        )
        assertEquals(listOf("Dandadan - 03.ass"), LibraryMatcher.episodeSubtitles(seasons, 1, 3, 3).map { it.file.name })
    }

    @Test
    fun `absolute numbering when the files carry no season`() {
        val files = listOf(sub("One Piece - 1071.ass"), sub("One Piece - 0003.ass"))
        assertEquals(listOf("One Piece - 1071.ass"), LibraryMatcher.episodeSubtitles(files, 21, 179, 1071).map { it.file.name })
        assertTrue(LibraryMatcher.episodeSubtitles(files, 21, 179, null).isEmpty())
    }

    @Test
    fun `several candidates are all offered`() {
        val files = listOf(sub("Frieren - 05 [Español].ass"), sub("Frieren - 05 [Carteles].ass"), sub("Frieren - 06.ass"))
        assertEquals(2, LibraryMatcher.episodeSubtitles(files, 1, 5, 5).size)
    }

    @Test
    fun `language from the file name`() {
        assertEquals("es", LibraryMatcher.languageOf("Frieren - 01.es.ass"))
        assertEquals("es", LibraryMatcher.languageOf("Frieren - 01 [Español].ass"))
        assertEquals("en", LibraryMatcher.languageOf("Frieren - 01 English.ass"))
        assertEquals(null, LibraryMatcher.languageOf("Frieren - 01.ass"))
    }

    @Test
    fun `font folders`() {
        assertTrue(LibraryMatcher.isFontFolder("Fonts"))
        assertTrue(LibraryMatcher.isFontFolder("fuentes"))
        assertTrue(LibraryMatcher.isFontFolder("Attachments"))
        assertTrue(!LibraryMatcher.isFontFolder("Temporada 2"))
    }
}
