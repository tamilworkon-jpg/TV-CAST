package com.cleantv.phone

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads a Google Photos *shared album* link on the phone (plenty of memory) and returns full-HD
 * picture URLs for the TV. The TV then only downloads the pictures/videos themselves, through its VPN.
 * Nothing is stored; no sign-in.
 */
object AlbumLink {
    private const val MAX_PAGE = 16 * 1024 * 1024
    private const val MAX_ITEMS = 500

    fun isAlbum(link: String): Boolean {
        val host = Uri.parse(link).host?.lowercase().orEmpty()
        return host == "photos.app.goo.gl" || host == "photos.google.com" || host == "goo.gl"
    }

    /** Blocking; call off the main thread. Empty list if the album couldn't be read. */
    fun resolve(link: String): List<String> {
        val c = URL(link).openConnection() as HttpURLConnection
        val html = try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            // Skips Google's "Before you continue" consent page.
            c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
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
        if (!c.url.host.lowercase().endsWith("photos.google.com")) return emptyList()
        // Shared-album items are served from lh3.googleusercontent.com/pw/<id>.
        return Regex("""https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_\-]{40,}""")
            .findAll(html).map { it.value }.distinct().take(MAX_ITEMS)
            .map { "$it=w1920-h1080" }.toList()
    }
}
