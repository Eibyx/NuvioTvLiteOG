// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where a subtitle folder lives. */
enum class SubtitleOrigin(val id: String) {
    TERABOX("terabox"),
    DRIVE("drive"),
    LOCAL("local");

    companion object {
        fun fromId(id: String?): SubtitleOrigin? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One configured root folder (`Subtítulos/`). Holds no credential: cloud folders point to an
 * account whose tokens stay encrypted in the cloud storage store; local folders hold a tree URI
 * or a path and the [LocalAccessMethod] used to read it.
 */
@Serializable
data class SubtitleFolder(
    val id: String,
    val origin: String,
    val displayName: String,
    /** Cloud account id (TeraBox, Drive); null for local folders. */
    val accountId: String? = null,
    /** Drive folder id, TeraBox path, SAF tree URI or absolute path. */
    val folderId: String,
    val folderPath: String = "",
    val localMethod: String? = null,
    val enabled: Boolean = true
) {
    val originKind: SubtitleOrigin? get() = SubtitleOrigin.fromId(origin)
    val accessMethod: LocalAccessMethod? get() = LocalAccessMethod.fromId(localMethod)
}

/** A series linked to a folder by hand ("Vincular carpeta…"). */
@Serializable
data class SubtitleFolderLink(val rootId: String, val folderEntryId: String, val folderName: String)

/** An episode's subtitle picked by hand ("Buscar en mis carpetas…"). */
@Serializable
data class SubtitleFilePick(
    val rootId: String,
    val fileId: String,
    val fileName: String,
    val folderEntryId: String,
    val sizeBytes: Long = 0,
    val modifiedEpochMs: Long = 0
)

data class SubtitleLibrarySettings(
    val folders: List<SubtitleFolder> = emptyList(),
    val autoSelect: Boolean = true,
    val cacheLimitMb: Int = DEFAULT_CACHE_LIMIT_MB,
    /** Series key (`anilist:…`, `kitsu:…`, `tt…`, `tmdb:…`) → linked folder. */
    val links: Map<String, SubtitleFolderLink> = emptyMap(),
    /** Episode key → picked file. */
    val picks: Map<String, SubtitleFilePick> = emptyMap()
) {
    val enabledFolders: List<SubtitleFolder> get() = folders.filter { it.enabled }

    companion object {
        const val DEFAULT_CACHE_LIMIT_MB = 100
        val CACHE_LIMIT_OPTIONS_MB = listOf(50, 100, 200, 500)
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleLibraryStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val foldersKey = stringPreferencesKey("folders_json")
    private val linksKey = stringPreferencesKey("links_json")
    private val picksKey = stringPreferencesKey("picks_json")
    private val autoSelectKey = booleanPreferencesKey("auto_select")
    private val cacheLimitKey = intPreferencesKey("cache_limit_mb")

    private val folderList = ListSerializer(SubtitleFolder.serializer())
    private val linkMap = MapSerializer(String.serializer(), SubtitleFolderLink.serializer())
    private val pickMap = MapSerializer(String.serializer(), SubtitleFilePick.serializer())

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    val settings: StateFlow<SubtitleLibrarySettings> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs ->
            SubtitleLibrarySettings(
                folders = prefs[foldersKey]?.let { runCatching { json.decodeFromString(folderList, it) }.getOrNull() }.orEmpty(),
                autoSelect = prefs[autoSelectKey] ?: true,
                cacheLimitMb = prefs[cacheLimitKey] ?: SubtitleLibrarySettings.DEFAULT_CACHE_LIMIT_MB,
                links = prefs[linksKey]?.let { runCatching { json.decodeFromString(linkMap, it) }.getOrNull() }.orEmpty(),
                picks = prefs[picksKey]?.let { runCatching { json.decodeFromString(pickMap, it) }.getOrNull() }.orEmpty()
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, SubtitleLibrarySettings())

    suspend fun current(): SubtitleLibrarySettings = settings.first()

    suspend fun addFolder(folder: SubtitleFolder) = editFolders { folders -> folders.filterNot { it.id == folder.id } + folder }

    suspend fun removeFolder(id: String) {
        store().edit { prefs ->
            val folders = decodeFolders(prefs[foldersKey]).filterNot { it.id == id }
            prefs[foldersKey] = json.encodeToString(folderList, folders)
            val links = decodeLinks(prefs[linksKey]).filterValues { it.rootId != id }
            prefs[linksKey] = json.encodeToString(linkMap, links)
            val picks = decodePicks(prefs[picksKey]).filterValues { it.rootId != id }
            prefs[picksKey] = json.encodeToString(pickMap, picks)
        }
    }

    suspend fun setFolderEnabled(id: String, enabled: Boolean) =
        editFolders { folders -> folders.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    suspend fun setAutoSelect(enabled: Boolean) {
        store().edit { it[autoSelectKey] = enabled }
    }

    suspend fun setCacheLimitMb(limit: Int) {
        store().edit { it[cacheLimitKey] = limit.coerceIn(10, 2000) }
    }

    suspend fun link(seriesKey: String, link: SubtitleFolderLink) {
        store().edit { prefs ->
            val links = decodeLinks(prefs[linksKey]) + (seriesKey to link)
            prefs[linksKey] = json.encodeToString(linkMap, links.entries.toList().takeLast(MAX_LINKS).associate { it.toPair() })
        }
    }

    suspend fun pick(episodeKey: String, pick: SubtitleFilePick) {
        store().edit { prefs ->
            val picks = decodePicks(prefs[picksKey]) + (episodeKey to pick)
            prefs[picksKey] = json.encodeToString(pickMap, picks.entries.toList().takeLast(MAX_PICKS).associate { it.toPair() })
        }
    }

    private suspend fun editFolders(change: (List<SubtitleFolder>) -> List<SubtitleFolder>) {
        store().edit { prefs -> prefs[foldersKey] = json.encodeToString(folderList, change(decodeFolders(prefs[foldersKey]))) }
    }

    private fun decodeFolders(value: String?) = value?.let { runCatching { json.decodeFromString(folderList, it) }.getOrNull() }.orEmpty()
    private fun decodeLinks(value: String?) = value?.let { runCatching { json.decodeFromString(linkMap, it) }.getOrNull() }.orEmpty()
    private fun decodePicks(value: String?) = value?.let { runCatching { json.decodeFromString(pickMap, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val FEATURE = "kai_subtitle_library"
        const val MAX_LINKS = 500
        const val MAX_PICKS = 2000
    }
}
