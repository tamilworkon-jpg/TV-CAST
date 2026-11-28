package com.cleantv.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Invisible screen that hands a phone video to another player app (VLC, MX Player…) and waits for it:
 *  - keeps telling the phone the TV still needs it, so sharing isn't stopped while the other app plays;
 *  - when the other app closes, saves where it stopped, so Continue/resume stays in step.
 */
class ExternalActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var base: String? = null
    private var key = ""
    private var title = ""
    private var startedAt = 0L

    private val keepAlive = object : Runnable {
        override fun run() {
            val b = base ?: return
            Thread { ping("$b/alive") }.start()
            // Stop after a long movie's worth of time in case this screen is never returned to.
            if (System.currentTimeMillis() - startedAt < MAX_MS) handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        base = intent.getStringExtra(EXTRA_BASE)
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        startedAt = System.currentTimeMillis()
        handler.post(keepAlive)
        if (savedInstanceState != null) return // already handed over; waiting for the result
        val pos = intent.getLongExtra(EXTRA_POS, 0L)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        val sub = intent.getStringExtra(EXTRA_SUB)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", title)
            .putExtra("position", pos.toInt())  // MX Player
            .putExtra("return_result", true)    // MX Player: report where it stopped
            .putExtra("extra_position", pos)    // VLC
            .putExtra("from_start", false)
        sub?.let { s ->
            i.putExtra("subs", arrayOf<android.os.Parcelable>(Uri.parse(s)))
            i.putExtra("subs.enable", arrayOf<android.os.Parcelable>(Uri.parse(s)))
            i.putExtra("subtitles_location", s)
        }
        if (pkg != null) i.setPackage(pkg)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(if (pkg == null) Intent.createChooser(i, "Open with") else i, REQ)
        } catch (e: Exception) {
            Toast.makeText(this, "That player is not installed", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (data != null && key.isNotEmpty()) {
            // VLC: extra_position / extra_duration (ms, long). MX Player: position / duration (ms, int).
            var pos = data.getLongExtra("extra_position", -1L)
            var dur = data.getLongExtra("extra_duration", -1L)
            if (pos < 0) pos = data.getIntExtra("position", -1).toLong()
            if (dur <= 0) dur = data.getIntExtra("duration", -1).toLong()
            if (data.getStringExtra("end_by") == "playback_completion" && dur > 0) pos = dur
            if (dur > 0 && pos >= 0) History.save(applicationContext, key, title, pos.coerceAtMost(dur), dur)
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun ping(url: String) = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        try { c.responseCode } finally { c.disconnect() }
    }

    companion object {
        private const val REQ = 7
        private const val MAX_MS = 6 * 60 * 60 * 1000L
        private const val EXTRA_URL = "url"
        private const val EXTRA_BASE = "base"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POS = "pos"
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_SUB = "sub"

        fun intent(ctx: Context, url: String, base: String?, key: String, title: String, pos: Long, pkg: String?, sub: String?) =
            Intent(ctx, ExternalActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_BASE, base).putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_POS, pos).putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_SUB, sub)
    }
}
