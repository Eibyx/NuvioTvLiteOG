// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import java.nio.charset.Charset
import java.util.Locale

/**
 * The font names an ASS/SSA script asks for: the `Fontname` column of every style in
 * `[V4+ Styles]`/`[V4 Styles]` and every `\fn<name>` override inside `{...}` in the events.
 *
 * Names are compared the way libass compares them: case-insensitive, without the `@` that marks
 * vertical text and without surrounding spaces. The script's own `[Fonts]` section is left alone:
 * libass reads it from the script itself.
 */
internal object AssFontNames {

    /** The script text: BOM-aware UTF-8 / UTF-16 (LE/BE); without a BOM, UTF-16 is guessed from NUL bytes. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val sample = bytes.size.coerceAtMost(512)
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until sample) {
            if (bytes[i] == 0.toByte()) if (i % 2 == 0) evenZeros++ else oddZeros++
        }
        val charset: Charset = when {
            sample >= 4 && oddZeros > sample / 4 && evenZeros == 0 -> Charsets.UTF_16LE
            sample >= 4 && evenZeros > sample / 4 && oddZeros == 0 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    fun usedFontNames(bytes: ByteArray): Set<String> = usedFontNames(decode(bytes))

    /** Normalised names (see [normalize]); empty when the script names no font. */
    fun usedFontNames(script: String): Set<String> {
        val names = linkedSetOf<String>()
        var section = ""
        var styleFontColumn = -1
        var eventTextColumn = -1
        script.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r').trimStart('﻿', ' ', '\t')
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("!:")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.lowercase(Locale.ROOT)
                styleFontColumn = -1
                eventTextColumn = -1
                return@forEach
            }
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1)
            when (section) {
                "[v4+ styles]", "[v4 styles]" -> when (key) {
                    "format" -> styleFontColumn = value.split(',').indexOfFirst {
                        it.trim().equals("fontname", ignoreCase = true)
                    }
                    "style" -> {
                        // Without a Format line both versions put Fontname second.
                        val column = if (styleFontColumn >= 0) styleFontColumn else 1
                        value.split(',').getOrNull(column)?.let(::normalize)?.let(names::add)
                    }
                }
                "[events]" -> when (key) {
                    "format" -> {
                        val columns = value.split(',').map { it.trim().lowercase(Locale.ROOT) }
                        // Text is always the last column.
                        eventTextColumn = columns.lastIndex
                    }
                    "dialogue" -> {
                        // Text is the last column and may itself contain commas.
                        val column = if (eventTextColumn > 0) eventTextColumn else 9
                        val text = value.split(',', limit = column + 1).getOrNull(column)
                        text?.let { overrideFontNames(it, names) }
                    }
                }
            }
        }
        return names
    }

    /** `\fn<name>` inside override blocks; an empty `\fn` returns to the style's font and adds nothing. */
    internal fun overrideFontNames(text: String, into: MutableSet<String>) {
        var index = 0
        while (true) {
            val open = text.indexOf('{', index)
            if (open < 0) return
            val close = text.indexOf('}', open + 1).let { if (it < 0) text.length else it }
            val block = text.substring(open + 1, close)
            var at = block.indexOf("\\fn")
            while (at >= 0) {
                val start = at + 3
                var end = start
                while (end < block.length && block[end] != '\\') end++
                normalize(block.substring(start, end))?.let(into::add)
                at = block.indexOf("\\fn", end)
            }
            index = close + 1
            if (index >= text.length) return
        }
    }

    /** Lowercase, trimmed, without the vertical-text `@`; null for an empty name. */
    fun normalize(name: String): String? =
        name.trim().removePrefix("@").trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
}
