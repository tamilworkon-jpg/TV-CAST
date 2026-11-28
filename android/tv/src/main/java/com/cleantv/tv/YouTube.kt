package com.cleantv.tv

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plays YouTube links in TV Cast's own player (no ads, through the built-in VPN).
 * Asks YouTube's player API for the video's HLS stream (adaptive quality, audio + video
 * together), the same way open-source players such as NewPipe do. YouTube changes this
 * API from time to time; when it fails the caller falls back to the TV's YouTube app.
 */
object YouTube {
    // visionOS app client (same values as NewPipeExtractor v0.27.1); it returns HLS streams.
    private const val CLIENT_NAME = "VISIONOS"
    private const val CLIENT_VERSION = "1.04"
    private const val DEVICE_MODEL = "RealityDevice17,1"
    private const val OS_VERSION = "26.6.0.23O770"
    private const val UA = "com.google.visionos.youtube/$CLIENT_VERSION($DEVICE_MODEL; U; CPU visionOS 26_6_0 like Mac OS X; US)"

    fun isYouTube(host: String): Boolean =
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

    /** Video id from watch / youtu.be / shorts / live / embed links, or null. */
    fun videoId(link: String): String? {
        val u = Uri.parse(link)
        val host = u.host?.lowercase().orEmpty()
        val seg = u.pathSegments
        val id = when {
            host == "youtu.be" -> seg.firstOrNull()
            u.getQueryParameter("v") != null -> u.getQueryParameter("v")
            seg.size >= 2 && seg[0] in listOf("shorts", "live", "embed", "v") -> seg[1]
            else -> null
        }
        return id?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
    }

    /** Canonical watch link (the TV's YouTube app only understands this form). */
    fun watchUrl(link: String): String? = videoId(link)?.let { "https://www.youtube.com/watch?v=$it" }

    /** Resolves a YouTube link to an HLS stream the player can play, or null. Blocking; call off the main thread. */
    fun resolve(link: String): VideoItem? {
        val id = videoId(link) ?: return null
        val visitor = post("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", context(null))
            .optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }
        val body = context(visitor).put("videoId", id)
        val t = (1..12).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".random() }.joinToString("")
        val player = post("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&t=$t&id=$id", body)
        if (player.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val hls = player.optJSONObject("streamingData")?.optString("hlsManifestUrl")?.takeIf { it.startsWith("https://") } ?: return null
        val title = player.optJSONObject("videoDetails")?.optString("title")?.takeIf { it.isNotBlank() } ?: "YouTube"
        return VideoItem(hls, title, online = true, hls = true)
    }

    private fun context(visitorData: String?): JSONObject {
        val client = JSONObject()
            .put("clientName", CLIENT_NAME).put("clientVersion", CLIENT_VERSION).put("clientScreen", "WATCH")
            .put("platform", "MOBILE").put("deviceMake", "Apple").put("deviceModel", DEVICE_MODEL)
            .put("osName", "visionOS").put("osVersion", OS_VERSION)
            .put("hl", "en").put("gl", "US").put("utcOffsetMinutes", 0)
        visitorData?.let { client.put("visitorData", it) }
        return JSONObject().put("context", JSONObject().put("client", client)
            .put("request", JSONObject().put("useSsl", true))
            .put("user", JSONObject().put("lockedSafetyMode", false)))
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("X-Goog-Api-Format-Version", "2")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) return JSONObject()
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
