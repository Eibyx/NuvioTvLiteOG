// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Minimal sfnt fonts (only a `name` table) for the parser tests. */
internal object SfntTestFonts {
    data class Name(val platform: Int, val encoding: Int, val nameId: Int, val value: String)

    fun windowsNames(family: String, full: String, postscript: String, typographic: String? = null) = listOfNotNull(
        Name(3, 1, 1, family),
        Name(3, 1, 4, full),
        Name(3, 1, 6, postscript),
        typographic?.let { Name(3, 1, 16, it) }
    )

    fun nameTable(names: List<Name>): ByteArray {
        val strings = ByteArrayOutputStream()
        val records = names.map { name ->
            val bytes = if (name.platform == 1) name.value.toByteArray(Charsets.ISO_8859_1) else name.value.toByteArray(Charsets.UTF_16BE)
            val offset = strings.size()
            strings.write(bytes)
            Triple(name, offset, bytes.size)
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeShort(0)
            writeShort(records.size)
            writeShort(6 + records.size * 12)
            records.forEach { (name, offset, length) ->
                writeShort(name.platform); writeShort(name.encoding); writeShort(if (name.platform == 1) 0 else 0x409)
                writeShort(name.nameId); writeShort(length); writeShort(offset)
            }
            write(strings.toByteArray())
        }
        return out.toByteArray()
    }

    /** One face at [base] within a larger file: offset table + directory + name table. */
    private fun face(version: Int, names: List<Name>, base: Int): ByteArray {
        val table = nameTable(names)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(version); writeShort(1); writeShort(16); writeShort(0); writeShort(0)
            writeInt(0x6E616D65); writeInt(0); writeInt(base + 12 + 16); writeInt(table.size)
            write(table)
        }
        return out.toByteArray()
    }

    fun ttf(names: List<Name>): ByteArray = face(0x00010000, names, 0)

    fun otf(names: List<Name>): ByteArray = face(0x4F54544F, names, 0)

    fun ttc(vararg faces: List<Name>): ByteArray {
        val headerSize = 12 + faces.size * 4
        val bodies = mutableListOf<ByteArray>()
        var offset = headerSize
        val offsets = faces.map { names ->
            val body = face(0x00010000, names, offset)
            bodies += body
            val at = offset
            offset += body.size
            at
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x74746366); writeInt(0x00010000); writeInt(faces.size)
            offsets.forEach(::writeInt)
            bodies.forEach(::write)
        }
        return out.toByteArray()
    }
}
