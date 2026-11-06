package com.nuvio.tv.core.cloudstorage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A remote from an rclone.conf the user pasted or picked (for instance exported from Round Sync Extra). */
internal sealed class RcloneRemote(val name: String) {
    class TeraBox(name: String, val cookie: String, val domain: String?) : RcloneRemote(name) {
        override fun toString(): String = "TeraBox($name)"
    }

    class GoogleDrive(
        name: String,
        val clientId: String?,
        val clientSecret: String?,
        val refreshToken: String?,
        val rootFolderId: String?,
    ) : RcloneRemote(name) {
        /** Without its own client id the token belongs to rclone's shared OAuth client. */
        val usesOwnClient: Boolean get() = !clientId.isNullOrBlank()
        override fun toString(): String = "GoogleDrive($name)"
    }

    class Unsupported(name: String, val type: String) : RcloneRemote(name)
}

internal class RcloneConfigException(message: String) : Exception(message)

/**
 * Reads the `terabox` and `drive` remotes of an rclone.conf. Only what the user gives the app is
 * read: this never looks for another app's files. Encrypted configs are refused (they would need
 * the config password).
 */
internal object RcloneConfigImport {
    fun parse(text: String): List<RcloneRemote> {
        if (text.trimStart().startsWith("RCLONE_ENCRYPT_V0:")) {
            throw RcloneConfigException("encrypted")
        }
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith('#') || line.startsWith(';') -> Unit
                line.startsWith('[') && line.endsWith(']') -> {
                    current = mutableMapOf<String, String>().also { sections[line.substring(1, line.length - 1).trim()] = it }
                }
                '=' in line -> current?.put(line.substringBefore('=').trim().lowercase(), line.substringAfter('=').trim())
            }
        }
        return sections.map { (name, values) ->
            when (val type = values["type"].orEmpty().lowercase()) {
                "terabox" -> values["cookie"]?.takeIf { it.isNotBlank() }
                    ?.let { RcloneRemote.TeraBox(name, it, values["domain"]) }
                    ?: RcloneRemote.Unsupported(name, type)
                "drive" -> RcloneRemote.GoogleDrive(
                    name = name,
                    clientId = values["client_id"]?.takeIf { it.isNotBlank() },
                    clientSecret = values["client_secret"]?.takeIf { it.isNotBlank() },
                    refreshToken = values["token"]?.let(::refreshTokenOf),
                    rootFolderId = values["root_folder_id"]?.takeIf { it.isNotBlank() },
                )
                else -> RcloneRemote.Unsupported(name, type)
            }
        }
    }

    private fun refreshTokenOf(token: String): String? =
        runCatching { Json.parseToJsonElement(token) as? JsonObject }.getOrNull()
            ?.get("refresh_token")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
}
