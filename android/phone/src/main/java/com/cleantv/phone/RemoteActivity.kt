package com.cleantv.phone

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RemoteActivity : AppCompatActivity() {
    private lateinit var tv: Tv
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var time: TextView
    private lateinit var seek: SeekBar
    private lateinit var videoPanel: View
    private lateinit var imagePanel: View
    private lateinit var btnPlay: com.google.android.material.button.MaterialButton
    private lateinit var btnSubs: Button
    private lateinit var btnAudio: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnAspect: Button
    private lateinit var btnSlide: Button
    private var dragging = false
    private var volDragging = false
    private lateinit var volSeek: SeekBar
    private var failures = 0
    private var reconnecting = false
    private var last: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = Prefs.lastTv(this) ?: run { finish(); return }
        setContentView(R.layout.activity_remote)
        title = findViewById(R.id.title)
        state = findViewById(R.id.state)
        time = findViewById(R.id.time)
        seek = findViewById(R.id.seek)
        videoPanel = findViewById(R.id.videoPanel)
        imagePanel = findViewById(R.id.imagePanel)
        btnPlay = findViewById(R.id.btnPlay)
        btnSubs = findViewById(R.id.btnSubs)
        btnAudio = findViewById(R.id.btnAudio)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnAspect = findViewById(R.id.btnAspect)
        btnSlide = findViewById(R.id.btnSlide)
        findViewById<TextView>(R.id.tvName).text = tv.name

        btnPlay.setOnClickListener { send("toggle") }
        findViewById<Button>(R.id.btnRew).setOnClickListener { send("rew") }
        findViewById<Button>(R.id.btnFwd).setOnClickListener { send("fwd") }
        findViewById<Button>(R.id.btnRew60).setOnClickListener { send("rew", "60000") }
        findViewById<Button>(R.id.btnFwd60).setOnClickListener { send("fwd", "60000") }
        btnSubs.setOnClickListener { pickTrack("Subtitles", "subTracks", "subtrack", withOff = true) }
        btnAudio.setOnClickListener { pickTrack("Audio track", "audioTracks", "audiotrack", withOff = false) }
        btnSpeed.setOnClickListener { pickSpeed() }
        btnAspect.setOnClickListener { pickAspect() }
        findViewById<Button>(R.id.btnExternal).setOnClickListener { pickExternal() }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { send("voldown") }
        findViewById<Button>(R.id.btnVolUp).setOnClickListener { send("volup") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { send("mute") }
        volSeek = findViewById(R.id.volSeek)
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) { volDragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                volDragging = false
                send("setvol", s.progress.toString())
            }
        })
        findViewById<Button>(R.id.btnPrev).setOnClickListener { send("prev") }
        findViewById<Button>(R.id.btnNext).setOnClickListener { send("next") }
        btnSlide.setOnClickListener { send("slideshow") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { send("stop"); finish() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) time.text = "${formatTime(progress * 1000L)} / ${formatTime(s.max * 1000L)}"
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                send("seek", (s.progress * 1000L).toString())
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    poll()
                    delay(500)
                }
            }
        }
    }

    private fun send(cmd: String, v: String? = null) {
        // Keep the phone's media server up while the remote is in use (it may have idled out).
        ServerService.start(this)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { TvClient.control(tv, cmd, v) }
            poll()
        }
    }

    private fun pickTrack(title: String, field: String, cmd: String, withOff: Boolean) {
        val arr = last?.optJSONArray(field)
        val ids = mutableListOf<String>()
        val labels = mutableListOf<String>()
        var checked = -1
        if (withOff) {
            ids += "off"; labels += "Off"
            if (last?.optBoolean("subs", true) == false) checked = 0
        }
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr!!.getJSONObject(i)
            if (o.optBoolean("selected") && checked == -1) checked = ids.size
            ids += o.getString("id"); labels += o.getString("label")
        }
        if (labels.size <= (if (withOff) 1 else 0)) {
            Toast.makeText(this, "No ${title.lowercase()} in this video", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> send(cmd, ids[which]); d.dismiss() }
            .show()
    }

    private fun pickExternal() {
        val arr = last?.optJSONArray("players")
        val pkgs = mutableListOf<String>()
        val labels = mutableListOf<String>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr!!.getJSONObject(i)
            pkgs += o.getString("pkg"); labels += o.getString("name")
        }
        pkgs += ""; labels += "Ask on TV…"
        AlertDialog.Builder(this)
            .setTitle("Continue on TV with")
            .setItems(labels.toTypedArray()) { _, which -> send("external", pkgs[which]); finish() }
            .show()
    }

    private fun pickAspect() {
        val arr = last?.optJSONArray("aspects")
        val labels = List(arr?.length() ?: 0) { arr!!.getString(it) }
        if (labels.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Screen size")
            .setSingleChoiceItems(labels.toTypedArray(), labels.indexOf(last?.optString("aspect"))) { d, which ->
                send("aspect", which.toString()); d.dismiss()
            }
            .show()
    }

    private fun pickSpeed() {
        val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        val current = last?.optDouble("speed", 1.0) ?: 1.0
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(speeds.map { "${it}x" }.toTypedArray(), speeds.indexOf(current)) { d, which ->
                send("speed", speeds[which].toString()); d.dismiss()
            }
            .show()
    }

    private suspend fun poll() {
        val s = withContext(Dispatchers.IO) { TvClient.status(tv) }
        if (s == null) {
            failures++
            state.text = if (failures < 3) "Connecting…" else "Reconnecting to ${tv.name}…"
            if (failures == 3 && !reconnecting) {
                reconnecting = true
                lifecycleScope.launch {
                    TvClient.ensure(this@RemoteActivity)?.let { tv = it }
                    reconnecting = false
                }
            }
            if (failures > 20) failures = 2
            return
        }
        failures = 0
        render(s)
    }

    private fun render(s: JSONObject) {
        last = s
        when (s.optString("mode")) {
            "video" -> {
                videoPanel.visibility = View.VISIBLE
                imagePanel.visibility = View.GONE
                title.text = s.optString("title")
                val playing = s.optBoolean("playing")
                state.text = when {
                    s.optString("error").isNotEmpty() -> "Cannot play (${s.optString("error")}). Try \"Play in another TV player\"."
                    s.optBoolean("ended") -> "Finished"
                    s.optBoolean("buffering") -> "Buffering…"
                    playing -> "Playing"
                    else -> "Paused"
                }
                btnPlay.setIconResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                btnSubs.text = if (s.optBoolean("subs", true)) "Subtitles: On" else "Subtitles: Off"
                btnAudio.text = "Audio (${s.optJSONArray("audioTracks")?.length() ?: 0})"
                btnSpeed.text = "Speed ${s.optDouble("speed", 1.0)}x"
                btnAspect.text = "Screen: ${s.optString("aspect", "Fit")}"
                if (!volDragging) {
                    volSeek.max = s.optInt("volMax", 15)
                    volSeek.progress = s.optInt("vol")
                }
                val dur = s.optLong("dur")
                val pos = s.optLong("pos")
                seek.max = (dur / 1000).toInt().coerceAtLeast(1)
                if (!dragging) {
                    seek.progress = (pos / 1000).toInt()
                    time.text = "${formatTime(pos)} / ${formatTime(dur)}"
                }
            }
            "images" -> {
                videoPanel.visibility = View.GONE
                imagePanel.visibility = View.VISIBLE
                title.text = s.optString("title")
                state.text = "Picture ${s.optInt("index") + 1} of ${s.optInt("count")}"
                btnSlide.text = if (s.optBoolean("slideshow")) "Stop slideshow" else "Slideshow"
            }
            else -> {
                videoPanel.visibility = View.GONE
                imagePanel.visibility = View.GONE
                title.text = ""
                state.text = "Nothing playing on the TV"
            }
        }
    }
}
