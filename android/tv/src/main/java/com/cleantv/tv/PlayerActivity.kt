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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
    private var reconnecting = false
    /** The Android VPN permission prompt is open over this screen. */
    private var awaitingVpn = false
    private var queue: MutableList<VideoItem> = mutableListOf()
    private var rotation = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var reconnectStartedAt = 0L
    private var resumeAt = 0L
    /** Dolby/DTS audio is sent untouched to the TV/soundbar. Off while playing faster/slower (see [setSpeed]). */
    private var audioPassthrough = true
    /** After switching to decoded audio for a speed change: check the audio could actually be decoded. */
    private var checkDecodedAudio = false
    /** Where repeated ⏩/⏪ presses are heading; the real seek happens once the presses stop. */
    private var seekTarget = -1L
    /** Duration of the current queue entry, kept so the entry can be marked watched after it moves on. */
    private var lastDur = 0L
    /** Stations in a row that failed to play; stops trying once every station has been tried. */
    private var radioFails = 0
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            publishStatus()
            if (++ticks % 4 == 0) saveProgress()
            if (ticks % 2 == 0) PairDialog.showIfPending(this@PlayerActivity)
            handler.postDelayed(this, 500)
        }
    }
    private val slideStep = object : Runnable {
        override fun run() {
            // While an album video plays, the slideshow waits; it resumes when the video ends.
            if (!slideshow || slideVideo) return
            showImage(index + 1)
            handler.postDelayed(this, SLIDE_MS)
        }
    }

    /** Google Photos album: each item is checked for a video when it comes up. */
    private var albumVideos = false
    /** An album video is playing in place of the current slide. */
    private var slideVideo = false
    /** Ignores video checks that finish after the slideshow has moved on. */
    private var videoCheck = 0
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
        // Video size / layout changes resize the surface, so the rotation fit must be recomputed.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
        (playerView.videoSurfaceView as? View)?.let { surface ->
            surface.addOnLayoutChangeListener(relayout)
            // The content frame is sized to the video's shape; let a rotated/zoomed video draw outside it.
            var p = surface.parent as? ViewGroup
            while (p != null) {
                p.clipChildren = false
                p.clipToPadding = false
                if (p === playerView) break
                p = p.parent as? ViewGroup
            }
        }
        image.addOnLayoutChangeListener(relayout)
        applyLook()
        Bus.controller = this
        handleRequest()
        handler.post(ticker)
        handler.postDelayed(heartbeat, 30_000)
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
            is CastRequest.VpnConsent -> askVpn()
            null -> if (mode == "idle") finish()
        }
    }

    /** Shows Android's one-time VPN prompt (only allowed from a visible screen), then retries the link. */
    private fun askVpn() {
        val consent = android.net.VpnService.prepare(this)
        if (consent == null) { vpnAnswered(true); return }
        awaitingVpn = true
        showCaption("Allow the TV Cast VPN (one time) with the remote → OK", 20_000)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQ_VPN)
        } catch (e: Exception) {
            awaitingVpn = false
            vpnAnswered(false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VPN) return
        awaitingVpn = false
        vpnAnswered(resultCode == RESULT_OK)
    }

    private fun vpnAnswered(allowed: Boolean) {
        val retry = Bus.vpnRetry
        Bus.vpnRetry = null
        if (allowed && retry != null) {
            showCaption("Connecting VPN…", 10_000)
            Thread { retry(true) }.start()
            // Opened only for the prompt (e.g. VPN started from the phone): close once nothing else is shown.
            if (mode == "idle") handler.postDelayed({ if (mode == "idle") finish() }, 6000)
        } else if (!allowed) {
            showCaption("VPN not allowed, so the link wasn't played.\nUntick \"Use built-in VPN\" on the phone to play without it.", 10_000)
            if (mode == "idle") handler.postDelayed({ if (mode == "idle") finish() }, 10_000)
        }
    }

    // ---------------------------------------------------------------- video

    private fun playVideo(r: CastRequest.Video) {
        saveProgress()
        stopSlideshow()
        mode = "video"
        queue = r.items.toMutableList()
        image.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        // A new video starts at normal speed, so its surround sound goes straight to the TV/soundbar again.
        if (!audioPassthrough) { releasePlayer(); audioPassthrough = true }
        handler.removeCallbacks(doSeek)
        seekTarget = -1L
        val p = player ?: createPlayer()
        resetView()
        reconnecting = false
        handler.removeCallbacks(reconnectStep)
        handler.removeCallbacks(closeIfEnded)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverrides().build()
        p.setPlaybackSpeed(1f)
        p.setMediaItems(queue.map { buildItem(it) }, r.index, r.startMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        syncCurrent()
        val c = current ?: return
        val hint = when {
            c.radio -> "Live radio" + (if (r.items.size > 1) " · ◀ ▶ or ⏮ ⏭ for other stations" else "") + " · ☰ for sound modes"
            c.online -> "Private mode: nothing about this link is saved · ☰ for options"
            else -> "☰ for audio, subtitles, picture & sound modes, rotate, zoom, VLC/MX"
        }
        val next = if (queue.size > 1 && !c.radio) "\n${r.index + 1} of ${queue.size} · ⏭ for next video" else ""
        showCaption((if (r.startMs > 0) "${c.title}\nResuming from ${fmt(r.startMs)}" else c.title) + next + "\n$hint", 5000)
    }

    private fun buildItem(v: VideoItem): MediaItem {
        val item = MediaItem.Builder()
            .setUri(v.url)
            .setMediaId(v.key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
        val path = Uri.parse(v.url).path.orEmpty().lowercase()
        if (path.endsWith(".m3u8") || v.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (path.endsWith(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD)
        v.sub?.let { sub ->
            val mime = if (sub.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                        .setMimeType(mime)
                        .setLanguage("en")
                        .setLabel("External (.${sub.substringAfterLast('.')})")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return item.build()
    }

    /** Current queue entry. */
    private val current: VideoItem? get() = queue.getOrNull(player?.currentMediaItemIndex ?: 0)

    /** Mirrors the current queue entry into title/key/videoUrl/subUrl. */
    private fun syncCurrent() {
        val c = current ?: return
        title = c.title
        key = if (c.online) "" else c.key // private mode: online links never reach History
        lastDur = 0L
        videoUrl = c.url
        subUrl = c.sub
        radioView(c)
    }

    /** Live radio: show the station logo instead of an empty video area. */
    private fun radioView(c: VideoItem) {
        if (!c.radio) {
            image.visibility = View.GONE
            playerView.controllerShowTimeoutMs = 5000
            return
        }
        image.visibility = View.VISIBLE
        image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (c.artwork != null) image.load(c.artwork, imageLoader(this)) { error(R.drawable.ic_launcher) }
        else image.setImageResource(R.drawable.ic_launcher)
        // Bar hides after a while so ◀ ▶ switch stations; any other key brings it back.
        playerView.controllerShowTimeoutMs = 6000
        playerView.showController()
    }

    private fun skip(dir: Int) {
        val p = player ?: return
        if (queue.size < 2) { showCaption(if (current?.radio == true) "No other stations in this list" else "No other videos in this list", 1500); return }
        if (current?.radio == true) {
            // Stations wrap around, like a radio dial.
            val n = queue.size
            p.seekTo(((p.currentMediaItemIndex + dir) % n + n) % n, C.TIME_UNSET)
            p.prepare()
            p.play()
            return
        }
        saveProgress()
        if (dir > 0) {
            if (p.hasNextMediaItem()) p.seekToNextMediaItem() else showCaption("This is the last video", 1500)
        } else {
            if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0)
        }
    }

    /** "http://ip:8090/<token>" part of the current phone media URL; used for keep-alive and reconnect. */
    private fun currentBase(): String? {
        if (mode == "video" && current?.online == true) return null
        val url = when (mode) {
            "video" -> videoUrl
            "images" -> urls.getOrNull(index)
            else -> null
        } ?: return null
        val cut = maxOf(url.lastIndexOf("/v/"), url.lastIndexOf("/i/"))
        return if (cut > 0) url.substring(0, cut) else null
    }

    private fun httpOk(url: String): Boolean = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        try { c.responseCode in 200..299 } finally { c.disconnect() }
    } catch (e: Exception) {
        false
    }

    /**
     * Tells the phone every 30 s that the TV is still using it, so the phone's
     * idle auto-stop never fires while a video is paused or pictures are on screen.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            currentBase()?.let { b -> io.execute { httpOk("$b/alive") } }
            handler.postDelayed(this, 30_000)
        }
    }

    /** Tells the phone the TV no longer needs it, so it can stop sharing right away. */
    private fun sayBye() {
        val b = currentBase() ?: return
        Thread { httpOk("$b/bye") }.start()
    }

    private fun latestPhoneBase(): String? =
        getSharedPreferences("phone", MODE_PRIVATE).getString("base", null)

    /** Network dropped or phone app was closed: keep trying (same position) until the phone is back. */
    private fun startReconnect() {
        if (reconnecting) return
        reconnecting = true
        reconnectStartedAt = System.currentTimeMillis()
        resumeAt = player?.currentPosition ?: resumeAt
        saveProgress()
        handler.removeCallbacks(reconnectStep)
        handler.postDelayed(reconnectStep, 2000)
    }

    private val reconnectStep: Runnable = object : Runnable {
        override fun run() {
            if (!reconnecting || mode != "video") return
            val online = current?.online == true
            val oldBase = currentBase()
            val candidates = if (online) emptyList() else listOfNotNull(latestPhoneBase(), oldBase).distinct()
            io.execute {
                val good = if (online) "online" else candidates.firstOrNull { httpOk("$it/alive") }
                runOnUiThread {
                    if (!reconnecting || isFinishing) return@runOnUiThread
                    val p = player ?: return@runOnUiThread
                    if (good != null) {
                        val idx = p.currentMediaItemIndex
                        if (!online && oldBase != null && good != oldBase) {
                            // Phone got a new IP address: switch every link in the list, keep the position.
                            queue = queue.map { v ->
                                v.copy(url = v.url.replace(oldBase, good), sub = v.sub?.replace(oldBase, good))
                            }.toMutableList()
                            p.setMediaItems(queue.map { buildItem(it) }, idx, resumeAt)
                            syncCurrent()
                        } else {
                            p.seekTo(idx, resumeAt)
                        }
                        p.prepare()
                        p.playWhenReady = true
                        showCaption("Reconnected", 2000)
                        // reconnecting is cleared once the player is READY again
                    } else {
                        val waited = (System.currentTimeMillis() - reconnectStartedAt) / 1000
                        if (waited > RECONNECT_GIVE_UP_S) {
                            reconnecting = false
                            showCaption("Phone not reachable. Open TV Cast on the phone and press Play again.", 60_000)
                            return@runOnUiThread
                        }
                        showCaption(
                            "Connection to phone lost — reconnecting…\n" +
                                "If the phone was locked for long, open TV Cast on it. Playback will continue from ${fmt(resumeAt)}.",
                            6000
                        )
                        handler.postDelayed(this, 4000)
                    }
                }
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val passthrough = audioPassthrough
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
                if (passthrough) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                // Decode Dolby/DTS on the TV instead of passing it through: passthrough audio can't change speed.
                @Suppress("DEPRECATION")
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setEnableDecoderFallback(true)
        // Ad/tracker requests are refused at the network layer; short Wi-Fi blips are retried silently.
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(AdBlock.dataSourceFactory(this))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(10))
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            // Default buffering can take ~130 MB, too much for this 1 GB TV: cap it at 40 MB / 40 s.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 40_000, 2_500, 5_000)
                    .setTargetBufferBytes(40 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
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
                if (mode == "video" && current?.radio == true && queue.size > 1 && ++radioFails < queue.size) {
                    // Dead station: move on to the next one after a moment.
                    showCaption("${current?.title ?: "Station"} isn't on air right now, trying the next station…", 4000)
                    handler.postDelayed({ if (player?.playerError != null && current?.radio == true) skip(1) }, 2500)
                    return
                }
                if (mode == "images") {
                    // Album video that can't be played: carry on with the slideshow.
                    showCaption("Couldn't play this video, skipping", 3000)
                    endSlideVideo(advance = true)
                    return
                }
                val online = current?.online == true
                // 2xxx = network/IO errors: the file is fine, the connection isn't. Reconnect automatically.
                if (error.errorCode in 2000..2999 && mode == "video" && !online) {
                    startReconnect()
                } else if (online) {
                    showCaption("Can't play this link (${error.errorCodeName}).\nIt may need a website login, or it isn't a direct video/stream link.", 15_000)
                } else {
                    showCaption("Cannot play: ${error.errorCodeName}\nPress ☰ (menu) to open in VLC / MX Player", 15_000)
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
                // Played to the end and moved on by itself: remember the finished one as watched.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && key.isNotEmpty() && lastDur > 0) {
                    History.save(applicationContext, key, title, lastDur, lastDur)
                }
                handler.removeCallbacks(doSeek)
                seekTarget = -1L
                syncCurrent()
                resetView()
                val c = current ?: return
                val p = player ?: return
                if (c.radio) { showCaption("${c.title}  (${p.currentMediaItemIndex + 1} of ${queue.size})", 3000); return }
                // Next video in the list: continue where it was left, if it was half watched.
                val h = if (c.online) null else History.find(this@PlayerActivity, c.key)
                val pos = h?.optLong("pos") ?: 0L
                val dur = h?.optLong("dur") ?: 0L
                val n = "(${p.currentMediaItemIndex + 1} of ${queue.size})"
                if (pos > 10_000 && dur > 0 && pos < dur - 30_000) {
                    p.seekTo(pos)
                    showCaption("${c.title}\nResumed from ${fmt(pos)}  $n", 4000)
                } else {
                    showCaption("${c.title}  $n", 3000)
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!checkDecodedAudio || tracks.groups.isEmpty()) return
                checkDecodedAudio = false
                val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                if (hasAudio && !tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                    // The TV can't decode this audio itself, so it can only play it at normal speed.
                    handler.post { rebuildPlayer(passthrough = true, speed = 1f) }
                    showCaption("This audio (Dolby/DTS) can only play at 1x on this TV.\nPick another audio track in ☰, or ☰ → Play in VLC to change speed.", 8000)
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Sound.attach(this@PlayerActivity, audioSessionId)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) radioFails = 0
                if (state == Player.STATE_READY && reconnecting) {
                    reconnecting = false
                    handler.removeCallbacks(reconnectStep)
                }
                if (state == Player.STATE_ENDED && mode == "images") {
                    endSlideVideo(advance = true)
                    return
                }
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
        Sound.release()
        playerView.player = null
        player?.release()
        player = null
    }

    private fun saveProgress() {
        val p = player ?: return
        if (mode != "video" || key.isEmpty()) return
        // Ignore a player that's between items or not ready yet (would save a wrong length).
        if (p.currentMediaItem?.mediaId != key) return
        val dur = p.duration
        if (dur <= 0) return
        lastDur = dur
        val pos = when {
            p.playbackState == Player.STATE_ENDED -> dur
            seekTarget >= 0 -> seekTarget
            else -> p.currentPosition
        }
        History.save(applicationContext, key, title, pos, dur)
    }

    /**
     * ⏩/⏪ and ◀/▶: presses add up (hold the button to go faster) and the player seeks once they stop,
     * to the nearest key frame. Big MKV files would otherwise restart loading on every single press.
     */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (current?.radio == true) { skip(if (deltaMs > 0) 1 else -1); return }
        val dur = p.duration
        if (!p.isCurrentMediaItemSeekable) {
            when {
                p.isCurrentMediaItemLive -> showCaption("This is a live stream, it can't be skipped through", 3000)
                p.playbackState != Player.STATE_READY && dur <= 0 -> showCaption("Still loading…", 1500)
                else -> showCaption("This file has no seek index, so it can't be skipped through here.\n" +
                    "Press ☰ → Play in VLC / MX Player to skip.", 5000)
            }
            return
        }
        val from = if (seekTarget >= 0) seekTarget else p.currentPosition
        var target = (from + deltaMs).coerceAtLeast(0)
        if (dur > 0) target = target.coerceAtMost(dur - 1000)
        seekTarget = target
        // Forward never lands before the target, backward never after it, so every press moves.
        val slack = 8_000_000L
        p.setSeekParameters(if (deltaMs > 0) SeekParameters(0, slack) else SeekParameters(slack, 0))
        val diff = target - p.currentPosition
        val sign = if (diff >= 0) "+" else "−"
        showCaption("${fmt(target)} / ${fmt(dur.coerceAtLeast(0))}    ($sign${fmt(kotlin.math.abs(diff))})", 1800)
        handler.removeCallbacks(doSeek)
        handler.postDelayed(doSeek, SEEK_DELAY_MS)
    }

    private val doSeek = Runnable {
        val t = seekTarget
        seekTarget = -1L
        val p = player ?: return@Runnable
        if (t >= 0) {
            p.seekTo(t)
            p.setSeekParameters(SeekParameters.DEFAULT) // resume / phone seeks stay exact
            saveProgress()
        }
    }

    /** Seek step for a held-down button: 10 s, then 30 s, then 1 min, then 2 min. */
    private fun seekStep(repeat: Int): Long = when {
        repeat < 6 -> 10_000L
        repeat < 16 -> 30_000L
        repeat < 30 -> 60_000L
        else -> 120_000L
    }

    /** Selected audio is Dolby/DTS going straight to the TV/soundbar, where speed changes are ignored. */
    private fun audioIsPassthrough(): Boolean {
        if (!audioPassthrough) return false
        val p = player ?: return false
        val f = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
            ?: return false
        val mime = f.sampleMimeType ?: return false
        if (mime !in PASSTHROUGH_MIMES) return false
        return runCatching { AudioCapabilities.getCapabilities(this).isPassthroughPlaybackSupported(f) }.getOrDefault(true)
    }

    /** Recreates the player at the same spot, with Dolby/DTS audio passed through or decoded on the TV. */
    private fun rebuildPlayer(passthrough: Boolean, speed: Float) {
        val old = player ?: return
        val idx = old.currentMediaItemIndex
        val pos = if (seekTarget >= 0) seekTarget else old.currentPosition
        val playing = old.playWhenReady
        val lang = old.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        handler.removeCallbacks(doSeek)
        seekTarget = -1L
        releasePlayer()
        audioPassthrough = passthrough
        val p = createPlayer()
        if (lang != null) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage(lang).build()
        p.setMediaItems(queue.map { buildItem(it) }, idx, pos)
        p.setPlaybackSpeed(speed)
        p.prepare()
        p.playWhenReady = playing
        applyLook()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playerError != null) {
            // After an error, Play means "try again" from where it stopped.
            reconnecting = false
            startReconnect()
            return
        }
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
        val p = player ?: return
        if (mode != "video") return
        if (current?.radio == true) { showCaption("Live radio always plays at normal speed", 2000); return }
        when {
            speed != 1f && audioIsPassthrough() -> {
                // Passthrough audio ignores speed changes: decode it on the TV while playing faster/slower.
                checkDecodedAudio = true
                rebuildPlayer(passthrough = false, speed = speed)
            }
            // Back to normal: send surround sound to the TV/soundbar untouched again.
            speed == 1f && !audioPassthrough -> rebuildPlayer(passthrough = true, speed = 1f)
            else -> p.setPlaybackSpeed(speed)
        }
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
        entries += "Rotate:  $rotation°" to {
            choose("Rotate video", listOf("0° (normal)", "90° right", "180° (upside down)", "270° (90° left)"), rotation / 90) {
                rotation = it * 90; applyTransform(); showCaption("Rotation: $rotation°", 1500)
            }
        }
        entries += "Zoom:  ${if (zoom == 1f) "off" else zoomLabel(zoom)}" to {
            choose("Zoom", ZOOMS.map { if (it == 1f) "Off (1×)" else zoomLabel(it) }, ZOOMS.indexOfFirst { it >= zoom - 0.01f }) {
                setZoom(ZOOMS[it])
            }
        }
        entries += "Restart from beginning" to { p.seekTo(0); p.play() }
        if (queue.size > 1) {
            entries += "Next video  (${p.currentMediaItemIndex + 1} of ${queue.size})" to { skip(1) }
            entries += "Previous video" to { skip(-1) }
        }
        entries += "Picture mode:  ${Look.mode(this)}" to { pickPictureMode() }
        entries += "Sound mode:  ${Sound.mode(this)}" to { pickSoundMode() }
        if (current?.online != true) {
            players.forEach { (pkg, name) -> entries += "Play in $name" to { openExternal(pkg) } }
            entries += "Open with other app…" to { openExternal(null) }
        }
        entries += "Disconnect phone & close" to { disconnect() }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    // -------------------------------------------------- picture & sound modes

    private fun applyLook() {
        val f = Look.filter(this)
        (playerView.videoSurfaceView as? android.view.TextureView)?.let { tv ->
            tv.setLayerPaint(f?.let { android.graphics.Paint().apply { colorFilter = it } })
        }
        image.colorFilter = f
    }

    private fun pickPictureMode() {
        val modes = Look.PRESETS.keys.toList()
        choose("Picture mode", modes, modes.indexOf(Look.mode(this))) {
            if (modes[it] == "Custom") customPicture()
            else { Look.setMode(this, modes[it]); applyLook(); showCaption("Picture: ${modes[it]}", 1500) }
        }
    }

    /** Remote-friendly sliders: Up/Down to move between them, Left/Right to change. */
    private fun customPicture() {
        val c = Look.custom(this)
        val rows = listOf(
            Triple("Brightness", c.brightness + 50, 100),
            Triple("Contrast", c.contrast - 50, 100),
            Triple("Colour", c.saturation, 200),
            Triple("Warmth", c.warmth + 50, 100),
        )
        sliders("Custom picture", rows) { v ->
            Look.setCustom(this, Look.Params(v[0] - 50, v[1] + 50, v[2], v[3] - 50))
            applyLook()
        }
    }

    private fun pickSoundMode() {
        val modes = Sound.PRESETS.keys.toList()
        choose("Sound mode", modes, modes.indexOf(Sound.mode(this))) {
            if (modes[it] == "Custom") customSound()
            else {
                Sound.setMode(this, modes[it])
                showCaption(if (Sound.supported) "Sound: ${modes[it]}" else
                    "Sound: ${modes[it]} (not available for this audio track — try the AAC/stereo track)", 3000)
            }
        }
    }

    private fun customSound() {
        val c = Sound.custom(this)
        val names = listOf("Bass", "Low mid", "Mid (voices)", "High mid", "Treble")
        val rows = names.mapIndexed { i, n -> Triple(n, c.gains[i] + 12, 24) } + Triple("Loudness boost", c.boost, 12)
        sliders("Custom sound (dB)", rows) { v ->
            Sound.setCustom(this, Sound.Preset(v.take(5).map { it - 12 }, v[5]))
        }
    }

    private fun sliders(title: String, rows: List<Triple<String, Int, Int>>, onChange: (List<Int>) -> Unit) {
        val d = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val bars = rows.map { (label, value, max) ->
            val text = TextView(this).apply { setTextColor(0xFFE8EAED.toInt()); textSize = 15f }
            val bar = android.widget.SeekBar(this).apply {
                this.max = max
                progress = value
                keyProgressIncrement = 1
                isFocusable = true
            }
            box.addView(text); box.addView(bar)
            Triple(label, text, bar)
        }
        fun refresh() {
            bars.forEachIndexed { i, (label, text, bar) ->
                val (_, _, max) = rows[i]
                val shown = when {
                    title.startsWith("Custom sound") && i < 5 -> "${bar.progress - 12}"
                    title.startsWith("Custom picture") && i == 1 -> "${bar.progress + 50}%"
                    title.startsWith("Custom picture") && i == 2 -> "${bar.progress}%"
                    title.startsWith("Custom picture") -> "${bar.progress - max / 2}"
                    else -> "${bar.progress}"
                }
                text.text = "$label:  $shown"
            }
        }
        refresh()
        bars.forEach { (_, _, bar) ->
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    refresh()
                    onChange(bars.map { it.third.progress })
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
            })
        }
        AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Done", null).show()
        bars.first().third.requestFocus()
    }

    /** Closes the player and tells the phone it can stop sharing now. */
    private fun disconnect() {
        sayBye()
        showCaption("Disconnected", 800)
        finish()
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
        bar.addView(button(R.drawable.ic_zoom, "Zoom") { cycleZoom() }, 0)
        bar.addView(button(R.drawable.ic_rotate, "Rotate") { rotate(90) }, 0)
        bar.addView(button(R.drawable.ic_tune, "Options") { showMenu() }, 0)
    }

    private fun cycleZoom() {
        val i = ZOOMS.indexOfFirst { it >= zoom - 0.01f }.coerceAtLeast(0)
        setZoom(ZOOMS[(i + 1) % ZOOMS.size])
    }

    // --------------------------------------------------------------- images

    private fun showImages(r: CastRequest.Images) {
        releasePlayer()
        slideVideo = false
        mode = "images"
        queue = mutableListOf()
        urls = r.urls
        names = r.names
        albumVideos = r.albumVideos
        playerView.visibility = View.GONE
        image.visibility = View.VISIBLE
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        stopSlideshow()
        showImage(r.index)
        if (r.slideshow) toggleSlideshow()
    }

    private fun showImage(i: Int) {
        if (urls.isEmpty()) return
        endSlideVideo(advance = false)
        index = ((i % urls.size) + urls.size) % urls.size
        resetView(apply = false)
        image.load(urls[index], imageLoader(this)) {
            crossfade(300)
            // Never decode bigger than the TV screen (a 12 MP photo would otherwise take ~48 MB).
            size(1920, 1080)
            listener(onSuccess = { _, _ -> applyTransform() })
        }
        applyTransform()
        showCaption("${names.getOrElse(index) { "" }}   (${index + 1}/${urls.size})   ·   ☰ rotate / zoom")
        if (albumVideos) {
            val token = ++videoCheck
            val item = urls[index]
            io.execute {
                val video = GooglePhotos.videoUrl(item)
                runOnUiThread {
                    if (video != null && token == videoCheck && mode == "images" && !isFinishing) playSlideVideo(video)
                }
            }
        }
    }

    /** Plays an album video full screen in place of its preview picture. */
    private fun playSlideVideo(url: String) {
        slideVideo = true
        handler.removeCallbacks(slideStep)
        val p = player ?: createPlayer()
        image.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        showCaption("Video   (${index + 1}/${urls.size})   ·   OK to pause · ⏭ to skip", 4000)
    }

    /** Back to pictures after an album video; [advance] moves on to the next item. */
    private fun endSlideVideo(advance: Boolean) {
        if (!slideVideo) return
        slideVideo = false
        releasePlayer()
        playerView.visibility = View.GONE
        image.visibility = View.VISIBLE
        if (advance) {
            showImage(index + 1)
            if (slideshow) {
                handler.removeCallbacks(slideStep)
                handler.postDelayed(slideStep, SLIDE_MS)
            }
        }
    }

    // ------------------------------------------------------ rotate / zoom

    private fun resetView(apply: Boolean = true) {
        rotation = 0
        zoom = 1f
        panX = 0f
        panY = 0f
        if (apply) applyTransform()
    }

    private fun rotate(deg: Int) {
        rotation = ((rotation + deg) % 360 + 360) % 360
        panX = 0f; panY = 0f
        applyTransform()
        showCaption("Rotation: $rotation°", 1500)
    }

    private fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, ZOOMS.last())
        if (zoom == 1f) { panX = 0f; panY = 0f }
        applyTransform()
        showCaption(if (zoom == 1f) "Zoom: off" else "Zoom: ${zoomLabel(zoom)}" +
            if (mode == "images" && zoom > 1f) "  ·  arrows to move, Back to reset" else "", 2500)
    }

    private fun stepZoom(dir: Int) {
        val i = ZOOMS.indexOfFirst { it >= zoom - 0.01f }.coerceAtLeast(0)
        setZoom(ZOOMS[(i + dir).coerceIn(0, ZOOMS.size - 1)])
    }

    private fun pan(dx: Int, dy: Int) {
        val step = 0.15f * maxOf(image.width, image.height)
        panX += dx * step
        panY += dy * step
        applyTransform()
    }

    private fun zoomLabel(z: Float) = if (z % 1f == 0f) "${z.toInt()}×" else "${z}×"

    /**
     * Applies rotation + zoom (+ pan for pictures) to the video surface or the picture.
     * For 90°/270° the content is shrunk so the rotated frame still fits the screen.
     */
    private fun applyTransform() {
        val target: View = (if (mode == "video") playerView.videoSurfaceView as? View else image) ?: return
        val cw = (if (mode == "video") playerView.width else image.width).toFloat()
        val ch = (if (mode == "video") playerView.height else image.height).toFloat()
        // Size of what is actually visible: the video surface, or the picture as fitted inside the screen.
        var w = target.width.toFloat()
        var h = target.height.toFloat()
        if (mode == "images") {
            val d = image.drawable
            if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0 && cw > 0 && ch > 0) {
                val sc = minOf(cw / d.intrinsicWidth, ch / d.intrinsicHeight)
                w = d.intrinsicWidth * sc
                h = d.intrinsicHeight * sc
            }
        }
        val turned = rotation % 180 != 0
        val rw = if (turned) h else w
        val rh = if (turned) w else h
        // Turned 90°/270°: scale so the rotated frame fills the screen as much as possible.
        val fit = if (turned && rw > 0 && rh > 0) minOf(cw / rw, ch / rh) else 1f
        val s = fit * zoom
        val maxX = maxOf(0f, (rw * s - cw) / 2f)
        val maxY = maxOf(0f, (rh * s - ch) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        target.rotation = rotation.toFloat()
        target.scaleX = s
        target.scaleY = s
        target.translationX = if (mode == "images") panX else 0f
        target.translationY = if (mode == "images") panY else 0f
    }

    private fun showImageMenu() {
        val entries = listOf<Pair<String, () -> Unit>>(
            "Rotate right ⟳" to { rotate(90) },
            "Rotate left ⟲" to { rotate(-90) },
            "Zoom in" to { stepZoom(1) },
            "Zoom out" to { stepZoom(-1) },
            "Reset rotation & zoom" to { resetView(); showCaption("Reset", 1200) },
            (if (slideshow) "Stop slideshow" else "Start slideshow") to { toggleSlideshow() },
        )
        AlertDialog.Builder(this)
            .setTitle(names.getOrElse(index) { "Picture" })
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
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
            "play" -> if (player?.playerError != null) togglePlay() else player?.play()
            "pause" -> player?.pause()
            "seek" -> if (ms != null) {
                handler.removeCallbacks(doSeek)
                seekTarget = -1L
                player?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                player?.seekTo(ms)
                player?.setSeekParameters(SeekParameters.DEFAULT)
                showCaption(fmt(ms), 1500)
            }
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
            "next" -> if (mode == "video") skip(1) else showImage(index + 1)
            "prev" -> if (mode == "video") skip(-1) else showImage(index - 1)
            "picmode" -> v?.let { m ->
                Look.setMode(this, m); applyLook(); showCaption("Picture: ${Look.mode(this)}", 1500)
            }
            "piccustom" -> v?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 4 }?.let {
                Look.setCustom(this, Look.Params(it[0], it[1], it[2], it[3])); applyLook()
            }
            "soundmode" -> v?.let { m -> Sound.setMode(this, m); showCaption("Sound: ${Sound.mode(this)}", 1500) }
            "soundcustom" -> v?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 6 }?.let {
                Sound.setCustom(this, Sound.Preset(it.take(5), it[5]))
            }
            "showpin" -> PairDialog.showIfPending(this)
            "disconnect" -> disconnect()
            "slideshow" -> toggleSlideshow()
            "rotate" -> rotate(v?.toIntOrNull() ?: 90)
            "zoom" -> when (v) {
                "in" -> stepZoom(1)
                "out" -> stepZoom(-1)
                null, "reset" -> setZoom(1f)
                else -> v.toFloatOrNull()?.let { setZoom(it) }
            }
            "resetview" -> { resetView(); showCaption("Reset", 1200) }
            "pan" -> when (v) {
                "left" -> pan(1, 0); "right" -> pan(-1, 0); "up" -> pan(0, 1); "down" -> pan(0, -1)
            }
            "stop" -> finish()
        }
        publishStatus()
    }

    private fun publishStatus() {
        val j = JSONObject().put("mode", mode).put("rotation", rotation).put("zoom", zoom.toDouble())
            .put("picMode", Look.mode(this)).put("picModes", JSONArray(Look.PRESETS.keys.toList()))
            .put("picCustom", Look.custom(this).let { JSONArray(listOf(it.brightness, it.contrast, it.saturation, it.warmth)) })
            .put("soundMode", Sound.mode(this)).put("soundModes", JSONArray(Sound.PRESETS.keys.toList()))
            .put("soundCustom", Sound.custom(this).let { JSONArray(it.gains + it.boost) })
            .put("soundOk", Sound.supported)
        when (mode) {
            "video" -> {
                val p = player
                val err = p?.playerError?.errorCodeName
                val c = current
                j.put("title", title)
                    .put("key", key)
                    .put("online", c?.online == true)
                    .put("radio", c?.radio == true)
                    .put("vpn", c?.online == true && (Vpn.up || AdBlock.vpnActive(this)))
                    .put("vpnName", if (Vpn.up) Vpn.provider(this) else "")
                    .put("qIndex", p?.currentMediaItemIndex ?: 0)
                    .put("qCount", queue.size)
                    .put("error", if (reconnecting) "" else err ?: "")
                    .put("reconnecting", reconnecting)
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
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (down) showImageMenu()
                return true
            }
            if (zoom > 1f) {
                // Zoomed in: arrows move around the picture, Back returns to normal view.
                val handled = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) pan(1, 0); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) pan(-1, 0); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (down) pan(0, 1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) pan(0, -1); true }
                    KeyEvent.KEYCODE_BACK -> { if (down) { setZoom(1f) }; true }
                    else -> false
                }
                if (handled) return true
            }
            val handled = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (down) { stopSlideshow(); showImage(index - 1) }; true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (down) { stopSlideshow(); showImage(index + 1) }; true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (down) {
                        val p = player
                        if (slideVideo && p != null) p.playWhenReady = !p.playWhenReady else toggleSlideshow()
                    }
                    true
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
            if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                if (down && event.repeatCount == 0) skip(if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1)
                return true
            }
            // ⏩ / ⏪ on the remote: our own seeking (adds up presses, speeds up when held).
            if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                if (down) {
                    val step = seekStep(event.repeatCount)
                    seekBy(if (event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -step else step)
                }
                return true
            }
            val radio = current?.radio == true
            // With the control bar hidden, left/right on the remote seek directly (other station for radio).
            if (!playerView.isControllerFullyVisible &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                if (down && radio) { if (event.repeatCount == 0) skip(if (left) -1 else 1) }
                else if (down) seekStep(event.repeatCount).let { seekBy(if (left) -it else it) }
                return true
            }
            return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) trimImages()
    }

    override fun onPause() {
        // Saved before the home screen comes back, so its "Continue" list is already up to date.
        if (seekTarget >= 0) doSeek.run()
        saveProgress()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Home pressed or another app opened: stop playback instead of playing hidden.
        if (!isFinishing && !awaitingVpn) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        reconnecting = false
        sayBye()
        io.shutdownNow()
        releasePlayer()
        // Built-in VPN is only for online playback; close it with the player unless another link is
        // starting, it was started from the phone's VPN button, or this screen only asked for permission.
        if (Bus.pending == null && !Vpn.keepOn && mode != "idle") Vpn.downAsync()
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
        private const val SEEK_DELAY_MS = 450L
        private val PASSTHROUGH_MIMES = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_TRUEHD,
        )
        private const val REQ_VPN = 41
        private const val RECONNECT_GIVE_UP_S = 15 * 60
        private val ZOOMS = listOf(1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
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
