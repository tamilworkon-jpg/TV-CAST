package com.cleantv.tv

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Built-in VPN for online links and radio (WireGuard, runs inside TV Cast).
 *
 *  - Only TV Cast's own traffic goes through it (other TV apps are untouched).
 *  - Home-network addresses stay outside the tunnel, so the phone's media keeps working.
 *  - Default server: Cloudflare WARP (free, no account; a key is registered once).
 *    The phone can send a WireGuard config from any provider to use instead.
 *  - Up only while an online link/radio is playing; down when the player closes.
 */
object Vpn {
    private const val TAG = "TvCast"
    private const val WARP_PEER = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private const val WARP_API = "https://api.cloudflareclient.com/v0a2158/reg"

    // Everything except private/link-local/multicast ranges, so LAN traffic bypasses the tunnel.
    private const val PUBLIC_V4 = "0.0.0.0/5, 8.0.0.0/7, 11.0.0.0/8, 12.0.0.0/6, 16.0.0.0/4, 32.0.0.0/3, " +
        "64.0.0.0/2, 128.0.0.0/3, 160.0.0.0/5, 168.0.0.0/6, 172.0.0.0/12, 172.32.0.0/11, 172.64.0.0/10, " +
        "172.128.0.0/9, 173.0.0.0/8, 174.0.0.0/7, 176.0.0.0/4, 192.0.0.0/9, 192.128.0.0/11, 192.160.0.0/13, " +
        "192.169.0.0/16, 192.170.0.0/15, 192.172.0.0/14, 192.176.0.0/12, 192.192.0.0/10, 193.0.0.0/8, " +
        "194.0.0.0/7, 196.0.0.0/6, 200.0.0.0/5, 208.0.0.0/4"
    private const val PUBLIC_V6 = "2000::/3"

    private val tunnel = object : Tunnel {
        override fun getName() = "tvcast"
        override fun onStateChange(newState: Tunnel.State) {
            up = newState == Tunnel.State.UP
        }
    }
    private var backend: GoBackend? = null
    private val lock = Any()

    @Volatile var up = false
        private set

    /** Started from the phone's VPN button: stays on until Stop, instead of closing with the player. */
    @Volatile var keepOn = false

    /** Last error shown to the user, or null. */
    @Volatile var error: String? = null
        private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    fun provider(ctx: Context): String = if (sp(ctx).getString("custom", null) != null) "Custom" else "Cloudflare WARP"

    /** Saves a user WireGuard config (validated first). Null/blank switches back to WARP. */
    fun setCustom(ctx: Context, text: String?): Boolean {
        if (text.isNullOrBlank()) {
            sp(ctx).edit().remove("custom").apply()
            return true
        }
        if (text.length > 8000) return false
        val fixed = confine(ctx, text)
        if (runCatching { Config.parse(ByteArrayInputStream(fixed.toByteArray())) }.isFailure) return false
        sp(ctx).edit().putString("custom", fixed).apply()
        return true
    }

    /** True once the one-time Android VPN permission has been given (PlayerActivity asks for it). */
    fun permitted(ctx: Context): Boolean = VpnService.prepare(ctx.applicationContext) == null

    /** Brings the tunnel up (blocking, call off the main thread). Needs [permitted] first. */
    fun ensureUp(ctx: Context): Boolean = synchronized(lock) {
        error = null
        if (up) return true
        val app = ctx.applicationContext
        try {
            if (!permitted(app)) {
                error = "VPN not allowed on the TV yet"
                return false
            }
            val text = sp(app).getString("custom", null) ?: warpConfig(app)
            val config = Config.parse(ByteArrayInputStream(text.toByteArray()))
            val b = backend ?: GoBackend(app).also { backend = it }
            b.setState(tunnel, Tunnel.State.UP, config)
            up = true
            return true
        } catch (e: Exception) {
            Log.w(TAG, "vpn up failed: ${e.javaClass.simpleName}")
            error = "VPN couldn't connect"
            up = false
            return false
        }
    }

    fun down() = synchronized(lock) {
        if (!up) return
        try { backend?.setState(tunnel, Tunnel.State.DOWN, null) } catch (e: Exception) { }
        up = false
    }

    fun downAsync() { if (up) Thread { down() }.start() }

    /** Forces the tunnel to TV Cast only and keeps home-network traffic outside it. */
    private fun confine(ctx: Context, text: String): String {
        val out = StringBuilder()
        var section = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                if (section == "interface") out.append("IncludedApplications = ${ctx.packageName}\n")
                section = line.lowercase().removeSurrounding("[", "]")
            }
            val k = line.substringBefore('=').trim().lowercase()
            if (section == "interface" && (k == "includedapplications" || k == "excludedapplications")) continue
            if (section == "peer" && k == "allowedips") {
                val nets = line.substringAfter('=').split(',').map { it.trim() }.flatMap {
                    when (it) {
                        "0.0.0.0/0" -> PUBLIC_V4.split(", ")
                        "::/0" -> listOf(PUBLIC_V6)
                        else -> listOf(it)
                    }
                }
                out.append("AllowedIPs = ${nets.joinToString(", ")}\n")
                continue
            }
            out.append(raw).append('\n')
        }
        if (section == "interface") out.append("IncludedApplications = ${ctx.packageName}\n")
        return out.toString()
    }

    /** Registers a WARP key once (stored only on the TV) and returns the WireGuard config. */
    private fun warpConfig(ctx: Context): String {
        sp(ctx).getString("warp", null)?.let { return it }
        val keys = KeyPair()
        val body = JSONObject()
            .put("key", keys.publicKey.toBase64())
            .put("install_id", "").put("fcm_token", "")
            .put("tos", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
            .put("model", "TV").put("type", "Android").put("locale", "en_US")
        val c = URL(WARP_API).openConnection() as HttpURLConnection
        val resp = try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("User-Agent", "okhttp/3.12.1")
            c.setRequestProperty("CF-Client-Version", "a-6.30-3596")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) throw IllegalStateException("warp ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
        val cfg = resp.getJSONObject("config")
        val peer = cfg.getJSONArray("peers").getJSONObject(0)
        val addr = cfg.getJSONObject("interface").getJSONObject("addresses")
        val v6 = addr.optString("v6").takeIf { it.isNotBlank() }
        val text = """
            [Interface]
            PrivateKey = ${keys.privateKey.toBase64()}
            Address = ${addr.getString("v4")}/32${v6?.let { ", $it/128" }.orEmpty()}
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280
            IncludedApplications = ${ctx.packageName}

            [Peer]
            PublicKey = ${peer.optString("public_key", WARP_PEER)}
            AllowedIPs = $PUBLIC_V4${if (v6 != null) ", $PUBLIC_V6" else ""}
            Endpoint = ${peer.optJSONObject("endpoint")?.optString("host")?.takeIf { it.contains(':') } ?: "engage.cloudflareclient.com:2408"}
            PersistentKeepalive = 25
        """.trimIndent()
        sp(ctx).edit().putString("warp", text).apply()
        return text
    }
}
