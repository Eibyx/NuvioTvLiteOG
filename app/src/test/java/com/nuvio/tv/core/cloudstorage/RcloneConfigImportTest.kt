package com.nuvio.tv.core.cloudstorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RcloneConfigImportTest {
    @Test
    fun readsTeraBoxAndDriveRemotes() {
        val remotes = RcloneConfigImport.parse(
            """
            [mybox]
            type = terabox
            cookie = ndus=FAKE-TEST-VALUE; lang=en

            [gd]
            type = drive
            client_id = 123.apps.googleusercontent.com
            client_secret = FAKE-SECRET
            scope = drive.readonly
            token = {"access_token":"x","token_type":"Bearer","refresh_token":"1//FAKE-REFRESH","expiry":"2026-01-01T00:00:00Z"}

            [shared]
            type = drive
            token = {"refresh_token":"1//OTHER"}

            [s3]
            type = s3
            """.trimIndent(),
        )
        val box = assertIs<RcloneRemote.TeraBox>(remotes[0])
        assertEquals("ndus=FAKE-TEST-VALUE; lang=en", box.cookie)
        val drive = assertIs<RcloneRemote.GoogleDrive>(remotes[1])
        assertEquals("1//FAKE-REFRESH", drive.refreshToken)
        assertTrue(drive.usesOwnClient)
        assertFalse(assertIs<RcloneRemote.GoogleDrive>(remotes[2]).usesOwnClient)
        assertIs<RcloneRemote.Unsupported>(remotes[3])
        assertFalse("FAKE" in remotes.joinToString())
    }

    @Test
    fun encryptedConfigsAreRefused() {
        assertFailsWith<RcloneConfigException> { RcloneConfigImport.parse("RCLONE_ENCRYPT_V0:\nabcdef") }
    }

    @Test
    fun credentialsNeverPrint() {
        val credentials = CloudCredentials(teraboxCookie = "ndus=secret", driveRefreshToken = "1//secret")
        assertFalse("secret" in credentials.toString())
    }
}
