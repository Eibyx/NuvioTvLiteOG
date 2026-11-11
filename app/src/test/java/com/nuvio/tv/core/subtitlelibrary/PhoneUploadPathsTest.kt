// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneUploadPathsTest {
    @Test
    fun `uploads stay inside the folder and only subtitles and fonts are accepted`() {
        val root = Files.createTempDirectory("kai-upload").toFile()
        assertEquals("Frieren/Fonts/A.ttf", PhoneUploadPaths.resolve(root, "Frieren/Fonts/A.ttf")!!.relativeTo(root).path)
        assertEquals("Dandadan/Temporada 2/x.ass", PhoneUploadPaths.resolve(root, "Dandadan\\Temporada 2\\x.ass")!!.relativeTo(root).path)
        assertNull(PhoneUploadPaths.resolve(root, "../evil.ass"))
        assertNull(PhoneUploadPaths.resolve(root, "Frieren/../../evil.ass"))
        assertNull(PhoneUploadPaths.resolve(root, "Frieren/script.sh"))
        assertNull(PhoneUploadPaths.resolve(root, "Frieren/.hidden.ass"))
        assertNull(PhoneUploadPaths.resolve(root, ""))
        root.deleteRecursively()
    }
}
