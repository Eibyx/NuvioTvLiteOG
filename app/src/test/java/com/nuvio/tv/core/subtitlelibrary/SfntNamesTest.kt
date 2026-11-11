// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SfntNamesTest {
    private val fontDir = listOf(File("src/main/res/font"), File("app/src/main/res/font")).first { it.isDirectory }

    @Test
    fun `real bundled TTF exposes its family`() {
        // LiteOG bundles Inter (Arc's original test read its Montserrat Bold).
        val faces = SfntNames.read(File(fontDir, "inter_variable.ttf"))
        assertEquals(1, faces.size)
        assertTrue(faces.single().names.toString(), faces.single().names.any { it.startsWith("inter") })
    }

    @Test
    fun `TTF and OTF names from the Windows and Mac platforms`() {
        val names = SfntTestFonts.windowsNames("Gandhi Sans", "Gandhi Sans Bold", "GandhiSans-Bold", "Gandhi Sans") +
            SfntTestFonts.Name(1, 0, 4, "Gandhi Sans Mac")
        val ttf = SfntNames.read(SfntTestFonts.ttf(names)).single()
        assertEquals(setOf("gandhi sans", "gandhi sans bold", "gandhisans-bold", "gandhi sans mac"), ttf.names)
        assertEquals("gandhi sans", ttf.family)
        val otf = SfntNames.read(SfntTestFonts.otf(SfntTestFonts.windowsNames("Komika", "Komika Axis", "KomikaAxis"))).single()
        assertEquals(setOf("komika", "komika axis", "komikaaxis"), otf.names)
    }

    @Test
    fun `typographic family wins as the family`() {
        val face = SfntNames.read(SfntTestFonts.ttf(SfntTestFonts.windowsNames("Foo Light", "Foo Light", "Foo-Light", "Foo"))).single()
        assertEquals("foo", face.family)
    }

    @Test
    fun `every face of a TTC`() {
        val ttc = SfntTestFonts.ttc(
            SfntTestFonts.windowsNames("MS Gothic", "MS Gothic", "MS-Gothic"),
            SfntTestFonts.windowsNames("MS PGothic", "MS PGothic", "MS-PGothic")
        )
        val faces = SfntNames.read(ttc)
        assertEquals(listOf(0, 1), faces.map { it.faceIndex })
        assertTrue("ms pgothic" in faces[1].names)
    }

    @Test
    fun `garbage and truncated files give no faces`() {
        assertTrue(SfntNames.read(ByteArray(3)).isEmpty())
        assertTrue(SfntNames.read("not a font at all, really".toByteArray()).isEmpty())
        val ttf = SfntTestFonts.ttf(SfntTestFonts.windowsNames("A", "A", "A"))
        assertTrue(SfntNames.read(ttf.copyOf(ttf.size - 4)).isEmpty())
    }
}
