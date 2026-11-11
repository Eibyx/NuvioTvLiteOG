// Ported from Eibyx/NuvioArcKai (branch kai/subtitle-folders, GPL-3.0).
package com.nuvio.tv.core.subtitlelibrary

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.nuvio.tv.core.cloudstorage.CloudStorageRepository
import com.nuvio.tv.core.di.CloudHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext

/** What the player asks for: the episode and every title it may be filed under. */
internal data class LibraryQuery(
    /** `anilist:…`, `kitsu:…`, `tt…`, `tmdb:…`: what a manual folder link is remembered by. */
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
    val absoluteEpisode: Int?,
    val titles: List<String>,
    val preferredLanguage: String?
) {
    val episodeKey: String? get() = seriesKey?.let { "$it:${season ?: 0}:${episode ?: 0}" }
}

internal data class LibraryFontFile(val name: String, val file: File)

/** A subtitle copied into the cache with the fonts it uses, ready for the player (`file://`). */
internal data class PreparedLibrarySubtitle(
    val root: SubtitleFolder,
    val fileName: String,
    val fileId: String,
    val language: String,
    val file: File,
    val isAss: Boolean,
    val fonts: List<LibraryFontFile>,
    val missingFonts: Set<String>,
    val picked: Boolean
)

internal data class LibrarySearchResult(
    val subtitles: List<PreparedLibrarySubtitle> = emptyList(),
    /** Roots that could not be read now (USB unplugged, account disconnected…). */
    val unavailable: List<Pair<SubtitleFolder, LibraryUnavailable>> = emptyList(),
    /** Roots where no single folder fits the title: "Vincular carpeta…". */
    val needsLink: List<SubtitleFolder> = emptyList(),
    /** The anime folder found in each root, for "Buscar en mis carpetas…". */
    val animeFolders: Map<String, LibraryEntry> = emptyMap()
)

/**
 * The user's subtitle folders, searched on demand from the player (no background work): finds the
 * episode's subtitle in each enabled root, copies it and the fonts it really uses into the cache,
 * and remembers listings for ten minutes. Nothing here writes to a folder or to the cloud.
 */
@Singleton
class SubtitleLibraryService @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val store: SubtitleLibraryStore,
    private val cloudRepository: CloudStorageRepository,
    @CloudHttpClient cloudClient: OkHttpClient
) {
    private val client = cloudClient.newBuilder().followRedirects(true).followSslRedirects(true).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val listings = LinkedHashMap<String, CachedListing>()
    private val faceIndex = LinkedHashMap<String, List<FontFaceNames>>()
    private val listingDir = File(context.cacheDir, "kai-subtitle-library-listings")
    private val diskCache = LibraryDiskCache(
        root = File(context.cacheDir, "kai-subtitle-library"),
        maxBytes = { store.settings.value.cacheLimitMb.toLong() * 1024 * 1024 }
    )

    @Serializable
    private class CachedListing(val atEpochMs: Long, val entries: List<LibraryEntry>)

    val settings get() = store.settings

    internal fun sourceFor(folder: SubtitleFolder): LibrarySource? = when (folder.originKind) {
        SubtitleOrigin.TERABOX, SubtitleOrigin.DRIVE -> CloudLibrarySource(folder, cloudRepository, client)
        SubtitleOrigin.LOCAL -> when (folder.accessMethod) {
            LocalAccessMethod.SAF -> SafLibrarySource(folder, context)
            LocalAccessMethod.LEGACY_FILES -> FileLibrarySource(folder) { legacyReadGranted() }
            LocalAccessMethod.ALL_FILES -> FileLibrarySource(folder) { allFilesGranted() }
            LocalAccessMethod.PHONE_UPLOAD -> FileLibrarySource(folder)
            null -> null
        }
        null -> null
    }

    private fun legacyReadGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun allFilesGranted(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    // ---- Listings -------------------------------------------------------------------------------

    internal suspend fun list(source: LibrarySource, folderId: String, folderPath: String, force: Boolean = false): List<LibraryEntry> {
        val key = "${source.folder.id}|$folderId"
        val now = System.currentTimeMillis()
        if (!force) {
            synchronized(lock) { listings[key] }?.takeIf { now - it.atEpochMs < LISTING_TTL_MS }?.let { return it.entries }
            readListing(key)?.takeIf { now - it.atEpochMs < LISTING_TTL_MS }?.let { cached ->
                synchronized(lock) { listings[key] = cached }
                return cached.entries
            }
        }
        val entries = source.list(folderId, folderPath)
        val cached = CachedListing(now, entries)
        synchronized(lock) {
            listings[key] = cached
            while (listings.size > MAX_MEMORY_LISTINGS) listings.remove(listings.keys.first())
        }
        writeListing(key, cached)
        return entries
    }

    /** "Actualizar": forgets the listings of one root (or all), so the next search reads it again. */
    fun invalidate(rootId: String? = null) {
        synchronized(lock) {
            if (rootId == null) listings.clear() else listings.keys.removeAll { it.startsWith("$rootId|") }
        }
        listingDir.listFiles()?.forEach { file ->
            if (rootId == null || file.name.startsWith(hash(rootId).take(12))) file.delete()
        }
    }

    private fun listingFile(key: String) = File(listingDir, "${hash(key.substringBefore('|')).take(12)}-${hash(key).take(24)}.json")

    private fun readListing(key: String): CachedListing? = runCatching {
        val file = listingFile(key)
        if (!file.isFile) return null
        json.decodeFromString(CachedListing.serializer(), file.readText())
    }.getOrNull()

    private fun writeListing(key: String, listing: CachedListing) {
        runCatching {
            listingDir.mkdirs()
            val file = listingFile(key)
            val temporary = File(listingDir, "${file.name}.part")
            temporary.writeText(json.encodeToString(CachedListing.serializer(), listing))
            temporary.renameTo(file)
        }
    }

    // ---- Search ---------------------------------------------------------------------------------

    internal suspend fun search(query: LibraryQuery): LibrarySearchResult = withContext(Dispatchers.IO) {
        val settings = store.current()
        val roots = settings.enabledFolders
        if (roots.isEmpty() || query.episode == null && query.titles.isEmpty()) return@withContext LibrarySearchResult()
        val subtitles = mutableListOf<PreparedLibrarySubtitle>()
        val unavailable = mutableListOf<Pair<SubtitleFolder, LibraryUnavailable>>()
        val needsLink = mutableListOf<SubtitleFolder>()
        val animeFolders = mutableMapOf<String, LibraryEntry>()
        for (root in roots) {
            coroutineContext.ensureActive()
            val source = sourceFor(root) ?: continue
            try {
                source.checkAvailable()
                val pick = query.episodeKey?.let { settings.picks[it] }?.takeIf { it.rootId == root.id }
                if (pick != null) {
                    val entry = LibraryEntry(pick.fileId, pick.fileName, false, pick.sizeBytes, pick.modifiedEpochMs)
                    val folder = LibraryEntry(pick.folderEntryId, "", true)
                    subtitles += prepare(source, EpisodeSubtitle(entry, emptyList(), folder), query, picked = true)
                    continue
                }
                val top = list(source, source.rootId, root.folderPath)
                val link = query.seriesKey?.let { settings.links[it] }?.takeIf { it.rootId == root.id }
                when (val match = LibraryMatcher.animeFolder(top, query.titles, link?.folderEntryId)) {
                    is AnimeFolderMatch.Found -> {
                        animeFolders[root.id] = match.folder
                        val files = episodeTree(source, match.folder, root)
                        LibraryMatcher.episodeSubtitles(files, query.season, query.episode, query.absoluteEpisode)
                            .take(MAX_CANDIDATES_PER_ROOT)
                            .forEach { candidate -> subtitles += prepare(source, candidate, query, picked = false) }
                    }
                    is AnimeFolderMatch.Ambiguous -> needsLink += root
                    AnimeFolderMatch.None -> needsLink += root
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LibrarySourceException) {
                unavailable += root to error.reason
            } catch (error: Exception) {
                Log.w(TAG, "Subtitle folder ${root.origin} not read: ${error::class.java.simpleName} ${LibraryRedaction.text(error.message)}")
                unavailable += root to LibraryUnavailable.FAILED
            }
        }
        LibrarySearchResult(subtitles, unavailable, needsLink, animeFolders)
    }

    /** Subtitles in the anime folder and in its season folders (`Temporada 2`, `Season 2`, `S2`). */
    private suspend fun episodeTree(source: LibrarySource, anime: LibraryEntry, root: SubtitleFolder): List<EpisodeSubtitle> {
        val entries = list(source, anime.id, anime.path.ifEmpty { "${root.folderPath}/${anime.name}" })
        val here = entries.filter { !it.isFolder && LibraryMatcher.isSubtitleName(it.name) }
            .map { EpisodeSubtitle(it, emptyList(), anime) }
        val seasons = entries.filter { it.isFolder && LibraryMatcher.seasonOfFolder(it.name) != null }
            .take(MAX_SEASON_FOLDERS)
            .flatMap { season ->
                list(source, season.id, season.path)
                    .filter { !it.isFolder && LibraryMatcher.isSubtitleName(it.name) }
                    .map { EpisodeSubtitle(it, listOf(season.name), season) }
            }
        return here + seasons
    }

    /** The entries of a folder for the in-player browser; [folderId] null means the root. */
    internal suspend fun browse(root: SubtitleFolder, folderId: String?, folderPath: String): List<LibraryEntry> {
        val source = sourceFor(root) ?: throw LibrarySourceException(LibraryUnavailable.FAILED)
        source.checkAvailable()
        return list(source, folderId ?: source.rootId, folderPath)
            .filter { it.isFolder || LibraryMatcher.isSubtitleName(it.name) }
            .sortedWith(compareByDescending<LibraryEntry> { it.isFolder }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /** A file picked by hand in the browser: prepared like a found one and remembered for the episode. */
    internal suspend fun preparePicked(root: SubtitleFolder, folder: LibraryEntry, file: LibraryEntry, query: LibraryQuery): PreparedLibrarySubtitle {
        val source = sourceFor(root) ?: throw LibrarySourceException(LibraryUnavailable.FAILED)
        val prepared = prepare(source, EpisodeSubtitle(file, emptyList(), folder), query, picked = true)
        query.episodeKey?.let { key ->
            store.pick(key, SubtitleFilePick(root.id, file.id, file.name, folder.id, file.sizeBytes, file.modifiedEpochMs))
        }
        return prepared
    }

    internal suspend fun linkFolder(seriesKey: String, root: SubtitleFolder, folder: LibraryEntry) {
        store.link(seriesKey, SubtitleFolderLink(root.id, folder.id, folder.name))
    }

    // ---- Download and fonts ---------------------------------------------------------------------

    private suspend fun prepare(
        source: LibrarySource,
        candidate: EpisodeSubtitle,
        query: LibraryQuery,
        picked: Boolean
    ): PreparedLibrarySubtitle {
        val root = source.folder
        val file = fetch(source, candidate.file, MAX_SUBTITLE_BYTES)
        val extension = candidate.file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isAss = extension == "ass" || extension == "ssa"
        var fonts = emptyList<LibraryFontFile>()
        var missing = emptySet<String>()
        var scriptLanguage: String? = null
        if (isAss) {
            val script = AssFontNames.decode(file.readBytes())
            scriptLanguage = SCRIPT_LANGUAGE.find(script.take(8192))?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.take(3)
            val used = AssFontNames.usedFontNames(script)
            if (used.isNotEmpty()) {
                val plan = fontsFor(source, candidate.folder, used)
                fonts = plan.first
                missing = plan.second
            }
        }
        val language = LibraryMatcher.languageOf(candidate.file.name)
            ?: scriptLanguage?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
            // The user's own folders: an untagged file is taken to be in their preferred language.
            ?: query.preferredLanguage?.takeIf { it.isNotBlank() && it != "none" }
            ?: "und"
        Log.i(TAG, "Prepared a ${root.origin} subtitle (${fonts.size} fonts, ${missing.size} missing)")
        return PreparedLibrarySubtitle(root, candidate.file.name, candidate.file.id, language, file, isAss, fonts, missing, picked)
    }

    /** Fonts of the subtitle's own folder (and its Fonts/Fuentes/Attachments folder) that the script uses. */
    private suspend fun fontsFor(source: LibrarySource, folder: LibraryEntry, used: Set<String>): Pair<List<LibraryFontFile>, Set<String>> {
        val siblings = runCatching { list(source, folder.id, folder.path) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        val fontEntries = siblings.filter { !it.isFolder && SfntNames.isFontName(it.name) } +
            siblings.filter { it.isFolder && LibraryMatcher.isFontFolder(it.name) }.flatMap { sub ->
                runCatching { list(source, sub.id, sub.path) }.getOrDefault(emptyList())
                    .filter { !it.isFolder && SfntNames.isFontName(it.name) }
            }
        if (fontEntries.isEmpty()) return emptyList<LibraryFontFile>() to used
        val limits = FontLimits()
        val byName = fontEntries.associateBy { it.name }
        val ordered = FontSelection.fetchOrder(used, fontEntries.map { it.name }).mapNotNull { byName[it] }
        val candidates = mutableListOf<FontCandidate>()
        val files = mutableMapOf<String, File>()
        val readFaces = mutableListOf<FontFaceNames>()
        var fetched = 0L
        for (entry in ordered) {
            coroutineContext.ensureActive()
            // Once every name is found, only files that look like a used family (its bold, its italic) follow.
            if (FontSelection.allCovered(used, readFaces) && !FontSelection.looksLike(used, entry.name)) break
            if (entry.sizeBytes > limits.maxFileBytes) continue
            if (fetched + entry.sizeBytes > MAX_FONT_FETCH_BYTES) break
            val file = runCatching { fetch(source, entry, limits.maxFileBytes) }.getOrElse { if (it is CancellationException) throw it; null } ?: continue
            fetched += file.length()
            val faces = synchronized(lock) { faceIndex[file.name] } ?: SfntNames.read(file).also { faces ->
                synchronized(lock) {
                    faceIndex[file.name] = faces
                    while (faceIndex.size > MAX_FACE_INDEX) faceIndex.remove(faceIndex.keys.first())
                }
            }
            readFaces += faces
            files[file.name] = file
            candidates += FontCandidate(file.name, entry.name, file.length(), faces.ifEmpty { null })
        }
        val plan = FontSelection.plan(used, candidates, limits)
        return plan.selected.mapNotNull { selected -> files[selected.key]?.let { LibraryFontFile(selected.fileName, it) } } to plan.missing
    }

    /** The cached copy of [entry], downloaded or copied when it changed or is not cached yet. */
    private suspend fun fetch(source: LibrarySource, entry: LibraryEntry, maxBytes: Long): File {
        val key = diskCache.keyFor("${source.folder.id}|${entry.id}", entry.sizeBytes, entry.modifiedEpochMs, entry.name)
        diskCache.get(source.folder.origin, key)?.let { return it }
        if (entry.sizeBytes > maxBytes) throw IOException("file too large")
        return withContext(Dispatchers.IO) {
            source.open(entry).use { input ->
                diskCache.put(source.folder.origin, key) { output -> copyCapped(input, output, maxBytes) }
            }
        }
    }

    fun cacheBytes(): Long = diskCache.totalBytes()

    fun clearCache() {
        diskCache.clear()
        invalidate(null)
        synchronized(lock) { faceIndex.clear() }
    }

    private fun copyCapped(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("file too large")
            output.write(buffer, 0, read)
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "KaiSubtitleLibrary"
        const val LISTING_TTL_MS = 10 * 60 * 1000L
        const val MAX_MEMORY_LISTINGS = 64
        const val MAX_FACE_INDEX = 256
        const val MAX_CANDIDATES_PER_ROOT = 4
        const val MAX_SEASON_FOLDERS = 12
        const val MAX_SUBTITLE_BYTES = 50L * 1024 * 1024
        /** Fonts downloaded to find the used ones in one folder, at most. */
        const val MAX_FONT_FETCH_BYTES = 80L * 1024 * 1024
        val SCRIPT_LANGUAGE = Regex("""(?im)^\s*Language\s*:\s*([A-Za-z]{2,3})""")
    }
}
