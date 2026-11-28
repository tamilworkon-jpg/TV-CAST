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

    /**
     * Brings the built-in VPN up (or down when [vpn] is false). Returns true when playback may go ahead.
     *  - VPN permission missing → the player screen asks for it (Android only shows the prompt
     *    to a visible screen of the app), then [retry] runs again; returns false for now.
     *  - VPN fails to connect → not played (untick "Use built-in VPN" on the phone to play without it).
     */
    private fun vpnReady(ctx: Context, vpn: Boolean, retry: (Boolean) -> Unit): Boolean {
        if (!vpn) { Vpn.down(); return true }
        if (Vpn.up) return true
        if (!Vpn.permitted(ctx)) {
            Bus.vpnRetry = retry
            play(ctx, CastRequest.VpnConsent)
            return false
        }
        toast(ctx, "Connecting VPN…")
        if (Vpn.ensureUp(ctx)) return true
        toast(ctx, "${Vpn.error ?: "VPN failed"}. Not playing. Untick \"Use built-in VPN\" on the phone to play without it.")
        return false
    }

    fun radio(ctx: Context, item: VideoItem, vpn: Boolean) = radio(ctx, listOf(item), 0, vpn)

    /** Station list picked on the TV: ◀ ▶ / ⏮ ⏭ in the player step through it. */
    fun radio(ctx: Context, items: List<VideoItem>, index: Int, vpn: Boolean) {
        if (vpnReady(ctx, vpn) { v -> radio(ctx, items, index, v) }) play(ctx, CastRequest.Video(items, index, 0))
    }

    /** Google Photos album whose picture list the phone already read: slideshow, videos played in place. */
    fun album(ctx: Context, urls: List<String>, names: List<String>, vpn: Boolean) {
        if (vpnReady(ctx, vpn) { v -> album(ctx, urls, names, v) }) {
            play(ctx, CastRequest.Images(urls, names, 0, slideshow = true, albumVideos = true))
        }
    }

    fun open(ctx: Context, url: String, title: String, vpn: Boolean) {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase().orEmpty()
        try {
            when {
                AdBlock.isBlocked(host) -> toast(ctx, "That link is an ad/tracker address and was blocked.")
                !vpnReady(ctx, vpn) { v -> open(ctx, url, title, v) } -> Unit
                YouTube.isYouTube(host) -> {
                    toast(ctx, "Loading YouTube video…")
                    val item = runCatching { YouTube.resolve(url) }.getOrNull()
                    if (item != null) play(ctx, CastRequest.Video(listOf(item), 0, 0))
                    else {
                        toast(ctx, "Couldn't play it here, opening the YouTube app…")
                        openYouTube(ctx, url)
                    }
                }
                host == "photos.app.goo.gl" || host == "photos.google.com" || host == "goo.gl" -> {
                    toast(ctx, "Loading Google Photos album…")
                    val pics = GooglePhotos.resolve(url)
                    if (pics.isEmpty()) toast(ctx, "No pictures found. Is the album shared by link?")
                    else play(ctx, CastRequest.Images(pics, List(pics.size) { "Item ${it + 1}" }, 0, slideshow = true, albumVideos = true))
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
            android.util.Log.w("TvCast", "link failed: ${e.javaClass.simpleName}")
            toast(ctx, "Couldn't open that link.")
        }
    }

    private fun play(ctx: Context, r: CastRequest) {
        Bus.pending = r
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openYouTube(ctx: Context, link: String) {
        // The Fire TV YouTube app ignores youtu.be / shorts links; it only opens watch?v= links.
        val uri = Uri.parse(YouTube.watchUrl(link) ?: link)
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val yt = Intent(i).setPackage("com.amazon.firetv.youtube")
        try {
            ctx.startActivity(yt)
        } catch (e: Exception) {
            try { ctx.startActivity(i) } catch (e2: Exception) { toast(ctx, "YouTube app isn't installed on the TV.") }
        }
    }

    /**
     * Returns a URL the player can play. Direct media is returned as-is; for web pages the
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
        // Skips Google's "Before you continue" consent page, which some VPN exit countries get instead of the album.
        c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        val started = System.currentTimeMillis()
        val html = try {
            // goo.gl short links go through one or two redirects to photos.google.com/share/...
            val host = c.url.host.lowercase()
            if (!host.endsWith("google.com") && !host.endsWith("goo.gl")) return emptyList()
            // Read at most 8 MB of the page (the TV has little memory).
            c.inputStream.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (buf.size() < MAX_PAGE) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
        val finalHost = c.url.host.lowercase()
        // Shared-album photos are served from lh3.googleusercontent.com/pw/<id>.
        val re = Regex("""https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_\-]{40,}""")
        val items = if (finalHost.endsWith("photos.google.com")) {
            re.findAll(html).map { it.value }.distinct().take(MAX_PHOTOS).map { "$it=w1920-h1080" }.toList()
        } else emptyList()
        // Diagnostics only: no link or album id is logged.
        android.util.Log.i("TvCast", "album: host=$finalHost page=${html.length / 1024}KB items=${items.size} in ${System.currentTimeMillis() - started}ms")
        return items
    }

    /**
     * Google Photos serves an album item's video at "<item>=dv". For photos that address isn't a video,
     * so a 1-byte request tells the two apart. Returns the video URL, or null for a photo.
     */
    fun videoUrl(item: String): String? {
        val dv = item.substringBeforeLast('=') + "=dv"
        val c = URL(dv).openConnection() as HttpURLConnection
        return try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("Range", "bytes=0-0")
            if (c.responseCode in 200..299 && c.contentType.orEmpty().lowercase().startsWith("video/")) dv else null
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }.also { android.util.Log.i("TvCast", "album item: ${if (it != null) "video" else "photo"}") }
    }

    private const val MAX_PAGE = 8 * 1024 * 1024
    private const val MAX_PHOTOS = 500
}
