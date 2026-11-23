package com.cleantv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens a link sent from the phone, in private mode: nothing about the link is logged,
 * saved to history, or cached on disk.
 *  - YouTube links  → the TV's YouTube app
 *  - Google Photos shared album links → full-screen slideshow
 *  - web page with a video → the video found on the page
 *  - direct video / HLS (.m3u8) / DASH (.mpd) / audio stream → TV Cast player
 */
object Online {
    private const val MAX_PAGE = 3 * 1024 * 1024

    fun open(ctx: Context, url: String, title: String) {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase().orEmpty()
        try {
            when {
                AdBlock.isBlocked(host) -> toast(ctx, "That link is an ad/tracker address and was blocked.")
                host.endsWith("youtube.com") || host == "youtu.be" -> openYouTube(ctx, uri)
                host == "photos.app.goo.gl" || host == "photos.google.com" || host == "goo.gl" -> {
                    toast(ctx, "Loading Google Photos album…")
                    val pics = GooglePhotos.resolve(url)
                    if (pics.isEmpty()) toast(ctx, "No pictures found. Is the album shared by link?")
                    else play(ctx, CastRequest.Images(pics, List(pics.size) { "Photo ${it + 1}" }, 0, slideshow = true))
                }
                else -> {
                    val target = findPlayable(url) ?: run {
                        toast(ctx, "No video found at that link.")
                        return
                    }
                    val name = title.ifBlank { Uri.parse(target).lastPathSegment?.takeIf { it.contains('.') } ?: host }
                    play(ctx, CastRequest.Video(listOf(VideoItem(target, name, online = true)), 0, 0))
                }
            }
        } catch (e: Exception) {
            toast(ctx, "Couldn't open that link.")
        }
    }

    private fun play(ctx: Context, r: CastRequest) {
        Bus.pending = r
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openYouTube(ctx: Context, uri: Uri) {
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val yt = Intent(i).setPackage("com.amazon.firetv.youtube")
        try {
            ctx.startActivity(yt)
        } catch (e: Exception) {
            try { ctx.startActivity(i) } catch (e2: Exception) { toast(ctx, "YouTube app isn't installed on the TV.") }
        }
    }

    /**
     * Returns a URL ExoPlayer can play. Direct media is returned as-is; for web pages the
     * page is read once (in memory only) and the first video/stream link on it is used.
     */
    private fun findPlayable(url: String): String? {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        if (MEDIA_EXT.any { path.endsWith(it) }) return url
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 8000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; AFT) TVCast/2")
        try {
            val type = c.contentType?.lowercase().orEmpty()
            val finalUrl = c.url.toString()
            if (!type.startsWith("text/html")) {
                // Video, audio, playlists, or unknown binary: let the player try it.
                return finalUrl
            }
            val page = c.inputStream.use { s ->
                val buf = ByteArray(MAX_PAGE)
                var n = 0
                while (n < buf.size) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n)
            }
            return extract(page, finalUrl)
        } finally {
            c.disconnect()
        }
    }

    private fun extract(html: String, pageUrl: String): String? {
        val base = Uri.parse(pageUrl)
        fun abs(u: String): String? {
            val s = u.trim().replace("&amp;", "&").replace("\\/", "/")
            val r = when {
                s.startsWith("http://") || s.startsWith("https://") -> s
                s.startsWith("//") -> "${base.scheme}:$s"
                s.startsWith("/") -> "${base.scheme}://${base.authority}$s"
                else -> return null
            }
            return r.takeIf { !AdBlock.isBlocked(Uri.parse(it).host) }
        }
        val patterns = listOf(
            Regex("""<meta[^>]+property=["']og:video(?::secure_url|:url)?["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""<(?:video|source)[^>]+src=["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?:\\?/\\?/[^"'\s]+?\.(?:m3u8|mpd|mp4|webm|mkv)(?:\?[^"'\s]*)?)["']""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            for (m in p.findAll(html)) abs(m.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun toast(ctx: Context, msg: String) = Bus.main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }

    private val MEDIA_EXT = listOf(".mp4", ".mkv", ".webm", ".m3u8", ".mpd", ".mp3", ".aac", ".m4a", ".ogg", ".opus", ".ts", ".mov")
}

/** Reads a Google Photos *shared album* link and returns full-HD image URLs (no sign-in, nothing stored). */
object GooglePhotos {
    fun resolve(link: String): List<String> {
        val c = URL(link).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 8000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        val html = try {
            // goo.gl short links go through one or two redirects to photos.google.com/share/...
            val host = c.url.host.lowercase()
            if (!host.endsWith("google.com") && !host.endsWith("goo.gl")) return emptyList()
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        if (!c.url.host.lowercase().endsWith("photos.google.com")) return emptyList()
        // Shared-album photos are served from lh3.googleusercontent.com/pw/<id>.
        val re = Regex("""https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_\-]{40,}""")
        return re.findAll(html).map { it.value }.distinct()
            .map { "$it=w1920-h1080" }
            .toList()
    }
}
