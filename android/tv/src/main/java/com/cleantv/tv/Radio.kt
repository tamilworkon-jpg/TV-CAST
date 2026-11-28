package com.cleantv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A live internet radio station. */
data class Station(val name: String, val url: String, val logo: String?, val info: String = "")

/**
 * Internet radio picked straight from the TV (no phone needed).
 * Stations come from the free, community-run radio-browser.info directory, same as the phone's Radio page.
 */
object Radio {
    /** Category value meaning "stations played on this TV". */
    const val RECENT = "#recent"

    /** Quick categories on the TV: label to search text ("" = most popular in India). */
    val CATEGORIES = listOf(
        "Played recently" to RECENT,
        "Tamil" to "tamil", "Hindi" to "hindi", "Telugu" to "telugu", "Malayalam" to "malayalam",
        "Kannada" to "kannada", "Bollywood" to "bollywood", "Devotional" to "devotional",
        "News" to "news", "English" to "english", "Most popular in India" to "",
    )

    private val SERVERS = listOf("de1", "de2", "fi1", "nl1", "at1")
    private val cache = HashMap<String, List<Station>>()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)

    /** Last chosen category or search text. */
    fun query(ctx: Context): String = sp(ctx).getString("query", "tamil") ?: "tamil"
    fun setQuery(ctx: Context, q: String) = sp(ctx).edit().putString("query", q).apply()

    /** Uses the built-in VPN like the phone does; follows the phone's last "Use built-in VPN" choice. */
    fun vpn(ctx: Context): Boolean = sp(ctx).getBoolean("vpn", true)
    fun rememberVpn(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("vpn", on).apply()

    /** Stations played on this TV, newest first (only names and stream addresses, at most 20). */
    fun recent(ctx: Context): List<Station> = try {
        val a = JSONArray(sp(ctx).getString("recent", "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            Station(o.getString("name"), o.getString("url"), o.optString("logo").takeIf { l -> l.startsWith("http") }, o.optString("info"))
        }
    } catch (e: Exception) { emptyList() }

    fun remember(ctx: Context, s: Station) {
        val list = (listOf(s) + recent(ctx).filter { it.url != s.url }).take(20)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("logo", it.logo ?: "").put("info", it.info)) }
        sp(ctx).edit().putString("recent", a.toString()).apply()
    }

    /** Drops a cached station list so Refresh fetches it again. */
    fun forget(q: String) { synchronized(cache) { cache.remove(q) } }

    fun clearRecent(ctx: Context) = sp(ctx).edit().remove("recent").apply()

    /** Network call: run off the main thread. Returns null when the directory can't be reached. */
    fun search(ctx: Context, q: String): List<Station>? {
        if (q == RECENT) return recent(ctx)
        synchronized(cache) { cache[q] }?.let { return it }
        for (server in SERVERS) {
            val base = "https://$server.api.radio-browser.info/json/stations/search?countrycode=IN&hidebroken=true&order=clickcount&reverse=true&limit=100"
            val enc = URLEncoder.encode(q, "UTF-8")
            val list = runCatching {
                var l = fetch(base + if (q.isNotEmpty()) "&name=$enc" else "")
                // Many stations are tagged by language rather than named after it.
                if (q.isNotEmpty()) l = (l + fetch("$base&tag=$enc")).distinctBy { it.url }
                l
            }.getOrNull() ?: continue
            synchronized(cache) { cache[q] = list }
            return list
        }
        return null
    }

    private fun fetch(url: String): List<Station> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "TVCast/2")
        val text = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val u = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (!u.startsWith("http")) return@mapNotNull null
            val tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" · ")
            val kbps = o.optInt("bitrate").takeIf { it > 0 }?.let { "$it kbps" }
            Station(
                o.optString("name").trim().ifBlank { "Station" }, u,
                o.optString("favicon").takeIf { it.startsWith("http") },
                listOfNotNull(tags.ifBlank { null }, kbps).joinToString(" · "),
            )
        }.distinctBy { it.url }
    }

    fun item(s: Station) = VideoItem(s.url, s.name, online = true, radio = true, artwork = s.logo)
}
