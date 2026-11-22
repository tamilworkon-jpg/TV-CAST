package com.cleantv.phone

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder

data class Tv(val ip: String, val name: String)

object Net {
    // Mobile-data / VPN / virtual interfaces; their private 10.x addresses are not reachable from the TV.
    private val SKIP = listOf("rmnet", "ccmni", "v4-", "clat", "tun", "ppp", "dummy", "radio", "ifb", "ip6tnl", "sit")
    private val LAN = listOf("wlan", "ap", "swlan", "softap", "eth", "rndis", "usb", "bt-pan")

    /** LAN IPv4 addresses of this phone (Wi-Fi and hotspot), Wi-Fi/hotspot interfaces first. */
    fun localIps(): List<String> {
        val out = mutableListOf<Pair<String, String>>()
        for (nif in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (SKIP.any { name.startsWith(it) }) continue
            for (addr in nif.inetAddresses.toList()) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) out += name to addr.hostAddress!!
            }
        }
        return out.sortedBy { (name, _) -> if (LAN.any { name.startsWith(it) }) 0 else 1 }.map { it.second }.distinct()
    }

    fun localIp(): String? = localIps().firstOrNull()

    /**
     * The phone address on the same network as [remoteIp]. Matters when the phone is on home Wi-Fi
     * and also running a hotspot: the TV must be given the address of the network it is on.
     */
    fun localIpFor(remoteIp: String): String? {
        val prefix = remoteIp.substringBeforeLast('.')
        val all = localIps()
        return all.firstOrNull { it.substringBeforeLast('.') == prefix } ?: all.firstOrNull()
    }

    fun get(url: String, timeoutMs: Int = 3000): String? = request(url, null, timeoutMs)
    fun post(url: String, body: String, timeoutMs: Int = 5000): String? = request(url, body, timeoutMs)

    private fun request(url: String, body: String?, timeoutMs: Int): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}

object TvClient {
    const val PORT = 8091
    private fun base(tv: Tv) = "http://${tv.ip}:$PORT"

    fun ping(ip: String, timeoutMs: Int = 800): Tv? {
        val r = Net.get("http://$ip:$PORT/ping", timeoutMs) ?: return null
        return try {
            val j = JSONObject(r)
            if (j.optString("app") == "tvcast") Tv(ip, j.optString("name", ip)) else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Probes every address in each of the phone's local /24 networks (home Wi-Fi and/or its own hotspot);
     * [onFound] is called on the main thread.
     */
    suspend fun scan(onFound: (Tv) -> Unit) {
        val mine = withContext(Dispatchers.IO) { Net.localIps() }
        val sem = Semaphore(48)
        coroutineScope {
            for (myIp in mine) {
                val prefix = myIp.substringBeforeLast('.')
                for (i in 1..254) {
                    val ip = "$prefix.$i"
                    if (ip in mine) continue
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            ping(ip)?.let { tv -> withContext(Dispatchers.Main) { onFound(tv) } }
                        }
                    }
                }
            }
        }
    }

    /**
     * Makes sure the saved TV is reachable; if its IP changed (DHCP), finds it again by name and saves the new address.
     * Also announces this phone's library to it. Returns the reachable TV or null.
     */
    suspend fun ensure(ctx: Context): Tv? {
        val saved = Prefs.lastTv(ctx) ?: return null
        var tv = withContext(Dispatchers.IO) { ping(saved.ip, 1500) }
        if (tv == null) {
            var found: Tv? = null
            scan { t -> if (found == null && t.name == saved.name) found = t }
            tv = found ?: return null
            Prefs.saveTv(ctx, tv)
        }
        val myIp = withContext(Dispatchers.IO) { Net.localIpFor(tv.ip) } ?: return null
        val base = "http://$myIp:${MediaServer.PORT}/${Prefs.token(ctx)}"
        withContext(Dispatchers.IO) { hello(tv, base, phoneName(ctx)) }
        return tv
    }

    fun phoneName(ctx: Context): String =
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL

    fun play(tv: Tv, url: String, title: String, sub: String?, key: String, startMs: Long): Boolean {
        val j = JSONObject().put("url", url).put("title", title).put("sub", sub ?: JSONObject.NULL)
            .put("key", key).put("startMs", startMs)
        return Net.post("${base(tv)}/play", j.toString()) != null
    }

    fun images(tv: Tv, urls: List<String>, names: List<String>, index: Int): Boolean {
        val j = JSONObject().put("urls", JSONArray(urls)).put("names", JSONArray(names)).put("index", index)
        return Net.post("${base(tv)}/images", j.toString(), 10_000) != null
    }

    fun control(tv: Tv, cmd: String, v: String? = null): Boolean =
        Net.get("${base(tv)}/control?cmd=$cmd" + (v?.let { "&v=" + URLEncoder.encode(it, "UTF-8") } ?: "")) != null

    fun status(tv: Tv): JSONObject? = Net.get("${base(tv)}/status", 1500)?.let {
        try { JSONObject(it) } catch (e: Exception) { null }
    }

    fun history(tv: Tv): String? = Net.get("${base(tv)}/history", 1500)

    /** Tells the TV where this phone's library lives so it can be browsed from the TV remote. */
    fun hello(tv: Tv, base: String, name: String): Boolean =
        Net.post("${base(tv)}/hello", JSONObject().put("base", base).put("name", name).toString(), 2000) != null

    fun clear(tv: Tv): Boolean = Net.get("${base(tv)}/clear", 2000) != null
}

data class Progress(val pos: Long, val dur: Long, val time: Long) {
    val fraction: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = dur > 0 && pos >= dur - 30_000
    val resumable: Boolean get() = pos > 10_000 && !finished
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("tvcast", Context.MODE_PRIVATE)

    fun lastTv(ctx: Context): Tv? {
        val ip = sp(ctx).getString("tv_ip", null) ?: return null
        return Tv(ip, sp(ctx).getString("tv_name", ip) ?: ip)
    }

    fun saveTv(ctx: Context, tv: Tv) = sp(ctx).edit().putString("tv_ip", tv.ip).putString("tv_name", tv.name).apply()

    fun getFlag(ctx: Context, key: String) = sp(ctx).getBoolean(key, false)
    fun setFlag(ctx: Context, key: String) = sp(ctx).edit().putBoolean(key, true).apply()

    fun token(ctx: Context): String = sp(ctx).getString("token", null) ?: java.util.UUID.randomUUID().toString()
        .replace("-", "").also { sp(ctx).edit().putString("token", it).apply() }

    fun saveHistory(ctx: Context, json: String) = sp(ctx).edit().putString("history", json).apply()

    fun clearHistory(ctx: Context) = sp(ctx).edit().remove("history").apply()

    /** Cached copy of the TV's play history (last 30 days), keyed by [Media.key], newest first. */
    fun history(ctx: Context): LinkedHashMap<String, Progress> {
        val out = LinkedHashMap<String, Progress>()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        try {
            val arr = JSONArray(sp(ctx).getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optLong("t") < cutoff) continue
                out[o.getString("key")] = Progress(o.optLong("pos"), o.optLong("dur"), o.optLong("t"))
            }
        } catch (_: Exception) {
        }
        return out
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%d MB".format(bytes / (1L shl 20))
}
