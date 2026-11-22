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

    override fun serve(s: IHTTPSession): Response = try {
        when (s.uri) {
            "/ping" -> json(JSONObject().put("app", "tvcast").put("name", deviceName(ctx)).toString())
            "/play" -> {
                val j = JSONObject(body(s))
                Bus.pending = CastRequest.Video(
                    j.getString("url"),
                    j.optString("title"),
                    if (j.isNull("sub")) null else j.getString("sub"),
                    j.optString("key"),
                    j.optLong("startMs"),
                )
                openPlayer()
                json("{\"ok\":true}")
            }
            "/images" -> {
                val j = JSONObject(body(s))
                Bus.pending = CastRequest.Images(j.getJSONArray("urls").strings(), j.optJSONArray("names")?.strings().orEmpty(), j.optInt("index"))
                openPlayer()
                json("{\"ok\":true}")
            }
            "/control" -> {
                val cmd = s.parameters["cmd"]?.firstOrNull().orEmpty()
                val v = s.parameters["v"]?.firstOrNull()
                Bus.main.post { Bus.controller?.onCommand(cmd, v) }
                json("{\"ok\":true}")
            }
            "/status" -> json(Bus.status)
            "/history" -> json(History.all(ctx).toString())
            "/hello" -> {
                val j = JSONObject(body(s))
                ctx.getSharedPreferences("phone", Context.MODE_PRIVATE).edit()
                    .putString("base", j.getString("base")).putString("name", j.optString("name", "Phone")).apply()
                Bus.main.post { Bus.onPhoneChanged?.invoke() }
                json("{\"ok\":true}")
            }
            "/clear" -> {
                clearAll(ctx)
                json("{\"ok\":true}")
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.toString())
    }

    private fun openPlayer() {
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun json(text: String) = newFixedLengthResponse(Response.Status.OK, "application/json", text)

    private fun body(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        DataInputStream(s.inputStream).readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }

    companion object {
        const val PORT = 8091
    }
}

fun deviceName(ctx: Context): String =
    Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

fun localIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.inetAddresses.toList() }
    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    ?.hostAddress
