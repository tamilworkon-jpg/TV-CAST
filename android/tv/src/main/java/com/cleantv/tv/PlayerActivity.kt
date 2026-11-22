package com.cleantv.tv

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), CastController {
    private lateinit var playerView: PlayerView
    private lateinit var image: ImageView
    private lateinit var caption: TextView
    private lateinit var audio: AudioManager
    private var player: ExoPlayer? = null

    private var mode = "idle"
    private var title = ""
    private var key = ""
    private var videoUrl = ""
    private var subUrl: String? = null
    private var urls: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var index = 0
    private var subsOn = true
    private var slideshow = false
    private var aspect = 0
    private var ticks = 0
    private var cachedPlayers: List<Pair<String, String>> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            publishStatus()
            if (++ticks % 10 == 0) saveProgress()
            handler.postDelayed(this, 500)
        }
    }
    private val slideStep = object : Runnable {
        override fun run() {
            if (!slideshow) return
            showImage(index + 1)
            handler.postDelayed(this, SLIDE_MS)
        }
    }
    private val hideCaption = Runnable { caption.visibility = View.GONE }
    private val closeIfEnded = Runnable { if (player?.playbackState == Player.STATE_ENDED) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.playerView)
        image = findViewById(R.id.image)
        caption = findViewById(R.id.caption)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        cachedPlayers = externalPlayers(this)
        addControllerButtons()
        Bus.controller = this
        handleRequest()
        handler.post(ticker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRequest()
    }

    private fun handleRequest() {
        val r = Bus.pending
        Bus.pending = null
        when (r) {
            is CastRequest.Video -> playVideo(r)
            is CastRequest.Images -> showImages(r)
            null -> if (mode == "idle") finish()
        }
    }

    // ---------------------------------------------------------------- video

    private fun playVideo(r: CastRequest.Video) {
        saveProgress()
        stopSlideshow()
        mode = "video"
        title = r.title
        key = r.key
        videoUrl = r.url
        subUrl = r.sub
        image.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        val p = player ?: createPlayer()
        val item = MediaItem.Builder()
            .setUri(r.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(r.title).build())
        if (r.sub != null) {
            val mime = if (r.sub.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(r.sub))
                        .setMimeType(mime)
                        .setLanguage("en")
                        .setLabel("External (.${r.sub.substringAfterLast('.')})")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverrides().build()
        p.setPlaybackSpeed(1f)
        p.setMediaItem(item.build(), r.startMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        val hint = "Press ☰ for audio, subtitles, screen size, volume, VLC/MX"
        showCaption(if (r.startMs > 0) "${r.title}\nResuming from ${fmt(r.startMs)}\n$hint" else "${r.title}\n$hint", 5000)
    }

    private fun createPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showCaption("Cannot play: ${error.errorCodeName}\nPress ☰ (menu) to open in VLC / MX Player", 15_000)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    saveProgress()
                    showCaption("Finished. Closing in 10 seconds…", 10_000)
                    handler.postDelayed(closeIfEnded, 10_000)
                }
            }
        })
        playerView.player = p
        playerView.resizeMode = ASPECTS[aspect].first
        player = p
        return p
    }

    private fun releasePlayer() {
        saveProgress()
        playerView.player = null
        player?.release()
        player = null
    }

    private fun saveProgress() {
        val p = player ?: return
        if (mode != "video" || key.isEmpty()) return
        val dur = p.duration
        if (dur <= 0) return
        val pos = if (p.playbackState == Player.STATE_ENDED) dur else p.currentPosition
        History.save(applicationContext, key, title, pos, dur)
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        if (dur > 0) target = target.coerceAtMost(dur - 1000)
        p.seekTo(target)
        showCaption("${fmt(target)} / ${fmt(dur.coerceAtLeast(0))}", 1500)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    private fun cycleAspect() = setAspect((aspect + 1) % ASPECTS.size)

    private fun setAspect(i: Int) {
        aspect = i.coerceIn(0, ASPECTS.size - 1)
        playerView.resizeMode = ASPECTS[aspect].first
        showCaption("Screen size: ${ASPECTS[aspect].second}", 1500)
    }

    private fun setVolume(level: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = level.coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        // Fire OS ignores setStreamVolume from apps; step towards the target instead.
        var guard = max + 2
        while (guard-- > 0) {
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur == target) break
            val dir = if (cur < target) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == cur) break
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    private fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        showCaption("Speed ${speed}x", 1500)
    }

    private fun toggleSubs() {
        val p = player ?: return
        subsOn = !subsOn
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn).build()
        showCaption(if (subsOn) "Subtitles on" else "Subtitles off", 1500)
    }

    /** id is "group:track" from [tracksJson], or "off" to disable subtitles. */
    private fun selectTrack(type: Int, id: String?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (id == null || id == "off") {
            if (type != C.TRACK_TYPE_TEXT) return
            subsOn = false
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            showCaption("Subtitles off", 1500)
        } else {
            val (g, t) = id.split(':').map { it.toInt() }
            val group = p.currentTracks.groups.getOrNull(g) ?: return
            b.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
            if (type == C.TRACK_TYPE_TEXT) subsOn = true
            showCaption(trackLabel(group.getTrackFormat(t), t + 1), 2000)
        }
        p.trackSelectionParameters = b.build()
    }

    private fun tracksJson(type: Int): JSONArray {
        val arr = JSONArray()
        val p = player ?: return arr
        p.currentTracks.groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (t in 0 until group.length) {
                var label = trackLabel(group.getTrackFormat(t), arr.length() + 1)
                if (!group.isTrackSupported(t)) label += " (not supported)"
                arr.put(JSONObject().put("id", "$g:$t").put("label", label).put("selected", group.isTrackSelected(t)))
            }
        }
        return arr
    }

    private fun trackLabel(f: Format, n: Int): String {
        val parts = mutableListOf<String>()
        f.label?.let { parts += it }
        f.language?.takeIf { it != "und" }?.let { parts += Locale(it).displayLanguage }
        if (f.channelCount > 0) parts += when (f.channelCount) {
            1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch"
        }
        f.sampleMimeType?.substringAfter('/')?.uppercase()?.let { parts += it }
        return "Track $n" + if (parts.isEmpty()) "" else ": " + parts.distinct().joinToString(" · ")
    }

    private fun volume(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    /** Hands the current video to another player app on the TV (VLC, MX Player…) at the same position. */
    private fun openExternal(pkg: String?) {
        if (mode != "video" || videoUrl.isEmpty()) return
        val pos = player?.currentPosition ?: 0L
        saveProgress()
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(videoUrl), "video/*")
            .putExtra("title", title)
            .putExtra("position", pos.toInt())  // MX Player
            .putExtra("extra_position", pos)    // VLC
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        subUrl?.let { s ->
            i.putExtra("subs", arrayOf<android.os.Parcelable>(Uri.parse(s)))
            i.putExtra("subtitles_location", s)
        }
        if (pkg != null) i.setPackage(pkg)
        try {
            startActivity(if (pkg == null) Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i)
            finish()
        } catch (e: Exception) {
            showCaption("That player is not installed", 3000)
        }
    }

    private fun showMenu() {
        val p = player ?: return
        val audioTracks = tracksJson(C.TRACK_TYPE_AUDIO)
        val subTracks = tracksJson(C.TRACK_TYPE_TEXT)
        fun selectedLabel(a: JSONArray): String? =
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optBoolean("selected") }?.optString("label")
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val players = externalPlayers(this)

        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries += "Audio track:  ${selectedLabel(audioTracks) ?: "—"}" to { pickTrack("Audio track", audioTracks, C.TRACK_TYPE_AUDIO, false) }
        entries += "Subtitles:  ${if (subsOn) selectedLabel(subTracks) ?: "On" else "Off"}" to { pickTrack("Subtitles", subTracks, C.TRACK_TYPE_TEXT, true) }
        entries += "Screen size:  ${ASPECTS[aspect].second}" to {
            choose("Screen size", ASPECTS.map { it.second }, aspect) { setAspect(it) }
        }
        entries += "Volume:  $vol / $volMax" to {
            val step = if (volMax > 30) 5 else 1
            val levels = (0..volMax step step).toList()
            choose("Volume", levels.map { if (it == 0) "Mute" else "$it" }, levels.indexOfFirst { it >= vol }) { setVolume(levels[it]) }
        }
        entries += "Speed:  ${p.playbackParameters.speed}x" to {
            choose("Speed", SPEEDS.map { "${it}x" }, SPEEDS.indexOf(p.playbackParameters.speed)) { setSpeed(SPEEDS[it]) }
        }
        entries += "Restart from beginning" to { p.seekTo(0); p.play() }
        players.forEach { (pkg, name) -> entries += "Play in $name" to { openExternal(pkg) } }
        entries += "Open with other app…" to { openExternal(null) }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    private fun choose(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> onPick(which); d.dismiss() }
            .show()
    }

    private fun pickTrack(title: String, tracks: JSONArray, type: Int, withOff: Boolean) {
        val ids = mutableListOf<String>()
        val labels = mutableListOf<String>()
        var checked = -1
        if (withOff) { ids += "off"; labels += "Off"; if (!subsOn) checked = 0 }
        for (i in 0 until tracks.length()) {
            val o = tracks.getJSONObject(i)
            if (o.optBoolean("selected") && checked == -1) checked = ids.size
            ids += o.getString("id"); labels += o.getString("label")
        }
        if (labels.size == (if (withOff) 1 else 0)) {
            showCaption("No ${title.lowercase()} in this video", 2000)
            return
        }
        choose(title, labels, checked) { selectTrack(type, ids[it]) }
    }

    /** Adds Screen-size and Options buttons to the player's bottom bar so they're reachable with the remote. */
    private fun addControllerButtons() {
        val bar = playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_basic_controls) ?: return
        fun button(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(
            this, null, 0, androidx.media3.ui.R.style.ExoStyledControls_Button_Bottom
        ).apply {
            setImageResource(icon)
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        bar.addView(button(R.drawable.ic_aspect, "Screen size") { cycleAspect() }, 0)
        bar.addView(button(R.drawable.ic_tune, "Options") { showMenu() }, 0)
    }

    // --------------------------------------------------------------- images

    private fun showImages(r: CastRequest.Images) {
        releasePlayer()
        mode = "images"
        urls = r.urls
        names = r.names
        playerView.visibility = View.GONE
        image.visibility = View.VISIBLE
        showImage(r.index)
    }

    private fun showImage(i: Int) {
        if (urls.isEmpty()) return
        index = ((i % urls.size) + urls.size) % urls.size
        image.load(urls[index], imageLoader(this)) { crossfade(300) }
        showCaption("${names.getOrElse(index) { "" }}   (${index + 1}/${urls.size})")
    }

    private fun toggleSlideshow() {
        slideshow = !slideshow
        handler.removeCallbacks(slideStep)
        if (slideshow) handler.postDelayed(slideStep, SLIDE_MS)
        showCaption(if (slideshow) "Slideshow on" else "Slideshow off")
    }

    private fun stopSlideshow() {
        slideshow = false
        handler.removeCallbacks(slideStep)
    }

    // ------------------------------------------------------- remote / status

    override fun onCommand(cmd: String, v: String?) {
        val ms = v?.toLongOrNull()
        when (cmd) {
            "toggle" -> if (mode == "images") toggleSlideshow() else togglePlay()
            "play" -> player?.play()
            "pause" -> player?.pause()
            "seek" -> if (ms != null) { player?.seekTo(ms); showCaption(fmt(ms), 1500) }
            "fwd" -> seekBy(ms ?: 10_000)
            "rew" -> seekBy(-(ms ?: 10_000))
            "subs" -> toggleSubs()
            "subtrack" -> selectTrack(C.TRACK_TYPE_TEXT, v)
            "audiotrack" -> selectTrack(C.TRACK_TYPE_AUDIO, v)
            "speed" -> v?.toFloatOrNull()?.let { setSpeed(it) }
            "aspect" -> if (v != null) setAspect(v.toIntOrNull() ?: 0) else cycleAspect()
            "setvol" -> v?.toIntOrNull()?.let { setVolume(it) }
            "volup" -> volume(AudioManager.ADJUST_RAISE)
            "voldown" -> volume(AudioManager.ADJUST_LOWER)
            "mute" -> if (Build.VERSION.SDK_INT >= 23) volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "external" -> openExternal(v?.takeIf { it.isNotEmpty() })
            "next" -> showImage(index + 1)
            "prev" -> showImage(index - 1)
            "slideshow" -> toggleSlideshow()
            "stop" -> finish()
        }
        publishStatus()
    }

    private fun publishStatus() {
        val j = JSONObject().put("mode", mode)
        when (mode) {
            "video" -> {
                val p = player
                val err = p?.playerError?.errorCodeName
                j.put("title", title)
                    .put("key", key)
                    .put("error", err ?: "")
                    .put("playing", p != null && err == null && p.playWhenReady && p.playbackState != Player.STATE_ENDED)
                    .put("buffering", p != null && p.playbackState == Player.STATE_BUFFERING)
                    .put("ended", p != null && p.playbackState == Player.STATE_ENDED)
                    .put("pos", p?.currentPosition ?: 0L)
                    .put("dur", p?.duration?.takeIf { it > 0 } ?: 0L)
                    .put("speed", p?.playbackParameters?.speed?.toDouble() ?: 1.0)
                    .put("aspect", ASPECTS[aspect].second)
                    .put("aspects", JSONArray(ASPECTS.map { it.second }))
                    .put("vol", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .put("volMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    .put("subs", subsOn)
                    .put("audioTracks", tracksJson(C.TRACK_TYPE_AUDIO))
                    .put("subTracks", tracksJson(C.TRACK_TYPE_TEXT))
                    .put("players", JSONArray().apply {
                        cachedPlayers.forEach { put(JSONObject().put("pkg", it.first).put("name", it.second)) }
                    })
            }
            "images" -> j.put("title", names.getOrElse(index) { "" })
                .put("index", index)
                .put("count", urls.size)
                .put("slideshow", slideshow)
        }
        Bus.status = j.toString()
    }

    private fun showCaption(text: String, ms: Long = 3000) {
        caption.text = text
        caption.visibility = View.VISIBLE
        handler.removeCallbacks(hideCaption)
        handler.postDelayed(hideCaption, ms)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (mode == "images") {
            val handled = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (down) { stopSlideshow(); showImage(index - 1) }; true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (down) { stopSlideshow(); showImage(index + 1) }; true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (down) toggleSlideshow(); true
                }
                else -> false
            }
            return handled || super.dispatchKeyEvent(event)
        }
        if (mode == "video") {
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (down) showMenu()
                return true
            }
            // With the control bar hidden, left/right on the remote seek 10s directly.
            if (!playerView.isControllerFullyVisible &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                if (down) seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000)
                return true
            }
            return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Home pressed or another app opened: stop playback instead of playing hidden.
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        if (Bus.controller === this) {
            Bus.controller = null
            Bus.status = Bus.IDLE
        }
        super.onDestroy()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val SLIDE_MS = 5000L
        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        private val ASPECTS = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom (fill, crop edges)",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "Fit width",
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "Fit height",
        )
    }
}
