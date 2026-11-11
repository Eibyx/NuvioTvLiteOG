// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.ui.screens.player

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFontRegistryTest {
    private fun font(dir: File, name: String, size: Int): Pair<String, File> =
        name to File(dir, name).apply { writeBytes(ByteArray(size) { 7 }) }

    @Test
    fun `fonts go to the parser of the right external subtitle only`() {
        val dir = Files.createTempDirectory("kai-fonts").toFile()
        val registry = LibraryFontRegistry()
        val frieren = "nuvio-addon-sub:kai-lib-aaaa:1f"
        val other = "nuvio-addon-sub:kai-lib-bbbb:2f"
        registry.register(frieren, listOf(font(dir, "A.ttf", 10), font(dir, "B.otf", 20)))
        registry.register(other, listOf(font(dir, "C.ttf", 30)))
        val added = mutableListOf<Pair<String, Int>>()
        assertEquals(2, registry.addFontsFor(frieren) { name, data -> added += name to data.size })
        assertEquals(listOf("A.ttf" to 10, "B.otf" to 20), added)
        added.clear()
        // Track groups may carry a "<period>:" prefix.
        assertEquals(1, registry.addFontsFor("1:$other") { name, data -> added += name to data.size })
        assertEquals(listOf("C.ttf" to 30), added)
        assertEquals(0, registry.addFontsFor("nuvio-addon-sub:addon-es:3f") { _, _ -> error("not ours") })
        assertEquals(0, registry.addFontsFor(null) { _, _ -> error("no id") })
        dir.deleteRecursively()
    }

    @Test
    fun `a new episode starts with nothing`() {
        val dir = Files.createTempDirectory("kai-fonts").toFile()
        val registry = LibraryFontRegistry()
        registry.register("nuvio-addon-sub:kai-lib-aaaa:1f", listOf(font(dir, "A.ttf", 10)))
        registry.clear()
        assertTrue(registry.isEmpty())
        assertEquals(0, registry.addFontsFor("nuvio-addon-sub:kai-lib-aaaa:1f") { _, _ -> error("leaked") })
        dir.deleteRecursively()
    }

    @Test
    fun `an unreadable font is skipped and the others still go in`() {
        val dir = Files.createTempDirectory("kai-fonts").toFile()
        val registry = LibraryFontRegistry()
        registry.register("t", listOf("gone.ttf" to File(dir, "gone.ttf"), font(dir, "ok.ttf", 5)))
        val names = mutableListOf<String>()
        assertEquals(1, registry.addFontsFor("t") { name, _ -> names += name })
        assertEquals(listOf("ok.ttf"), names)
        dir.deleteRecursively()
    }
}
