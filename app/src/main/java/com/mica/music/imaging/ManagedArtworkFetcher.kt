package com.mica.music.imaging

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Size
import coil.size.pxOrElse
import com.mica.music.data.scanner.AlbumArtCache
import com.mica.music.data.scanner.ManagedArtworkRecovery
import java.io.FileNotFoundException
import okio.buffer
import okio.source

/**
 * In-process Coil [Fetcher] for Mica managed artwork URIs.
 *
 * Routes compact requests (<= [AlbumArtCache.THUMB_MAX_EDGE_PX], e.g. song list, queue, search,
 * mini-player, blurred background) to the small-footprint thumbnail tier (`cache/album_art_thumb`),
 * while full-resolution requests (NowPlaying hero cover, CoverFlow, Particle, PhotoStack) load
 * from the bounded on-demand original LRU (`cache/album_art`) with zero quality loss.
 */
internal class ManagedArtworkFetcher(
    private val appContext: Context,
    private val uriString: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val useThumbnailTier = shouldUseThumbnailTier(options.size)
        val pfd = if (useThumbnailTier) {
            openThumbnailOrFallback()
        } else {
            openOriginal()
        } ?: throw FileNotFoundException("Unable to resolve managed album artwork: $uriString")

        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return SourceResult(
            source = ImageSource(stream.source().buffer(), appContext),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun openThumbnailOrFallback(): ParcelFileDescriptor? {
        AlbumArtCache.openExistingThumbnailArtwork(appContext, uriString)?.let { return it }
        if (ManagedArtworkRecovery.ensureThumbnail(appContext, uriString)) {
            AlbumArtCache.openExistingThumbnailArtwork(appContext, uriString)?.let { return it }
        }
        // Fallback when raw bytes are valid for Coil/BitmapFactory or already resident as original.
        return AlbumArtCache.openExistingManagedArtwork(appContext, uriString)
    }

    private suspend fun openOriginal(): ParcelFileDescriptor? {
        AlbumArtCache.openExistingManagedArtwork(appContext, uriString)?.let { return it }
        if (ManagedArtworkRecovery.ensureOriginal(appContext, uriString)) {
            AlbumArtCache.openExistingManagedArtwork(appContext, uriString)?.let { return it }
        }
        return null
    }

    internal class Factory(context: Context) : Fetcher.Factory<Uri> {
        private val appContext = context.applicationContext

        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val uriString = data.toString()
            if (AlbumArtCache.parseManagedArtworkUri(appContext, uriString) == null) return null
            return ManagedArtworkFetcher(appContext, uriString, options)
        }
    }

    internal companion object {
        /**
         * Requests up to 384px (compact 256px list/queue/mini-player covers and 384px blurred
         * player backgrounds) read the compact 320px WebP thumbnail tier.
         */
        internal const val THUMB_TIER_REQUEST_MAX_EDGE_PX = 384

        fun shouldUseThumbnailTier(
            size: Size,
            maxThumbEdgePx: Int = THUMB_TIER_REQUEST_MAX_EDGE_PX,
        ): Boolean {
            val width = size.width.pxOrElse { return false }
            val height = size.height.pxOrElse { return false }
            return width > 0 && height > 0 && maxOf(width, height) <= maxThumbEdgePx
        }
    }
}
