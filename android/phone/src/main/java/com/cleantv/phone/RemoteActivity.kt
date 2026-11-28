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
    private lateinit var viewPanel: View
    private lateinit var panRow: View
    private lateinit var viewState: TextView
    private var volDragging = false
    private lateinit var volSeek: SeekBar
    private var failures = 0
    private var reconnecting = false
    private var last: JSONObject? = null
    private lateinit var queueRow: View
    private lateinit var btnPicMode: Button
    private lateinit var btnSoundMode: Button

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
        viewPanel = findViewById(R.id.viewPanel)
        panRow = findViewById(R.id.panRow)
        viewState = findViewById(R.id.viewState)
        findViewById<Button>(R.id.btnRotL).setOnClickListener { send("rotate", "-90") }
        findViewById<Button>(R.id.btnRotR).setOnClickListener { send("rotate", "90") }
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { send("zoom", "in") }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { send("zoom", "out") }
        findViewById<Button>(R.id.btnPanL).setOnClickListener { send("pan", "left") }
        findViewById<Button>(R.id.btnPanR).setOnClickListener { send("pan", "right") }
        findViewById<Button>(R.id.btnPanU).setOnClickListener { send("pan", "up") }
        findViewById<Button>(R.id.btnPanD).setOnClickListener { send("pan", "down") }
        findViewById<Button>(R.id.btnResetView).setOnClickListener { send("resetview") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { send("stop"); finish() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            ServerService.disconnect(this)
            Toast.makeText(this, "Disconnected. Sharing stopped.", Toast.LENGTH_SHORT).show()
            finish()
        }
        queueRow = findViewById(R.id.queueRow)
        findViewById<Button>(R.id.btnPrevVideo).setOnClickListener { if (isRadio()) stepStation(-1) else send("prev") }
        findViewById<Button>(R.id.btnNextVideo).setOnClickListener { if (isRadio()) stepStation(1) else send("next") }
        btnPicMode = findViewById(R.id.btnPicMode)
        btnSoundMode = findViewById(R.id.btnSoundMode)
        btnPicMode.setOnClickListener { pickMode("Picture mode", "picModes", "picMode", "picmode") { customPicture() } }
        btnSoundMode.setOnClickListener { pickMode("Sound mode", "soundModes", "soundMode", "soundmode") { customSound() } }

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

    override fun onStart() {
        super.onStart()
        ServerService.uiVisible++
    }

    override fun onStop() {
        ServerService.uiVisible = (ServerService.uiVisible - 1).coerceAtLeast(0)
        super.onStop()
    }

    /** Radio list kept on the phone (stations picked on the TV are stepped through by the TV itself). */
    private fun isRadio() = last?.optBoolean("radio") == true && (last?.optInt("qCount") ?: 0) <= 1 && RadioQueue.stations.size > 1

    /** Next/previous station from the list the station was picked from. */
    private fun stepStation(delta: Int) {
        val s = RadioQueue.step(delta) ?: return
        Toast.makeText(this, s.name, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { TvClient.radio(tv, s.url, s.name, s.logo, Prefs.vpn(this@RemoteActivity)) }
            poll()
        }
    }

    private fun send(cmd: String, v: String? = null) {
        // If the TV is showing this phone's media and sharing was stopped (e.g. phone killed it), restart it.
        val s = last
        if (s != null && !s.optBoolean("online") && s.optString("mode") in listOf("video", "images") && !ServerService.running) {
            ServerService.start(this)
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { TvClient.control(tv, cmd, v) }
            poll()
        }
    }

    /** Preset picker for picture/sound modes; "Custom" opens sliders. */
    private fun pickMode(title: String, listField: String, curField: String, cmd: String, custom: () -> Unit) {
        val a = last?.optJSONArray(listField) ?: return
        val modes = List(a.length()) { a.getString(it) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(modes.toTypedArray(), modes.indexOf(last?.optString(curField))) { d, which ->
                d.dismiss()
                if (modes[which] == "Custom") custom() else send(cmd, modes[which])
            }
            .show()
    }

    private fun customPicture() {
        val c = last?.optJSONArray("picCustom")
        val vals = IntArray(4) { c?.optInt(it) ?: listOf(0, 100, 100, 0)[it] }
        sliders(
            "Custom picture",
            listOf(Slider("Brightness", -50, 50, vals[0]), Slider("Contrast %", 50, 150, vals[1]),
                Slider("Colour %", 0, 200, vals[2]), Slider("Warmth", -50, 50, vals[3]))
        ) { v -> send("piccustom", v.joinToString(",")) }
    }

    private fun customSound() {
        val c = last?.optJSONArray("soundCustom")
        val names = listOf("Bass", "Low mid", "Mid (voices)", "High mid", "Treble")
        val rows = names.mapIndexed { i, n -> Slider("$n (dB)", -12, 12, c?.optInt(i) ?: 0) } +
            Slider("Loudness boost (dB)", 0, 12, c?.optInt(5) ?: 0)
        sliders("Custom sound", rows) { v -> send("soundcustom", v.joinToString(",")) }
    }

    private data class Slider(val label: String, val min: Int, val max: Int, val value: Int)

    private fun sliders(title: String, rows: List<Slider>, onChange: (List<Int>) -> Unit) {
        val d = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val bars = rows.map { r ->
            val text = TextView(this)
            val bar = SeekBar(this).apply { max = r.max - r.min; progress = r.value - r.min }
            text.text = "${r.label}: ${r.value}"
            box.addView(text); box.addView(bar)
            Triple(r, text, bar)
        }
        fun values() = bars.map { (r, _, b) -> b.progress + r.min }
        bars.forEach { (r, text, bar) ->
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { text.text = "${r.label}: ${p + r.min}" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { onChange(values()) }
            })
        }
        AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Done", null).show()
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
        // Keep the phone's copy of the resume position current while watching.
        if (s.optString("mode") == "video" && s.optString("key").isNotEmpty() && s.optLong("dur") > 0) {
            Prefs.mergeProgress(this, s.optString("key"), s.optString("title"), s.optLong("pos"), s.optLong("dur"))
        }
        render(s)
    }

    private fun render(s: JSONObject) {
        last = s
        val mode = s.optString("mode")
        viewPanel.visibility = if (mode == "video" || mode == "images") View.VISIBLE else View.GONE
        val zoom = s.optDouble("zoom", 1.0)
        // Moving around only makes sense for a zoomed picture.
        panRow.visibility = if (mode == "images" && zoom > 1.0) View.VISIBLE else View.GONE
        viewState.text = "Rotate & zoom  ·  ${s.optInt("rotation")}°  ·  " +
            if (zoom <= 1.0) "no zoom" else "zoom ${"%.2f".format(zoom).trimEnd('0').trimEnd('.')}×"
        when (s.optString("mode")) {
            "video" -> {
                videoPanel.visibility = View.VISIBLE
                imagePanel.visibility = View.GONE
                title.text = s.optString("title")
                val playing = s.optBoolean("playing")
                val online = s.optBoolean("online")
                val q = s.optInt("qCount")
                state.text = when {
                    s.optBoolean("reconnecting") -> "Connection lost — TV is reconnecting and will continue from the same spot…"
                    s.optString("error").isNotEmpty() && online -> "Can't play this link (${s.optString("error")})."
                    s.optString("error").isNotEmpty() -> "Cannot play (${s.optString("error")}). Try \"Play in another TV player\"."
                    s.optBoolean("ended") -> "Finished"
                    s.optBoolean("buffering") -> "Buffering…"
                    playing -> "Playing"
                    else -> "Paused"
                } + when {
                    s.optBoolean("radio") -> "  ·  Live radio"
                    online -> "  ·  Private link · ads blocked · " +
                        (if (s.optBoolean("vpn")) "VPN on ${s.optString("vpnName")}".trim() else "VPN off")
                    q > 1 -> "  ·  ${s.optInt("qIndex") + 1} of $q"
                    else -> ""
                }
                val radioList = s.optBoolean("radio") && (q > 1 || RadioQueue.stations.size > 1)
                queueRow.visibility = if (q > 1 || radioList) View.VISIBLE else View.GONE
                findViewById<Button>(R.id.btnPrevVideo).text = if (radioList) "⏮ Previous station" else "⏮ Previous"
                findViewById<Button>(R.id.btnNextVideo).text = if (radioList) "Next station ⏭" else "Next ⏭"
                btnPicMode.text = "Picture: ${s.optString("picMode", "Standard")}"
                btnSoundMode.text = "Sound: ${s.optString("soundMode", "Standard")}"
                findViewById<View>(R.id.btnExternal).visibility = if (online) View.GONE else View.VISIBLE
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
