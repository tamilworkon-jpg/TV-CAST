package com.cleantv.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/** Always-on background service that listens for the phone and opens the player. */
class CastService : Service() {
    private var server: CastServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "TV Cast", NotificationManager.IMPORTANCE_MIN))
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("TV Cast is ready")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, 1, notification, type)

        try {
            server = CastServer(applicationContext).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: Exception) {
            Log.e("TvCast", "server start failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CastService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CastService.start(context)
    }
}

class CastServer(private val ctx: Context) : NanoHTTPD(PORT) {
    @Volatile private var lastPairStart = 0L
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun serve(s: IHTTPSession): Response {
        return try {
            serveChecked(s)
        } catch (e: Exception) {
            Log.w("TvCast", "request failed")
            status(Response.Status.BAD_REQUEST, "bad request")
        }
    }

    private fun serveChecked(s: IHTTPSession): Response {
        val body = readBody(s) ?: return status(Response.Status.PAYLOAD_TOO_LARGE, "too large")
        return when (s.uri) {
            // Open endpoints: discovery and pairing only.
            "/ping" -> {
                val paired = Auth.verify(ctx, s.method.name, s.uri, s.queryParameterString, body, s.headers)
                json(JSONObject().put("app", "tvcast").put("name", deviceName(ctx)).put("id", Auth.tvId(ctx))
                    .put("paired", paired).put("version", 2).toString())
            }
            "/pair/start" -> {
                val now = System.currentTimeMillis()
                if (now - lastPairStart < 5000) return status(Response.Status.TOO_MANY_REQUESTS, "wait")
                lastPairStart = now
                val j = runCatching { JSONObject(String(body)) }.getOrNull() ?: JSONObject()
                if (!Auth.startPairing(j.optString("name").ifBlank { "Phone" }, j.optString("phoneId"))) {
                    return status(Response.Status.FORBIDDEN, "locked")
                }
                // The Allow/Deny prompt appears on whichever TV Cast screen is open (home or player).
                Bus.main.post { Bus.onPairRequest?.invoke() }
                json("{\"ok\":true}")
            }
            "/pair/finish" -> {
                val j = JSONObject(String(body))
                val (state, key) = Auth.poll(j.optString("phoneId"))
                when (state) {
                    Auth.State.ALLOWED -> json(JSONObject().put("state", "allowed").put("key", key).put("id", Auth.tvId(ctx)).toString())
                    Auth.State.WAITING -> json("{\"state\":\"waiting\"}")
                    Auth.State.DENIED -> status(Response.Status.FORBIDDEN, "denied")
                }
            }
            else -> {
                if (!Auth.verify(ctx, s.method.name, s.uri, s.queryParameterString, body, s.headers)) {
                    status(Response.Status.UNAUTHORIZED, "not paired")
                } else {
                    secured(s, String(body, Charsets.UTF_8))
                }
            }
        }
    }

    private fun secured(s: IHTTPSession, body: String): Response = when (s.uri) {
        "/play" -> {
            val j = JSONObject(body)
            val items = if (j.has("items")) {
                val a = j.getJSONArray("items")
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    VideoItem(safeUrl(o.getString("url")), o.optString("title"),
                        o.optString("sub").takeIf { it.isNotEmpty() }?.let { safeUrl(it) }, o.optString("key"))
                }
            } else {
                listOf(VideoItem(safeUrl(j.getString("url")), j.optString("title"),
                    if (j.isNull("sub")) null else safeUrl(j.getString("sub")), j.optString("key")))
            }
            if (items.isEmpty()) status(Response.Status.BAD_REQUEST, "empty") else {
                Bus.pending = CastRequest.Video(items, j.optInt("index").coerceIn(0, items.size - 1), j.optLong("startMs"))
                openPlayer()
                ok()
            }
        }
        "/images" -> {
            val j = JSONObject(body)
            val urls = j.getJSONArray("urls").strings().map { safeUrl(it) }
            Bus.pending = CastRequest.Images(urls, j.optJSONArray("names")?.strings().orEmpty(), j.optInt("index"), j.optBoolean("slideshow"))
            openPlayer()
            ok()
        }
        "/url" -> {
            // Online link: resolved off the request thread (may need to load a web page first).
            val j = JSONObject(body)
            val url = safeUrl(j.getString("url"))
            worker.execute { Online.open(ctx, url, j.optString("title"), j.optBoolean("vpn", true)) }
            ok()
        }
        "/radio" -> {
            val j = JSONObject(body)
            val item = VideoItem(safeUrl(j.getString("url")), j.optString("name", "Radio"), online = true, radio = true,
                artwork = j.optString("favicon").takeIf { it.startsWith("http") })
            worker.execute { Online.radio(ctx, item, j.optBoolean("vpn", true)) }
            ok()
        }
        "/vpn" -> {
            // Built-in VPN server, encrypted by the phone: a WireGuard config, or "" for Cloudflare WARP.
            val j = JSONObject(body)
            val config = Auth.unseal(ctx, s.headers["x-phone"].orEmpty(), j.optString("sealed"))
            if (config == null || !Vpn.setCustom(ctx, config)) status(Response.Status.BAD_REQUEST, "invalid WireGuard config")
            else {
                worker.execute { Vpn.down() }
                json(JSONObject().put("provider", Vpn.provider(ctx)).toString())
            }
        }
        "/control" -> {
            val cmd = s.parameters["cmd"]?.firstOrNull().orEmpty()
            val v = s.parameters["v"]?.firstOrNull()
            Bus.main.post { Bus.controller?.onCommand(cmd, v) }
            ok()
        }
        "/status" -> json(Bus.status)
        "/history" -> json(History.all(ctx).toString())
        "/hello" -> {
            val j = JSONObject(body)
            ctx.getSharedPreferences("phone", Context.MODE_PRIVATE).edit()
                .putString("base", safeUrl(j.getString("base"))).putString("name", j.optString("name", "Phone").take(40)).apply()
            Bus.main.post { Bus.onPhoneChanged?.invoke() }
            ok()
        }
        "/bye" -> {
            // Phone disconnected: close whatever it was showing.
            Bus.main.post {
                Bus.controller?.onCommand("stop", null)
                Bus.onPhoneChanged?.invoke()
            }
            ok()
        }
        "/clear" -> {
            clearAll(ctx)
            ok()
        }
        else -> status(Response.Status.NOT_FOUND, "not found")
    }

    /** Only plain web links are accepted (no file:, content:, intent: …). */
    private fun safeUrl(u: String): String {
        val uri = android.net.Uri.parse(u.trim())
        require(uri.scheme == "http" || uri.scheme == "https") { "bad url" }
        require(!uri.host.isNullOrBlank()) { "bad url" }
        return u.trim()
    }

    private fun openPlayer() {
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun ok() = json("{\"ok\":true}")
    private fun json(text: String) = newFixedLengthResponse(Response.Status.OK, "application/json", text)
    private fun status(st: Response.Status, msg: String) = newFixedLengthResponse(st, MIME_PLAINTEXT, msg)

    private fun readBody(s: IHTTPSession): ByteArray? {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) return null
        val buf = ByteArray(len)
        if (len > 0) DataInputStream(s.inputStream).readFully(buf)
        return buf
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }

    companion object {
        const val PORT = 8091
        private const val MAX_BODY = 2 * 1024 * 1024
    }
}

fun deviceName(ctx: Context): String =
    Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

fun localIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.inetAddresses.toList() }
    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    ?.hostAddress
