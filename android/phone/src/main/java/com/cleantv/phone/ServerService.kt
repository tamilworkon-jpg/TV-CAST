package com.cleantv.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD

/** Keeps the media server alive while the screen is off or the app is in background. */
class ServerService : Service() {
    private var server: MediaServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Media sharing", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("TV Cast is sharing your media")
            .setContentText("Stops by itself after 1 hour without playback")
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        try {
            server = MediaServer(this, Prefs.token(this)).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: Exception) {
            Log.e("TvCast", "server start failed", e)
        }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvcast").apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvcast:server").apply { acquire() }
        running = true
        handler.postDelayed(idleCheck, IDLE_CHECK_MS)
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Stops sharing (and frees the wake/Wi-Fi locks) once the TV hasn't fetched anything for a while. */
    private val idleCheck = object : Runnable {
        override fun run() {
            val last = maxOf(server?.lastHit ?: 0L, lastStart)
            if (System.currentTimeMillis() - last > IDLE_LIMIT_MS) {
                Log.i("TvCast", "idle, stopping server")
                stopSelf()
            } else {
                handler.postDelayed(this, IDLE_CHECK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        lastStart = System.currentTimeMillis()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        server?.stop()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "server"
        private const val ACTION_STOP = "stop"
        private const val IDLE_LIMIT_MS = 60 * 60 * 1000L
        private const val IDLE_CHECK_MS = 60 * 1000L
        @Volatile var running = false
        @Volatile private var lastStart = System.currentTimeMillis()

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServerService::class.java))
        }
    }
}
