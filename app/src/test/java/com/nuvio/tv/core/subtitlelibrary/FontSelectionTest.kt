// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontSelectionTest {
    private fun font(key: String, size: Long, vararg names: String, family: String? = names.firstOrNull()) =
        FontCandidate(key, "$key.ttf", size, listOf(FontFaceNames(0, family, names.toSet())))

    private val mb = 1024L * 1024

    @Test
    fun `all faces of a used family and nothing else`() {
        val regular = font("gandhi", mb, "gandhi sans", "gandhi sans regular")
        val bold = font("gandhi-bold", mb, "gandhi sans bold", family = "gandhi sans")
        val italic = font("gandhi-italic", mb, "gandhi sans italic", family = "gandhi sans")
        val other = font("komika", mb, "komika axis")
        val plan = FontSelection.plan(setOf("gandhi sans"), listOf(other, italic, regular, bold))
        assertEquals(setOf("gandhi", "gandhi-bold", "gandhi-italic"), plan.selected.map { it.key }.toSet())
        assertEquals("gandhi", plan.selected.first().key)
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `missing names are reported`() {
        val plan = FontSelection.plan(setOf("komika axis", "arial"), listOf(font("komika", mb, "komika axis")))
        assertEquals(setOf("arial"), plan.missing)
    }

    @Test
    fun `size limits per file and in total`() {
        val limits = FontLimits(maxTotalBytes = 5 * mb, maxFileBytes = 3 * mb)
        val huge = font("huge", 4 * mb, "a")
        val one = font("one", 3 * mb, "b")
        val two = font("two", 3 * mb, "c")
        val plan = FontSelection.plan(setOf("a", "b", "c"), listOf(huge, one, two), limits)
        assertEquals(listOf("one"), plan.selected.map { it.key })
        assertEquals(setOf("huge", "two"), plan.skippedForSize.map { it.key }.toSet())
    }

    @Test
    fun `unreadable fonts load blind only in small folders`() {
        val broken = FontCandidate("broken", "broken.ttf", mb, null)
        val small = FontSelection.plan(setOf("x"), listOf(broken))
        assertEquals(listOf("broken"), small.selected.map { it.key })
        assertTrue(small.missing.isEmpty())
        val many = (1..6).map { FontCandidate("b$it", "b$it.ttf", mb, null) }
        val big = FontSelection.plan(setOf("x"), many)
        assertTrue(big.selected.isEmpty())
        assertEquals(setOf("x"), big.missing)
    }

    @Test
    fun `fonts whose file name looks like a used font are fetched first`() {
        val order = FontSelection.fetchOrder(setOf("gandhi sans", "komika axis"), listOf("zzz.ttf", "KomikaAxis.ttf", "GandhiSans-Bold.otf"))
        assertEquals("zzz.ttf", order.last())
    }
}
