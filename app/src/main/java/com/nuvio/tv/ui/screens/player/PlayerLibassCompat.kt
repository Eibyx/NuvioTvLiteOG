package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor as StockMatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import com.nuvio.tv.libass.media.AssHandler
import com.nuvio.tv.libass.media.parser.AssSubtitleParserFactory
import com.nuvio.tv.libass.media.render.AssRenderersFactory
import java.util.Collections
import java.util.WeakHashMap

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())
/** Fonts of the user's subtitle folders (Kai) for each player built here. */
private val libraryFontsByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, LibraryFontRegistry>())

@OptIn(UnstableApi::class)
internal fun ExoPlayer.Builder.buildWithAssSupportCompat(
    context: Context,
    playerMediaSourceFactory: PlayerMediaSourceFactory? = null,
    dataSourceFactory: DataSource.Factory = PlayerPlaybackNetworking.createDataSourceFactory(context),
    extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory(),
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
    libraryFonts: LibraryFontRegistry = LibraryFontRegistry()
): ExoPlayer {
    val assHandler = AssHandler()
    val assSubtitleParserFactory = CompatAssSubtitleParserFactory(assHandler, libraryFonts)
    val assExtractorsFactory = extractorsFactory.withAssMkvSupportCompat(
        subtitleParserFactory = assSubtitleParserFactory,
        assHandler = assHandler
    )
    playerMediaSourceFactory?.configureSubtitleParsing(
        extractorsFactory = assExtractorsFactory,
        subtitleParserFactory = assSubtitleParserFactory
    )

    val mediaSourceFactory = DefaultMediaSourceFactory(
        dataSourceFactory,
        assExtractorsFactory
    )
    mediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory)

    // Wrap the renderers factory with AssRenderersFactory to add a
    // NoSampleRenderer-based position fallback for tunneled playback.
    val assRenderersFactory = AssRenderersFactory(assHandler, renderersFactory)
    val player = this
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(assRenderersFactory)
        .build()

    assHandlersByPlayer[player] = assHandler
    libraryFontsByPlayer[player] = libraryFonts
    assHandler.init(player)
    // Registered after AssHandler, so it runs after libass has dropped the fonts: only a check
    // that the reload's transition did come before the folder subtitle's parser (see the registry).
    player.addListener(object : androidx.media3.common.Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            assSubtitleParserFactory.onMediaItemTransition()
        }
    })
    return player
}

internal fun ExoPlayer.getAssHandlerCompat(): AssHandler? = assHandlersByPlayer[this]

/** The fonts of the user's subtitle folders for this player (null without libass). */
internal fun ExoPlayer.libraryFontRegistryCompat(): LibraryFontRegistry? = libraryFontsByPlayer[this]

/**
 * Releases the AssHandler associated with this player and removes it from the map.
 * Must be called when the player is being disposed to free native libass resources
 * (renderer, tracks, embedded MKV fonts).
 */
internal fun ExoPlayer.releaseAssHandlerCompat() {
    val handler = assHandlersByPlayer.remove(this)
    libraryFontsByPlayer.remove(this)
    handler?.release()
}

@OptIn(UnstableApi::class)
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler,
    private val libraryFonts: LibraryFontRegistry
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)
    @Volatile private var folderParserCreatedAtMs = 0L

    fun onMediaItemTransition() {
        val createdAt = folderParserCreatedAtMs
        // A transition right after a folder subtitle's parser would have wiped its fonts.
        if (createdAt != 0L && android.os.SystemClock.elapsedRealtime() - createdAt < 2_000L) {
            android.util.Log.w("KaiSubtitleFonts", "media item transition after a folder subtitle parser; fonts may be lost")
        }
        folderParserCreatedAtMs = 0L
    }

    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(normalizeSsaFormat(format))
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(normalizeSsaFormat(format))
    }

    override fun create(format: Format): SubtitleParser {
        val normalized = normalizeSsaFormat(format)
        if (normalized.sampleMimeType == MimeTypes.TEXT_SSA && !libraryFonts.isEmpty()) {
            // Before delegate.create: AssHandler.createTrack flushes these pending fonts to libass.
            if (libraryFonts.addFontsFor(format.id) { name, data -> assHandler.addFont(name, data) } > 0) {
                folderParserCreatedAtMs = android.os.SystemClock.elapsedRealtime()
            }
        }
        return delegate.create(normalized)
    }

    private fun normalizeSsaFormat(format: Format): Format {
        val isSsaByCodecs = format.codecs == MimeTypes.TEXT_SSA
        val isSsaByMime = format.sampleMimeType == MimeTypes.TEXT_SSA
        if (isSsaByCodecs && !isSsaByMime) {
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build()
        }
        return format
    }
}

@OptIn(UnstableApi::class)
private fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler
): ExtractorsFactory {
    val delegate = this
    return ExtractorsFactory {
        val extractors = delegate.createExtractors()
        extractors.forEachIndexed { index, extractor ->
            val underlying = extractor.getUnderlyingImplementation()
            // Stock MatroskaExtractor: replace with ASS-aware variant for libass support.
            // Only the innermost extractor is replaced, so wrappers such as AutoSync's
            // embedded subtitle observer keep seeing the stream.
            if (underlying is StockMatroskaExtractor) {
                extractors[index] = extractor.replaceInnermostExtractor(
                    NuvioAssMatroskaExtractor(subtitleParserFactory, assHandler)
                )
            }
            // The DV7 factory swaps in a vendored DvMatroskaExtractor for DV conversion.
            // Preserve its Dolby Vision transformer while enabling libass and zlib subtitle
            // decompression from the same vendored Matroska extractor base class.
            if (underlying is DvMatroskaExtractor) {
                extractors[index] = extractor.replaceInnermostExtractor(
                    NuvioAssMatroskaExtractor(
                        subtitleParserFactory = subtitleParserFactory,
                        assHandler = assHandler,
                        dolbyVisionSampleTransformer = underlying.dolbyVisionSampleTransformer
                    )
                )
            }
        }
        extractors
    }
}
