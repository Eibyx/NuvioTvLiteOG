// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0) and adapted to NuvioTvLiteOG.
package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import com.nuvio.tv.R
import com.nuvio.tv.core.subtitlelibrary.LibraryEntry
import com.nuvio.tv.core.subtitlelibrary.LibraryMatcher
import com.nuvio.tv.core.subtitlelibrary.LibraryQuery
import com.nuvio.tv.core.subtitlelibrary.LibraryRedaction
import com.nuvio.tv.core.subtitlelibrary.LibrarySearchResult
import com.nuvio.tv.core.subtitlelibrary.LibrarySourceException
import com.nuvio.tv.core.subtitlelibrary.LibraryUnavailable
import com.nuvio.tv.core.subtitlelibrary.PreparedLibrarySubtitle
import com.nuvio.tv.core.subtitlelibrary.SubtitleFolder
import com.nuvio.tv.core.subtitlelibrary.SubtitleOrigin
import com.nuvio.tv.domain.model.Subtitle
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the subtitle menu shows about the user's subtitle folders. */
@Immutable
data class SubtitleLibraryMenuState(
    val configured: Boolean = false,
    val searching: Boolean = false,
    val usbDisconnected: Boolean = false,
    val accountUnavailable: Boolean = false,
    /** An anime folder was found (or folders exist): "Buscar en mis carpetas…". */
    val canBrowse: Boolean = false,
    /** No single folder fits this title: "Vincular carpeta…". */
    val needsLink: Boolean = false,
    /** A folder ASS with fonts is loaded while libass is off on ExoPlayer. */
    val libassOffForFonts: Boolean = false,
    /** Subtitle id → "file name · N fuentes" for the menu rows. */
    val details: Map<String, String> = emptyMap()
)

enum class SubtitleLibraryBrowserMode { PICK_FILE, LINK_FOLDER }

/** The in-player folder browser ("Buscar en mis carpetas…" / "Vincular carpeta…"). */
@Immutable
data class SubtitleLibraryBrowserState(
    val mode: SubtitleLibraryBrowserMode,
    val roots: List<SubtitleFolder>,
    val root: SubtitleFolder? = null,
    /** Folders opened from the root, deepest last. */
    val path: List<LibraryEntry> = emptyList(),
    val entries: List<LibraryEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

sealed interface SubtitleLibraryAction {
    data class OpenBrowser(val mode: SubtitleLibraryBrowserMode) : SubtitleLibraryAction
    data class SelectRoot(val rootId: String) : SubtitleLibraryAction
    data class OpenEntry(val entry: LibraryEntry) : SubtitleLibraryAction
    data object Up : SubtitleLibraryAction
    /** "Vincular esta carpeta". */
    data object LinkCurrentFolder : SubtitleLibraryAction
    data object Dismiss : SubtitleLibraryAction
    /** The shortcut from "Activa libass para ver las fuentes de este subtítulo". */
    data object EnableLibass : SubtitleLibraryAction
}

/** Per-player state of the subtitle-folder search. */
internal class SubtitleLibraryPlayerState {
    var searchKey: String? = null
    var search: Deferred<LibrarySearchResult>? = null
    var result: LibrarySearchResult? = null
    var query: LibraryQuery? = null
    /** Subtitle id → prepared file; also what the menu details come from. */
    val prepared = LinkedHashMap<String, PreparedLibrarySubtitle>()
    var subtitles: List<Subtitle> = emptyList()
    val warnedMissingFonts = mutableSetOf<String>()
    /** The folder subtitle chosen automatically for this episode: once, so reloads do not repeat it. */
    var autoSelectedId: String? = null
}

internal const val LIBRARY_SUBTITLE_ID_PREFIX = "kai-lib-"
private const val LIBRARY_STARTUP_WAIT_MS = 2_000L
private const val LIBRARY_TAG = "KaiSubtitleLibrary"

internal fun Subtitle.isLibrarySubtitle(): Boolean = id.startsWith(LIBRARY_SUBTITLE_ID_PREFIX)

/** For mpv: the cached ASS/SRT file itself, so the script reaches mpv unchanged. */
internal fun PlayerRuntimeController.librarySubtitleLocalPath(subtitle: Subtitle): String? {
    if (!subtitle.isLibrarySubtitle()) return null
    return subtitleLibraryState.prepared[subtitle.id]?.file?.takeIf { it.isFile }?.absolutePath
}

private fun PlayerRuntimeController.librarySearchKey(): String? {
    if (subtitleLibrary == null) return null
    val id = currentVideoId ?: videoId ?: contentId ?: return null
    return "$id|${currentSeason ?: 0}|${currentEpisode ?: 0}"
}

/**
 * Starts (once per episode) the low-priority search of the user's subtitle folders. It belongs to
 * the player's scope, so leaving the player cancels it.
 */
internal fun PlayerRuntimeController.startSubtitleLibrarySearchIfNeeded(): Deferred<LibrarySearchResult>? {
    val library = subtitleLibrary ?: return null
    val key = librarySearchKey() ?: return null
    val state = subtitleLibraryState
    if (state.searchKey == key) {
        // Same episode (player rebuilt, engine switched): put the folder subtitles back in the list.
        if (state.subtitles.isNotEmpty()) {
            _uiState.update { it.copy(addonSubtitles = withLibrarySubtitles(it.addonSubtitles)) }
        }
        return state.search
    }
    state.search?.cancel()
    state.searchKey = key
    state.result = null
    state.prepared.clear()
    state.subtitles = emptyList()
    state.warnedMissingFonts.clear()
    state.autoSelectedId = null
    clearLibraryFontRegistrations()
    val settings = library.settings.value
    if (settings.enabledFolders.isEmpty()) {
        state.search = null
        _uiState.update { it.copy(subtitleLibraryMenu = SubtitleLibraryMenuState()) }
        return null
    }
    _uiState.update { it.copy(subtitleLibraryMenu = SubtitleLibraryMenuState(configured = true, searching = true)) }
    val deferred = scope.async(Dispatchers.IO, start = CoroutineStart.DEFAULT) {
        val query = buildLibraryQuery()
        state.query = query
        library.search(query)
    }
    state.search = deferred
    scope.launch {
        val result = try {
            deferred.await()
        } catch (error: CancellationException) {
            return@launch
        } catch (error: Exception) {
            Log.w(LIBRARY_TAG, "Subtitle folder search failed: ${LibraryRedaction.text(error.message)}")
            LibrarySearchResult()
        }
        if (state.searchKey != key) return@launch
        publishLibraryResult(result)
    }
    return deferred
}

private suspend fun PlayerRuntimeController.buildLibraryQuery(): LibraryQuery {
    val type = contentType ?: "series"
    val id = currentVideoId ?: videoId ?: contentId.orEmpty()
    val request = runCatching { cloudStreams?.request(type, id, currentSeason, currentEpisode) }.getOrNull()
    val titles = buildList {
        request?.displayTitle?.let(::add)
        request?.aliases?.forEach { add(it.title) }
        _uiState.value.contentName?.let(::add)
        _uiState.value.title.takeIf { it.isNotBlank() }?.let(::add)
    }.filter { it.isNotBlank() }.distinct()
    return LibraryQuery(
        seriesKey = contentId,
        season = request?.season ?: currentSeason,
        episode = request?.episode ?: currentEpisode,
        absoluteEpisode = request?.absoluteEpisode,
        titles = titles,
        preferredLanguage = _uiState.value.subtitleStyle.preferredLanguage
    )
}

/**
 * For the first ExoPlayer media item: waits at most two seconds for the search and returns the
 * subtitle to attach from the start (so choosing it needs no reload). Never blocks longer.
 */
internal suspend fun PlayerRuntimeController.awaitStartupLibrarySubtitles(): List<Subtitle> {
    val deferred = startSubtitleLibrarySearchIfNeeded() ?: return emptyList()
    val result = withTimeoutOrNull(LIBRARY_STARTUP_WAIT_MS) { runCatching { deferred.await() }.getOrNull() } ?: return emptyList()
    if (subtitleLibraryState.subtitles.isEmpty()) publishLibraryResult(result, autoSelect = false)
    val auto = autoSelectableLibrarySubtitle() ?: return emptyList()
    return listOf(auto)
}

private fun PlayerRuntimeController.originLabel(origin: SubtitleOrigin?): String = context.getString(
    when (origin) {
        SubtitleOrigin.TERABOX -> R.string.subtitle_library_origin_terabox
        SubtitleOrigin.DRIVE -> R.string.subtitle_library_origin_drive
        else -> R.string.subtitle_library_origin_local
    }
)

private fun libraryId(prepared: PreparedLibrarySubtitle): String {
    val digest = MessageDigest.getInstance("SHA-1").digest("${prepared.root.id}|${prepared.fileId}".toByteArray())
    return LIBRARY_SUBTITLE_ID_PREFIX + digest.take(8).joinToString("") { "%02x".format(it) }
}

private fun PlayerRuntimeController.publishLibraryResult(result: LibrarySearchResult, autoSelect: Boolean = true) {
    val state = subtitleLibraryState
    state.result = result
    val subtitles = result.subtitles.map { prepared ->
        val id = libraryId(prepared)
        state.prepared[id] = prepared
        Subtitle(
            id = id,
            url = Uri.fromFile(prepared.file).toString(),
            lang = prepared.language,
            addonName = "${Subtitle.languageCodeToName(PlayerSubtitleUtils.normalizeLanguageCode(prepared.language))} · ${originLabel(prepared.root.originKind)}",
            addonLogo = null
        )
    }
    state.subtitles = subtitles
    registerLibraryFonts(subtitles)
    val usesExoWithoutLibass = !isUsingMpvEngine() && !requestedUseLibassByUser
    val details = subtitles.associate { subtitle ->
        val prepared = state.prepared.getValue(subtitle.id)
        subtitle.id to if (prepared.isAss) {
            context.resources.getQuantityString(R.plurals.subtitle_library_fonts_detail, prepared.fonts.size, prepared.fileName, prepared.fonts.size)
        } else {
            prepared.fileName
        }
    }
    val menu = SubtitleLibraryMenuState(
        configured = true,
        searching = false,
        usbDisconnected = result.unavailable.any { it.second == LibraryUnavailable.USB_DISCONNECTED },
        accountUnavailable = result.unavailable.any { it.second == LibraryUnavailable.ACCOUNT_DISCONNECTED },
        canBrowse = true,
        needsLink = subtitles.isEmpty() && result.needsLink.isNotEmpty(),
        libassOffForFonts = usesExoWithoutLibass && state.prepared.values.any { it.isAss && it.fonts.isNotEmpty() },
        details = details
    )
    _uiState.update { current ->
        current.copy(
            subtitleLibraryMenu = menu,
            addonSubtitles = (subtitles + current.addonSubtitles.filterNot { it.isLibrarySubtitle() })
                .distinctBy { addonSubtitleKey(it) }
        )
    }
    Log.i(LIBRARY_TAG, "Folder subtitles for this episode: ${subtitles.size} (unavailable roots ${result.unavailable.size})")
    // Before the player exists the track-change pass does it (tryAutoSelectPreferredSubtitleFromAvailableTracks).
    val playerReady = if (isUsingMpvEngine()) mpvView != null else _exoPlayer != null
    if (autoSelect && playerReady) autoSelectableLibrarySubtitle()?.let { autoSelectLibrarySubtitle(it) }
}

/**
 * Chooses [subtitle] the way LiteOG's own automatic subtitle choice does (selectAddonSubtitle:
 * without reload when the startup media item already carries it, otherwise the existing reload
 * path for ASS on libass). Once per episode.
 */
internal fun PlayerRuntimeController.autoSelectLibrarySubtitle(subtitle: Subtitle) {
    if (subtitleLibraryState.autoSelectedId != null) return
    subtitleLibraryState.autoSelectedId = subtitle.id
    autoSubtitleSelected = true
    Log.d(LIBRARY_TAG, "Choosing a folder subtitle automatically (${subtitle.lang})")
    selectAddonSubtitle(subtitle)
}

/** The folder subtitle to choose by itself: picked by hand, or in the preferred language. */
internal fun PlayerRuntimeController.autoSelectableLibrarySubtitle(): Subtitle? {
    val library = subtitleLibrary ?: return null
    if (!library.settings.value.autoSelect) return null
    if (isUserExplicitSubtitleSelection || subtitleDisabledByPersistedPreference) return null
    // A subtitle the user chose before and that is being restored is not overridden.
    pendingRestoredAddonSubtitle?.let { if (!it.id.startsWith(LIBRARY_SUBTITLE_ID_PREFIX)) return null }
    val subtitles = subtitleLibraryState.subtitles
    if (subtitles.isEmpty()) return null
    if (subtitleLibraryState.autoSelectedId != null) return null
    val selected = _uiState.value.selectedAddonSubtitle
    if (selected != null && selected.isLibrarySubtitle()) return null
    val preferred = _uiState.value.subtitleStyle.preferredLanguage
    val picked = subtitles.firstOrNull { subtitleLibraryState.prepared[it.id]?.picked == true }
    return picked ?: subtitles.firstOrNull { PlayerSubtitleUtils.matchesLanguageCode(it.lang, preferred) }
}

/** The folder subtitles, kept in front of whatever list the add-on fetch publishes. */
internal fun PlayerRuntimeController.withLibrarySubtitles(subtitles: List<Subtitle>): List<Subtitle> {
    val library = subtitleLibraryState.subtitles
    if (library.isEmpty()) return subtitles
    return (library + subtitles.filterNot { it.isLibrarySubtitle() }).distinctBy { addonSubtitleKey(it) }
}

private fun PlayerRuntimeController.registerLibraryFonts(subtitles: List<Subtitle>) {
    val registry = libraryFontRegistry
    subtitles.forEach { subtitle ->
        val prepared = subtitleLibraryState.prepared[subtitle.id] ?: return@forEach
        if (!prepared.isAss) return@forEach
        val fonts = prepared.fonts.map { it.name to it.file }
        registry.register(buildAddonSubtitleTrackId(subtitle), fonts)
        MpvLibraryFonts.register(prepared.file.absolutePath, fonts)
    }
}

private fun PlayerRuntimeController.clearLibraryFontRegistrations() {
    libraryFontRegistry.clear()
    MpvLibraryFonts.clear()
}

/** "Faltan fuentes: X, Y…": once per subtitle, when it is chosen. */
internal fun PlayerRuntimeController.onLibrarySubtitleSelected(subtitle: Subtitle) {
    if (!subtitle.isLibrarySubtitle()) return
    val prepared = subtitleLibraryState.prepared[subtitle.id] ?: return
    if (prepared.missingFonts.isEmpty() || !subtitleLibraryState.warnedMissingFonts.add(subtitle.id)) return
    val names = prepared.missingFonts.take(4).joinToString(", ")
    val notice = context.getString(R.string.subtitle_library_missing_fonts, names)
    // LiteOG's player has no notice strip: a toast, as its server messages use.
    android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_LONG).show()
}

// ---- In-player browser ------------------------------------------------------------------------

internal fun PlayerRuntimeController.handleSubtitleLibraryAction(action: SubtitleLibraryAction) {
    val library = subtitleLibrary ?: return
    when (action) {
        is SubtitleLibraryAction.OpenBrowser -> {
            val roots = library.settings.value.enabledFolders
            if (roots.isEmpty()) return
            val state = SubtitleLibraryBrowserState(mode = action.mode, roots = roots)
            _uiState.update { it.copy(subtitleLibraryBrowser = state) }
            val anime = subtitleLibraryState.result?.animeFolders
            val root = roots.firstOrNull { anime?.containsKey(it.id) == true } ?: roots.singleOrNull()
            if (root != null) {
                val start = if (action.mode == SubtitleLibraryBrowserMode.PICK_FILE) anime?.get(root.id) else null
                loadBrowser(state.copy(root = root, path = listOfNotNull(start)))
            }
        }
        is SubtitleLibraryAction.SelectRoot -> {
            val current = _uiState.value.subtitleLibraryBrowser ?: return
            val root = current.roots.firstOrNull { it.id == action.rootId } ?: return
            loadBrowser(current.copy(root = root, path = emptyList()))
        }
        is SubtitleLibraryAction.OpenEntry -> {
            val current = _uiState.value.subtitleLibraryBrowser ?: return
            if (action.entry.isFolder) {
                loadBrowser(current.copy(path = current.path + action.entry))
            } else if (current.mode == SubtitleLibraryBrowserMode.PICK_FILE && LibraryMatcher.isSubtitleName(action.entry.name)) {
                pickLibraryFile(current, action.entry)
            }
        }
        SubtitleLibraryAction.Up -> {
            val current = _uiState.value.subtitleLibraryBrowser ?: return
            when {
                current.path.isNotEmpty() -> loadBrowser(current.copy(path = current.path.dropLast(1)))
                current.root != null && current.roots.size > 1 -> _uiState.update {
                    it.copy(subtitleLibraryBrowser = current.copy(root = null, entries = emptyList(), error = null))
                }
                else -> _uiState.update { it.copy(subtitleLibraryBrowser = null) }
            }
        }
        SubtitleLibraryAction.LinkCurrentFolder -> {
            val current = _uiState.value.subtitleLibraryBrowser ?: return
            val root = current.root ?: return
            val folder = current.path.lastOrNull() ?: return
            val seriesKey = contentId ?: return
            scope.launch {
                library.linkFolder(seriesKey, root, folder)
                _uiState.update { it.copy(subtitleLibraryBrowser = null) }
                subtitleLibraryState.searchKey = null
                startSubtitleLibrarySearchIfNeeded()
            }
        }
        SubtitleLibraryAction.Dismiss -> _uiState.update { it.copy(subtitleLibraryBrowser = null, showSubtitleOverlay = false) }
        SubtitleLibraryAction.EnableLibass -> scope.launch {
            // Only on the user's own press of the shortcut.
            playerSettingsDataStore.setUseLibass(true)
        }
    }
}

private fun PlayerRuntimeController.loadBrowser(state: SubtitleLibraryBrowserState) {
    val library = subtitleLibrary ?: return
    val root = state.root ?: return
    _uiState.update { it.copy(subtitleLibraryBrowser = state.copy(loading = true, error = null)) }
    scope.launch {
        val folder = state.path.lastOrNull()
        val next = try {
            val entries = library.browse(root, folder?.id, folder?.path ?: root.folderPath)
            state.copy(entries = entries, loading = false, error = null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = when ((error as? LibrarySourceException)?.reason) {
                LibraryUnavailable.USB_DISCONNECTED -> context.getString(R.string.subtitle_library_usb_disconnected)
                LibraryUnavailable.ACCOUNT_DISCONNECTED -> context.getString(R.string.subtitle_library_connect_account)
                LibraryUnavailable.PERMISSION_MISSING -> context.getString(R.string.subtitle_library_permission_missing)
                else -> context.getString(R.string.subtitle_library_read_failed)
            }
            state.copy(entries = emptyList(), loading = false, error = message)
        }
        _uiState.update { current ->
            if (current.subtitleLibraryBrowser?.root?.id == root.id && current.subtitleLibraryBrowser.path == state.path) {
                current.copy(subtitleLibraryBrowser = next)
            } else {
                current
            }
        }
    }
}

private fun PlayerRuntimeController.pickLibraryFile(state: SubtitleLibraryBrowserState, file: LibraryEntry) {
    val library = subtitleLibrary ?: return
    val root = state.root ?: return
    val folder = state.path.lastOrNull() ?: LibraryEntry(root.folderId, root.displayName, true, path = root.folderPath)
    _uiState.update { it.copy(subtitleLibraryBrowser = state.copy(loading = true)) }
    scope.launch {
        try {
            val query = subtitleLibraryState.query ?: buildLibraryQuery()
            val prepared = library.preparePicked(root, folder, file, query)
            val previous = subtitleLibraryState.result ?: LibrarySearchResult()
            publishLibraryResult(previous.copy(subtitles = listOf(prepared) + previous.subtitles.filterNot { it.fileId == prepared.fileId }), autoSelect = false)
            val subtitle = subtitleLibraryState.subtitles.firstOrNull { subtitleLibraryState.prepared[it.id]?.fileId == prepared.fileId }
            _uiState.update { it.copy(subtitleLibraryBrowser = null) }
            subtitle?.let { onEvent(PlayerEvent.OnSelectAddonSubtitle(it)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(subtitleLibraryBrowser = state.copy(loading = false, error = context.getString(R.string.subtitle_library_read_failed))) }
        }
    }
}
