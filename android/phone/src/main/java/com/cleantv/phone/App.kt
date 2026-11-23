package com.cleantv.phone

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import android.util.Size
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        TvClient.app = this
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ThumbFetcher.Factory())
            add(Keyer<Media> { data, _ -> "thumb:${data.isVideo}:${data.id}" })
        }
        .crossfade(true)
        // MediaStore already caches thumbnails; don't duplicate them on disk.
        .diskCache(null)
        .build()
}

/** Loads MediaStore's cached thumbnails (fast, works for both videos and pictures). */
class ThumbFetcher(private val data: Media, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val ctx = options.context
        val bmp = ctx.contentResolver.loadThumbnail(data.uri, Size(384, 384), null)
        return DrawableResult(BitmapDrawable(ctx.resources, bmp), true, DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Media> {
        override fun create(data: Media, options: Options, imageLoader: ImageLoader) = ThumbFetcher(data, options)
    }
}
