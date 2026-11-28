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

data class Tv(val ip: String, val name: String, val id: String = "", val paired: Boolean = false)

/** Phone half of the pairing / request-signing scheme (see the TV's Auth.kt). */
object PhoneAuth {
    private val rng = java.security.SecureRandom()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun phoneId(ctx: Context): String = sp(ctx).getString("phone_id", null)
        ?: hex(ByteArray(12).also { rng.nextBytes(it) }).also { sp(ctx).edit().putString("phone_id", it).apply() }

    fun key(ctx: Context, tvId: String): String? = if (tvId.isEmpty()) null else sp(ctx).getString("key_$tvId", null)

    fun saveKey(ctx: Context, tvId: String, key: String) = sp(ctx).edit().putString("key_$tvId", key).apply()

    fun forget(ctx: Context, tvId: String) = sp(ctx).edit().remove("key_$tvId").apply()

    fun headers(ctx: Context, tvId: String, method: String, url: URL, body: ByteArray): Map<String, String> {
        val key = key(ctx, tvId) ?: return emptyMap()
        val ts = System.currentTimeMillis()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(unhex(key), "HmacSHA256"))
        val bodyHash = hex(java.security.MessageDigest.getInstance("SHA-256").digest(body))
        val msg = "$method\n${url.path}\n${url.query.orEmpty()}\n$ts\n$bodyHash"
        return mapOf("X-Phone" to phoneId(ctx), "X-Ts" to ts.toString(), "X-Sig" to hex(mac.doFinal(msg.toByteArray())))
    }

    /** Encrypts [text] for the paired TV (AES-256-GCM with a key derived from the pairing key). */
    fun seal(ctx: Context, tvId: String, text: String): String? {
        val key = key(ctx, tvId) ?: return null
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(unhex(key), "HmacSHA256"))
        val aes = javax.crypto.spec.SecretKeySpec(mac.doFinal("tvcast-seal".toByteArray()), "AES")
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, aes, javax.crypto.spec.GCMParameterSpec(128, iv))
        return android.util.Base64.encodeToString(iv + c.doFinal(text.toByteArray()), android.util.Base64.NO_WRAP)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

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

    fun get(url: String, timeoutMs: Int = 3000, headers: Map<String, String> = emptyMap()): String? =
        request(url, null, timeoutMs, headers)

    fun post(url: String, body: String, timeoutMs: Int = 5000, headers: Map<String, String> = emptyMap()): String? =
        request(url, body, timeoutMs, headers)

    /** Like [post] but returns the HTTP status too (pairing needs to tell "wrong PIN" from "no TV"). */
    fun postStatus(url: String, body: String, timeoutMs: Int = 5000): Pair<Int, String?> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        try {
            val code = c.responseCode
            code to (if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null)
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        -1 to null
    }

    private fun request(url: String, body: String?, timeoutMs: Int, headers: Map<String, String>): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
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

    /** Set once from Application; needed to sign requests. */
    lateinit var app: Context

    private fun sget(tv: Tv, path: String, timeoutMs: Int = 3000): String? {
        val url = "${base(tv)}$path"
        return Net.get(url, timeoutMs, PhoneAuth.headers(app, tv.id, "GET", URL(url), ByteArray(0)))
    }

    private fun spost(tv: Tv, path: String, body: String, timeoutMs: Int = 5000): String? {
        val url = "${base(tv)}$path"
        return Net.post(url, body, timeoutMs, PhoneAuth.headers(app, tv.id, "POST", URL(url), body.toByteArray()))
    }

    fun ping(ip: String, timeoutMs: Int = 800, tvId: String = ""): Tv? {
        val url = "http://$ip:$PORT/ping"
        val r = Net.get(url, timeoutMs, PhoneAuth.headers(app, tvId, "GET", URL(url), ByteArray(0))) ?: return null
        return try {
            val j = JSONObject(r)
            if (j.optString("app") == "tvcast") Tv(ip, j.optString("name", ip), j.optString("id"), j.optBoolean("paired"))
            else null
        } catch (e: Exception) {
            null
        }
    }

    /** Asks the TV to show an Allow/Deny prompt. Returns null if sent, else an error message. */
    fun pairStart(tv: Tv, phoneName: String): String? {
        val body = JSONObject().put("name", phoneName).put("phoneId", PhoneAuth.phoneId(app)).toString()
        val (code, _) = Net.postStatus("${base(tv)}/pair/start", body)
        return when {
            code in 200..299 -> null
            code == 403 -> "The TV refused too many requests. Try again in 10 minutes."
            code == 429 -> "Please wait a few seconds and try again."
            code > 0 -> "The TV app needs updating."
            else -> "TV not reachable"
        }
    }

    /** "waiting", "allowed" (key stored) or "denied"; null if the TV couldn't be reached. */
    fun pairPoll(tv: Tv): String? {
        val body = JSONObject().put("phoneId", PhoneAuth.phoneId(app)).toString()
        val (code, text) = Net.postStatus("${base(tv)}/pair/finish", body, 3000)
        if (code == 403) return "denied"
        if (code !in 200..299 || text == null) return null
        val j = JSONObject(text)
        if (j.optString("state") != "allowed") return "waiting"
        PhoneAuth.saveKey(app, j.optString("id").ifEmpty { tv.id }, j.getString("key"))
        return "allowed"
    }

    /**
     * Probes every address in each of the phone's local /24 networks (home Wi-Fi and/or its own hotspot);
     * [onFound] is called on the main thread.
     */
    suspend fun scan(tvId: String = "", onFound: (Tv) -> Unit) {
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
                            ping(ip, 800, tvId)?.let { tv -> withContext(Dispatchers.Main) { onFound(tv) } }
                        }
                    }
                }
            }
        }
    }

    /**
     * Makes sure the saved TV is reachable; if its IP changed (DHCP), finds it again (by its id) and saves the new address.
     * When [announce] is set, also tells the TV where this phone's media is. Returns the reachable TV or null.
     */
    suspend fun ensure(ctx: Context, announce: Boolean = true): Tv? {
        val saved = Prefs.lastTv(ctx) ?: return null
        var tv = withContext(Dispatchers.IO) { ping(saved.ip, 1500, saved.id) }
        if (tv == null || (saved.id.isNotEmpty() && tv.id != saved.id)) {
            var found: Tv? = null
            scan(saved.id) { t ->
                if (found == null && (if (saved.id.isNotEmpty()) t.id == saved.id else t.name == saved.name)) found = t
            }
            tv = found ?: return null
        }
        Prefs.saveTv(ctx, tv)
        if (announce && tv.paired) {
            val myIp = withContext(Dispatchers.IO) { Net.localIpFor(tv.ip) } ?: return tv
            val base = "http://$myIp:${MediaServer.PORT}/${Prefs.token(ctx)}"
            withContext(Dispatchers.IO) { hello(tv, base, phoneName(ctx)) }
        }
        return tv
    }

    fun phoneName(ctx: Context): String =
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL

    /** Plays [items] on the TV as a queue starting at [index] (Next/Previous move through it). */
    fun play(tv: Tv, items: JSONArray, index: Int, startMs: Long): Boolean {
        val j = JSONObject().put("items", items).put("index", index).put("startMs", startMs)
        return spost(tv, "/play", j.toString(), 10_000) != null
    }

    fun images(tv: Tv, urls: List<String>, names: List<String>, index: Int, slideshow: Boolean = false): Boolean {
        val j = JSONObject().put("urls", JSONArray(urls)).put("names", JSONArray(names)).put("index", index).put("slideshow", slideshow)
        return spost(tv, "/images", j.toString(), 10_000) != null
    }

    /** Online link (video page, stream, YouTube, Google Photos album). The TV keeps nothing about it. */
    fun url(tv: Tv, url: String, vpn: Boolean): Boolean =
        spost(tv, "/url", JSONObject().put("url", url).put("vpn", vpn).toString()) != null

    fun radio(tv: Tv, url: String, name: String, favicon: String?, vpn: Boolean): Boolean =
        spost(tv, "/radio", JSONObject().put("url", url).put("name", name).put("favicon", favicon ?: "").put("vpn", vpn).toString()) != null

    /** Sets the TV's built-in VPN server: a WireGuard config, or blank for Cloudflare WARP. Returns the provider name. */
    fun vpnConfig(tv: Tv, config: String): String? {
        val sealed = PhoneAuth.seal(app, tv.id, config) ?: return null
        return spost(tv, "/vpn", JSONObject().put("sealed", sealed).toString(), 5000)?.let {
            runCatching { JSONObject(it).optString("provider") }.getOrNull()
        }
    }

    /** TV's built-in VPN: {up, keepOn, permitted, provider, error}, or null if the TV can't be reached. */
    fun vpnState(tv: Tv): JSONObject? = sget(tv, "/vpn/state", 2000)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun vpnStart(tv: Tv): JSONObject? = spost(tv, "/vpn/start", "{}", 3000)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun vpnStop(tv: Tv): JSONObject? = spost(tv, "/vpn/stop", "{}", 3000)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun control(tv: Tv, cmd: String, v: String? = null): Boolean =
        sget(tv, "/control?cmd=$cmd" + (v?.let { "&v=" + URLEncoder.encode(it, "UTF-8") } ?: "")) != null

    fun status(tv: Tv): JSONObject? = sget(tv, "/status", 1500)?.let {
        try { JSONObject(it) } catch (e: Exception) { null }
    }

    fun history(tv: Tv, timeoutMs: Int = 4000): String? = sget(tv, "/history", timeoutMs)

    /** Tells the TV where this phone's library lives so it can be browsed from the TV remote. */
    fun hello(tv: Tv, base: String, name: String): Boolean =
        spost(tv, "/hello", JSONObject().put("base", base).put("name", name).toString(), 2000) != null

    /** Phone is disconnecting: TV closes whatever it was showing from this phone. */
    fun bye(tv: Tv): Boolean = spost(tv, "/bye", "{}", 2000) != null

    fun clear(tv: Tv): Boolean = sget(tv, "/clear", 2000) != null
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
        val id = sp(ctx).getString("tv_id", "") ?: ""
        return Tv(ip, sp(ctx).getString("tv_name", ip) ?: ip, id, PhoneAuth.key(ctx, id) != null)
    }

    fun saveTv(ctx: Context, tv: Tv) = sp(ctx).edit().putString("tv_ip", tv.ip).putString("tv_name", tv.name)
        .putString("tv_id", tv.id).apply()

    fun getFlag(ctx: Context, key: String) = sp(ctx).getBoolean(key, false)
    fun setFlag(ctx: Context, key: String) = sp(ctx).edit().putBoolean(key, true).apply()

    fun token(ctx: Context): String = sp(ctx).getString("token", null) ?: java.util.UUID.randomUUID().toString()
        .replace("-", "").also { sp(ctx).edit().putString("token", it).apply() }

    fun historyJson(ctx: Context): String = sp(ctx).getString("history", "[]") ?: "[]"

    fun saveHistory(ctx: Context, json: String) = sp(ctx).edit().putString("history", json).apply()

    /** Updates one entry of the cached TV history (moves it to the front), e.g. from the remote's live status. */
    fun mergeProgress(ctx: Context, key: String, title: String, pos: Long, dur: Long) {
        val old = try { JSONArray(sp(ctx).getString("history", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray().put(JSONObject().put("key", key).put("title", title).put("pos", pos).put("dur", dur)
            .put("t", System.currentTimeMillis()))
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("key") != key && out.length() < 50) out.put(o)
        }
        sp(ctx).edit().putString("history", out.toString()).apply()
    }

    fun clearHistory(ctx: Context) = sp(ctx).edit().remove("history").remove("links").apply()

    /** Built-in VPN for links/radio on the TV (on unless turned off). */
    fun vpn(ctx: Context) = sp(ctx).getBoolean("vpn", true)
    fun setVpn(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("vpn", on).apply()

    /** Links played on the TV, kept only on this phone (newest first, cleared with Clear history). */
    fun links(ctx: Context): List<String> = try {
        val a = JSONArray(sp(ctx).getString("links", "[]"))
        List(a.length()) { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    fun addLink(ctx: Context, url: String) {
        val list = (listOf(url) + links(ctx).filter { it != url }).take(50)
        sp(ctx).edit().putString("links", JSONArray(list).toString()).apply()
    }

    fun removeLink(ctx: Context, url: String) =
        sp(ctx).edit().putString("links", JSONArray(links(ctx).filter { it != url }).toString()).apply()

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
