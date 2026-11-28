package com.cleantv.tv

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.load
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Full-screen player. Videos, links, radio and album videos play on the built-in VLC engine
 * (libVLC): every common container and codec — MKV, MP4, HEVC/H.264, AC3/E-AC3, DTS, TrueHD,
 * AAC, FLAC… — with hardware video decoding drawn straight to the screen for full HD/4K quality.
 * Pictures are shown with Coil.
 */
class PlayerActivity : AppCompatActivity(), CastController {
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var image: ImageView
    private lateinit var caption: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var controls: View
    private lateinit var ctlTitle: TextView
    private lateinit var ctlInfo: TextView
    private lateinit var ctlPos: TextView
    private lateinit var ctlDur: TextView
    private lateinit var ctlSeek: SeekBar
    private lateinit var ctlSeekRow: View
    private lateinit var btnPrev: ImageButton
    private lateinit var btnRew: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnFwd: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnAudio: TextView
    private lateinit var btnSubs: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnAspect: TextView
    private lateinit var audio: AudioManager

    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    /** Video drawn on a TextureView (needed for picture modes / rotate / zoom) instead of the faster SurfaceView. */
    private var textureMode = false

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
    private var reconnectAttempts = 0
    /** The Android VPN permission prompt is open over this screen. */
    private var awaitingVpn = false
    private var queue: MutableList<VideoItem> = mutableListOf()
    private var qIndex = 0
    private var rotation = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var reconnectStartedAt = 0L
    private var resumeAt = 0L
    private var speed = 1f
    private var ended = false
    private var errorText = ""
    private var buffering = false
    /** Length of the current item (ms), 0 = unknown / live. */
    private var length = 0L
    /** Last known play position; VLC reports 0 after an error or the end, so this is kept separately. */
    private var lastTime = 0L
    /** The current item has started playing once (subtitles/delays applied). */
    private var started = false
    /** Where repeated ⏩/⏪ presses are heading; the real seek happens once the presses stop. */
    private var seekTarget = -1L
    /** Stations in a row that failed to play; stops trying once every station has been tried. */
    private var radioFails = 0
    /** When the current stall (buffering with no progress) began; 0 = not stalled. */
    private var stallSince = 0L
    /** Last time the stall watchdog restarted the stream. */
    private var lastStallFix = 0L
    private var stallTime = -1L
    private var backAt = 0L
    /** The video was handed to another player app: don't tell the phone to stop sharing. */
    private var handedOff = false
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            mp?.let { p ->
                val t = p.time
                if (mode == "video" && p.isPlaying && t > 0) {
                    lastTime = t
                    if (t > resumeAt + 10_000) reconnectAttempts = 0
                }
            }
            checkStall()
            if (controls.visibility == View.VISIBLE) updateControls()
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
    private val closeIfEnded = Runnable { if (ended) finish() }
    private val hideControls = Runnable {
        // Paused: keep the bar up so it's clear what's going on.
        if (mp?.isPlaying == true || current?.radio == true) controls.visibility = View.GONE else scheduleHide()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        videoLayout = findViewById(R.id.videoLayout)
        image = findViewById(R.id.image)
        caption = findViewById(R.id.caption)
        spinner = findViewById(R.id.spinner)
        controls = findViewById(R.id.controls)
        ctlTitle = findViewById(R.id.ctlTitle)
        ctlInfo = findViewById(R.id.ctlInfo)
        ctlPos = findViewById(R.id.ctlPos)
        ctlDur = findViewById(R.id.ctlDur)
        ctlSeek = findViewById(R.id.ctlSeek)
        ctlSeekRow = findViewById(R.id.ctlSeekRow)
        btnPrev = findViewById(R.id.btnPrev)
        btnRew = findViewById(R.id.btnRew)
        btnPlay = findViewById(R.id.btnPlay)
        btnFwd = findViewById(R.id.btnFwd)
        btnNext = findViewById(R.id.btnNext)
        btnAudio = findViewById(R.id.btnAudio)
        btnSubs = findViewById(R.id.btnSubs)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnAspect = findViewById(R.id.btnAspect)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        cachedPlayers = externalPlayers(this)
        aspect = prefs.getInt("aspect", 0).coerceIn(0, ASPECTS.size - 1)
        setupControls()
        image.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
        videoLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
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

    // ---------------------------------------------------------------- VLC engine

    private fun vlcOptions() = arrayListOf(
        "--network-caching=3000",       // phone over Wi-Fi: rides out short drops, still quick to start/seek
        "--prefetch-buffer-size=49152", // read up to 48 MB ahead, so a phone that briefly sleeps doesn't stall the film
        "--prefetch-read-size=1048576",
        "--http-reconnect",             // resume a dropped HTTP connection instead of ending the video
        "--audio-time-stretch",         // faster/slower playback keeps the normal voice pitch
        "--audio-resampler=soxr",       // best-quality resampling
        "--avcodec-skiploopfilter=0",   // never trade picture quality for speed in software decoding
        "--subsdec-encoding=UTF-8",
        "--freetype-rel-fontsize=16",
        "--no-stats",
    )

    /** Creates the engine on first use and attaches it to the screen. */
    private fun vlc(): MediaPlayer {
        mp?.let { return it }
        val lib = libVlc ?: LibVLC(applicationContext, vlcOptions()).also { libVlc = it }
        val p = MediaPlayer(lib)
        p.setEventListener { e ->
            if (Looper.myLooper() == Looper.getMainLooper()) onVlcEvent(e) else handler.post { onVlcEvent(e) }
        }
        // Dolby/DTS: decoded on the TV (works with every TV/soundbar) unless passthrough is chosen in ☰.
        p.setAudioDigitalOutputEnabled(prefs.getBoolean("passthrough", false))
        mp = p
        textureMode = needTexture()
        p.attachViews(videoLayout, null, true, textureMode)
        afterAttach()
        p.videoScale = ASPECTS[aspect].first
        applySound()
        return p
    }

    private fun releaseVlc() {
        saveProgress()
        mp?.let { p ->
            p.setEventListener(null)
            runCatching { p.stop() }
            runCatching { p.detachViews() }
            p.release()
        }
        mp = null
        libVlc?.release()
        libVlc = null
    }

    /** TextureView only when picture mode, rotation or zoom need it; SurfaceView is faster and sharper otherwise. */
    private fun needTexture(): Boolean = Look.filter(this) != null || rotation != 0 || zoom != 1f

    private fun ensureSurface() {
        val p = mp ?: return
        val need = needTexture()
        if (need == textureMode) return
        textureMode = need
        p.detachViews()
        p.attachViews(videoLayout, null, true, textureMode)
        afterAttach()
        p.videoScale = ASPECTS[aspect].first
    }

    private fun afterAttach() {
        // A rotated/zoomed video must be allowed to draw outside its frame.
        fun open(g: ViewGroup) {
            g.clipChildren = false
            g.clipToPadding = false
            for (i in 0 until g.childCount) (g.getChildAt(i) as? ViewGroup)?.let { open(it) }
        }
        open(videoLayout)
        handler.post { applyLook(); applyTransform() }
    }

    private fun textureView(): TextureView? {
        fun find(v: View): TextureView? {
            if (v is TextureView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return if (textureMode) find(videoLayout) else null
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        if (mp == null || isFinishing) return
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                buffering = false
                spinner.visibility = View.GONE
                errorText = ""
                radioFails = 0
                if (reconnecting) {
                    reconnecting = false
                    handler.removeCallbacks(reconnectStep)
                }
                if (!started) onStarted()
            }
            MediaPlayer.Event.Buffering -> {
                buffering = e.buffering < 100f
                spinner.visibility = if (buffering && mode == "video" && current?.radio != true) View.VISIBLE else View.GONE
            }
            MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
            MediaPlayer.Event.EndReached -> onEnded()
            MediaPlayer.Event.EncounteredError -> onError()
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> {
                val p = mp ?: return
                if (!subsOn && p.spuTrack != -1) p.setSpuTrack(-1)
                if (controls.visibility == View.VISIBLE) updateControls()
            }
            MediaPlayer.Event.Vout -> handler.post { applyLook(); applyTransform() }
        }
    }

    /** First "playing" of an item: external subtitles, sync offsets, speed. */
    private fun onStarted() {
        val p = mp ?: return
        started = true
        if (mode != "video") return
        subUrl?.let { p.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(it), subsOn) }
        p.setAudioDelay(prefs.getLong("audioDelay", 0) * 1000)
        p.setSpuDelay(prefs.getLong("subDelay", 0) * 1000)
        if (speed != 1f && current?.radio != true) p.rate = speed
    }

    // ---------------------------------------------------------------- video

    private fun playVideo(r: CastRequest.Video) {
        saveProgress()
        stopSlideshow()
        slideVideo = false
        mode = "video"
        queue = r.items.toMutableList()
        image.visibility = View.GONE
        videoLayout.visibility = View.VISIBLE
        reconnecting = false
        reconnectAttempts = 0
        handler.removeCallbacks(reconnectStep)
        handler.removeCallbacks(closeIfEnded)
        handler.removeCallbacks(doSeek)
        seekTarget = -1L
        speed = 1f
        rotation = 0; zoom = 1f; panX = 0f; panY = 0f
        startItem(r.index, r.startMs)
        val c = current ?: return
        val hint = when {
            c.radio -> "Live radio" + (if (r.items.size > 1) " · ◀ ▶ or ⏮ ⏭ for other stations" else "") + " · ☰ for sound modes"
            c.online -> "Private mode: nothing about this link is saved · ☰ for options"
            else -> "OK for controls · ◀ ▶ to skip · ☰ for audio, subtitles, sync, picture & sound"
        }
        val next = if (queue.size > 1 && !c.radio) "\n${qIndex + 1} of ${queue.size} · ⏭ for next video" else ""
        showCaption((if (r.startMs > 0) "${c.title}\nResuming from ${fmt(r.startMs)}" else c.title) + next + "\n$hint", 5000)
    }

    /** Starts queue entry [i] at [startMs]. */
    private fun startItem(i: Int, startMs: Long) {
        if (queue.isEmpty()) return
        val p = vlc()
        ensureSurface()
        qIndex = i.coerceIn(0, queue.size - 1)
        val v = queue[qIndex]
        syncCurrent()
        ended = false
        errorText = ""
        length = 0L
        lastTime = startMs.coerceAtLeast(0)
        resumeAt = lastTime
        started = false
        buffering = true
        spinner.visibility = if (v.radio) View.GONE else View.VISIBLE
        handler.removeCallbacks(closeIfEnded)
        val m = Media(libVlc, Uri.parse(v.url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":http-user-agent=Mozilla/5.0 (Linux; Android 9; AFT) TVCast/2")
        if (v.online) m.addOption(":network-caching=3000")
        if (startMs > 0) m.addOption(":start-time=${startMs / 1000.0}")
        p.media = m
        m.release()
        p.play()
        if (controls.visibility == View.VISIBLE) updateControls()
    }

    /** Current queue entry. */
    private val current: VideoItem? get() = queue.getOrNull(qIndex)

    /** Mirrors the current queue entry into title/key/videoUrl/subUrl. */
    private fun syncCurrent() {
        val c = current ?: return
        title = c.title
        key = if (c.online) "" else c.key // private mode: online links never reach History
        videoUrl = c.url
        subUrl = c.sub
        radioView(c)
    }

    /** Live radio: show the station logo instead of an empty video area. */
    private fun radioView(c: VideoItem) {
        if (!c.radio) {
            if (mode == "video") image.visibility = View.GONE
            return
        }
        image.visibility = View.VISIBLE
        image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (c.artwork != null) image.load(c.artwork, imageLoader(this)) { error(R.drawable.ic_launcher) }
        else image.setImageResource(R.drawable.ic_launcher)
        showControls(btnPlay)
    }

    /** Resume point saved for [v], if it was half watched. */
    private fun resumeFor(v: VideoItem): Long {
        if (v.online) return 0L
        val h = History.find(this, v.key) ?: return 0L
        val pos = h.optLong("pos")
        val dur = h.optLong("dur")
        return if (pos > 10_000 && dur > 0 && pos < dur - 30_000) pos else 0L
    }

    /** Moves to queue entry [i], continuing where it was left. */
    private fun goTo(i: Int) {
        val v = queue.getOrNull(i) ?: return
        if (v.radio) {
            startItem(i, 0)
            showCaption("${v.title}  (${i + 1} of ${queue.size})", 3000)
            return
        }
        val pos = resumeFor(v)
        startItem(i, pos)
        val n = "(${i + 1} of ${queue.size})"
        showCaption(if (pos > 0) "${v.title}\nResumed from ${fmt(pos)}  $n" else "${v.title}  $n", 3500)
    }

    private fun skip(dir: Int) {
        if (queue.size < 2) {
            showCaption(if (current?.radio == true) "No other stations in this list" else "No other videos in this list", 1500)
            return
        }
        saveProgress()
        handler.removeCallbacks(doSeek)
        seekTarget = -1L
        val n = queue.size
        if (current?.radio == true) { goTo(((qIndex + dir) % n + n) % n); return } // stations wrap around like a dial
        when {
            dir > 0 && qIndex + 1 < n -> goTo(qIndex + 1)
            dir > 0 -> showCaption("This is the last video", 1500)
            qIndex > 0 -> goTo(qIndex - 1)
            else -> mp?.setTime(0)
        }
    }

    private fun onEnded() {
        if (mode == "images") { endSlideVideo(advance = true); return }
        if (mode != "video") return
        val c = current ?: return
        // A phone video that stops well before its end: the connection dropped, not the film.
        if (!c.online && length > 0 && lastTime in 1 until length - 15_000) { startReconnect(); return }
        if (c.radio) { onError(); return }
        if (key.isNotEmpty() && length > 0) History.save(applicationContext, key, title, length, length)
        if (qIndex + 1 < queue.size) { goTo(qIndex + 1); return }
        ended = true
        showControls(btnPlay)
        showCaption("Finished. Closing in 10 seconds…", 10_000)
        handler.postDelayed(closeIfEnded, 10_000)
    }

    private fun onError() {
        spinner.visibility = View.GONE
        if (mode == "images") {
            showCaption("Couldn't play this video, skipping", 3000)
            endSlideVideo(advance = true)
            return
        }
        if (mode != "video") return
        val c = current ?: return
        if (c.radio && queue.size > 1 && ++radioFails < queue.size) {
            // Dead station: move on to the next one after a moment.
            errorText = "unavailable"
            showCaption("${c.title} isn't on air right now, trying the next station…", 4000)
            handler.postDelayed({ if (current?.radio == true && errorText.isNotEmpty()) skip(1) }, 2500)
            return
        }
        if (!c.online && !reconnecting) { startReconnect(); return }
        if (reconnecting) return
        errorText = "cannot play"
        showCaption(
            if (c.radio) "${c.title} isn't on air right now."
            else "Can't play this link.\nIt may need a website login, or it isn't a direct video/stream link.", 15_000
        )
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
        resumeAt = if (lastTime > 0) lastTime else resumeAt
        saveProgress()
        handler.removeCallbacks(reconnectStep)
        handler.postDelayed(reconnectStep, 2000)
    }

    private val reconnectStep: Runnable = object : Runnable {
        override fun run() {
            if (!reconnecting || mode != "video") return
            val oldBase = currentBase()
            val candidates = listOfNotNull(latestPhoneBase(), oldBase).distinct()
            io.execute {
                val good = candidates.firstOrNull { httpOk("$it/alive") }
                runOnUiThread {
                    if (!reconnecting || isFinishing) return@runOnUiThread
                    if (good != null) {
                        if (++reconnectAttempts > 4) {
                            // The phone answers but this file keeps failing: it's the file, not the network.
                            reconnecting = false
                            errorText = "cannot play"
                            showCaption("This video keeps failing to play.\nPress ☰ → Play in VLC / MX Player, or pick another video.", 30_000)
                            return@runOnUiThread
                        }
                        if (oldBase != null && good != oldBase) {
                            // Phone got a new IP address: switch every link in the list, keep the position.
                            queue = queue.map { v ->
                                v.copy(url = v.url.replace(oldBase, good), sub = v.sub?.replace(oldBase, good))
                            }.toMutableList()
                        }
                        val at = resumeAt
                        startItem(qIndex, at)
                        resumeAt = at
                        showCaption("Reconnected", 2000)
                        // reconnecting is cleared once VLC reports Playing; if it doesn't, try again.
                        handler.postDelayed(this, 15_000)
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

    /**
     * The phone stopped sending (usually: it was locked and Android paused TV Cast there, or its Wi-Fi dozed).
     * After a while open a fresh connection at the same spot; if the phone doesn't answer, reconnect.
     */
    private fun checkStall() {
        val p = mp
        val c = current
        val now = System.currentTimeMillis()
        val t = p?.time ?: 0L
        // "Wants to play" (not paused by the user) but the position isn't moving.
        val wantsToPlay = p != null && (p.isPlaying || buffering)
        val moving = t != stallTime
        stallTime = t
        val stalled = mode == "video" && wantsToPlay && !moving && c != null && !c.online && !c.radio &&
            !reconnecting && !ended && errorText.isEmpty() && started && seekTarget < 0
        if (!stalled) { stallSince = 0L; return }
        if (stallSince == 0L) { stallSince = now; return }
        val waited = now - stallSince
        if (waited < STALL_MS || now - lastStallFix < STALL_MS) return
        lastStallFix = now
        stallSince = 0L
        val b = currentBase() ?: return
        val at = lastTime
        showCaption(
            "Waiting for the phone…\nIf it's locked, Android may have paused TV Cast there. Unlocking it continues the video;\n" +
                "to stop this happening, allow TV Cast to run without battery restrictions on the phone.", 10_000
        )
        io.execute {
            val alive = httpOk("$b/alive")
            runOnUiThread {
                if (isFinishing || mode != "video" || (mp?.time ?: 0L) != at && (mp?.time ?: 0L) > at + 2000) return@runOnUiThread // recovered meanwhile
                if (alive) startItem(qIndex, at).also { resumeAt = at } // stuck connection: start a fresh one
                else startReconnect()
            }
        }
    }

    /** Best known position of the current item. */
    private fun position(): Long {
        if (seekTarget >= 0) return seekTarget
        val t = mp?.time ?: 0L
        return if (t > 0 && mode == "video") t else lastTime
    }

    private fun saveProgress() {
        if (mode != "video" || key.isEmpty() || mp == null) return
        val dur = length
        if (dur <= 0) return
        val pos = if (ended) dur else position()
        if (pos <= 0) return
        History.save(applicationContext, key, title, pos, dur)
    }

    /** ⏩/⏪ and ◀/▶: presses add up (hold the button to go faster) and the player seeks once they stop. */
    private fun seekBy(deltaMs: Long) {
        val p = mp ?: return
        if (current?.radio == true) { skip(if (deltaMs > 0) 1 else -1); return }
        if (!p.isSeekable || length <= 0) {
            showCaption(if (length <= 0 && !buffering) "This is a live stream, it can't be skipped through" else "Still loading…", 2000)
            return
        }
        val from = position()
        val target = (from + deltaMs).coerceIn(0, (length - 1000).coerceAtLeast(0))
        seekTarget = target
        val now = if (p.time > 0) p.time else lastTime
        val diff = target - now
        val sign = if (diff >= 0) "+" else "−"
        showCaption("${fmt(target)} / ${fmt(length)}    ($sign${fmt(kotlin.math.abs(diff))})", 1800)
        if (controls.visibility == View.VISIBLE) updateControls()
        handler.removeCallbacks(doSeek)
        handler.postDelayed(doSeek, SEEK_DELAY_MS)
    }

    private val doSeek = Runnable {
        val t = seekTarget
        val p = mp
        seekTarget = -1L
        if (t >= 0 && p != null) {
            if (ended) startItem(qIndex, t)
            else p.setTime(t, false) // exact: a key-frame seek can land before the target and lose the jump
            lastTime = t
        }
        saveProgress()
    }

    /** Seek step for a held-down button: 10 s, then 30 s, then 1 min, then 2 min. */
    private fun seekStep(repeat: Int): Long = when {
        repeat < 6 -> 10_000L
        repeat < 16 -> 30_000L
        repeat < 30 -> 60_000L
        else -> 120_000L
    }

    private fun togglePlay() {
        val p = mp ?: return
        if (reconnecting) return
        if (ended) { startItem(qIndex, 0); return }
        if (errorText.isNotEmpty()) {
            // After an error, Play means "try again" from where it stopped.
            if (current?.online == true) startItem(qIndex, lastTime)
            else { reconnectAttempts = 0; startReconnect() }
            return
        }
        if (p.isPlaying) p.pause() else p.play()
        handler.postDelayed({ updateControls() }, 150)
    }

    private fun cycleAspect() = setAspect((aspect + 1) % ASPECTS.size)

    private fun setAspect(i: Int) {
        aspect = i.coerceIn(0, ASPECTS.size - 1)
        prefs.edit().putInt("aspect", aspect).apply()
        mp?.videoScale = ASPECTS[aspect].first
        handler.postDelayed({ applyTransform() }, 200)
        showCaption("Screen size: ${ASPECTS[aspect].second}", 1500)
        updateControls()
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

    private fun setSpeed(s: Float) {
        if (mode != "video") return
        if (current?.radio == true) { showCaption("Live radio always plays at normal speed", 2000); return }
        speed = s.coerceIn(0.25f, 4f)
        mp?.rate = speed
        showCaption("Speed ${speedLabel(speed)}", 1500)
        updateControls()
    }

    private fun speedLabel(s: Float) = (if (s % 1f == 0f) "${s.toInt()}" else "$s") + "x"

    private fun setAudioDelay(ms: Long) {
        prefs.edit().putLong("audioDelay", ms).apply()
        mp?.setAudioDelay(ms * 1000)
        showCaption("Audio sync: ${delayLabel(ms)}", 2500)
    }

    private fun setSubDelay(ms: Long) {
        prefs.edit().putLong("subDelay", ms).apply()
        mp?.setSpuDelay(ms * 1000)
        showCaption("Subtitle sync: ${delayLabel(ms)}", 2500)
    }

    private fun delayLabel(ms: Long) = when {
        ms == 0L -> "0 (normal)"
        ms > 0 -> "+$ms ms (later)"
        else -> "$ms ms (earlier)"
    }

    private fun setPassthrough(on: Boolean) {
        prefs.edit().putBoolean("passthrough", on).apply()
        val p = mp ?: return
        p.setAudioDigitalOutputEnabled(on)
        // Takes effect when the audio restarts: continue from the same spot.
        if (mode == "video" && current?.radio != true) startItem(qIndex, position())
        showCaption(if (on) "Dolby/DTS sent to the TV/soundbar as-is (only if it supports them)" else "Dolby/DTS decoded on the TV (works everywhere)", 4000)
    }

    private fun toggleSubs() {
        val p = mp ?: return
        if (subsOn) {
            subsOn = false
            p.setSpuTrack(-1)
        } else {
            subsOn = true
            val first = p.spuTracks?.firstOrNull { it.id != -1 }
            if (first != null) p.setSpuTrack(first.id)
            else subUrl?.let { p.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(it), true) }
        }
        showCaption(if (subsOn) "Subtitles on" else "Subtitles off", 1500)
        updateControls()
    }

    /** id is a track id from [tracksJson], or "off" to disable subtitles. */
    private fun selectTrack(type: Int, id: String?) {
        val p = mp ?: return
        if (id == null || id == "off") {
            if (type != TRACK_TEXT) return
            subsOn = false
            p.setSpuTrack(-1)
            showCaption("Subtitles off", 1500)
        } else {
            val n = id.toIntOrNull() ?: return
            if (type == TRACK_TEXT) { subsOn = true; p.setSpuTrack(n) } else p.setAudioTrack(n)
            val name = (if (type == TRACK_TEXT) p.spuTracks else p.audioTracks)?.firstOrNull { it.id == n }?.name
            showCaption(name ?: "Track changed", 2000)
        }
        updateControls()
    }

    private fun tracksJson(type: Int): JSONArray {
        val arr = JSONArray()
        val p = mp ?: return arr
        if (mode != "video") return arr
        val list = (if (type == TRACK_TEXT) p.spuTracks else p.audioTracks) ?: return arr
        val sel = if (type == TRACK_TEXT) p.spuTrack else p.audioTrack
        list.filter { it.id != -1 }.forEachIndexed { i, t ->
            val label = t.name?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}"
            arr.put(JSONObject().put("id", t.id.toString()).put("label", label).put("selected", t.id == sel))
        }
        return arr
    }

    private fun volume(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    /** Hands the current video to another player app on the TV (VLC, MX Player…) at the same position. */
    private fun openExternal(pkg: String?) {
        if (mode != "video" || videoUrl.isEmpty()) return
        val pos = position()
        saveProgress()
        handedOff = true // the phone keeps sharing; the other app is still using it
        try {
            startActivity(ExternalActivity.intent(this, videoUrl, currentBase(), key, title, pos, pkg, subUrl))
            finish()
        } catch (e: Exception) {
            handedOff = false
            showCaption("That player is not installed", 3000)
        }
    }

    /** Other player apps, VLC first; MX Player can't play Dolby/DTS sound by itself. */
    private fun externalEntries(): List<Pair<String, () -> Unit>> = cachedPlayers
        .sortedBy { (pkg, _) -> if (pkg.startsWith("org.videolan")) 0 else 1 }
        .map { (pkg, name) ->
            val note = if (pkg.startsWith("com.mxtech")) "  (may have no AC3/DTS sound)" else ""
            "Play in $name$note" to { openExternal(pkg) }
        }

    private fun pickAudio() = pickTrack("Audio track", tracksJson(TRACK_AUDIO), TRACK_AUDIO, false)
    private fun pickSubs() = pickTrack("Subtitles", tracksJson(TRACK_TEXT), TRACK_TEXT, true)

    private fun pickSpeed() {
        choose("Speed", SPEEDS.map { speedLabel(it) }, SPEEDS.indexOf(speed)) { setSpeed(SPEEDS[it]) }
    }

    private fun pickDelay(title: String, current: Long, onPick: (Long) -> Unit) {
        val labels = DELAYS.map { delayLabel(it) }
        choose(title, labels, DELAYS.indexOf(current).takeIf { it >= 0 } ?: DELAYS.indexOf(0L)) { onPick(DELAYS[it]) }
    }

    private fun showMenu() {
        val p = mp ?: return
        val audioTracks = tracksJson(TRACK_AUDIO)
        val subTracks = tracksJson(TRACK_TEXT)
        fun selectedLabel(a: JSONArray): String? =
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optBoolean("selected") }?.optString("label")
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val radio = current?.radio == true
        val passthrough = prefs.getBoolean("passthrough", false)

        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (!radio) {
            entries += "Audio track:  ${selectedLabel(audioTracks) ?: "—"}" to { pickAudio() }
            entries += "Subtitles:  ${if (subsOn) selectedLabel(subTracks) ?: "On" else "Off"}" to { pickSubs() }
            entries += "Audio sync (lip sync):  ${delayLabel(prefs.getLong("audioDelay", 0))}" to {
                pickDelay("Audio sync — later if sound comes before the lips", prefs.getLong("audioDelay", 0)) { setAudioDelay(it) }
            }
            entries += "Subtitle sync:  ${delayLabel(prefs.getLong("subDelay", 0))}" to {
                pickDelay("Subtitle sync — later if subtitles come too early", prefs.getLong("subDelay", 0)) { setSubDelay(it) }
            }
            entries += "Screen size:  ${ASPECTS[aspect].second}" to {
                choose("Screen size", ASPECTS.map { it.second }, aspect) { setAspect(it) }
            }
        }
        entries += "Volume:  $vol / $volMax" to {
            val step = if (volMax > 30) 5 else 1
            val levels = (0..volMax step step).toList()
            choose("Volume", levels.map { if (it == 0) "Mute" else "$it" }, levels.indexOfFirst { it >= vol }) { setVolume(levels[it]) }
        }
        if (!radio) {
            entries += "Speed:  ${speedLabel(speed)}" to { pickSpeed() }
            entries += "Rotate:  $rotation°" to {
                choose("Rotate video", listOf("0° (normal)", "90° right", "180° (upside down)", "270° (90° left)"), rotation / 90) {
                    rotation = it * 90; ensureSurface(); applyTransform(); showCaption("Rotation: $rotation°", 1500)
                }
            }
            entries += "Zoom:  ${if (zoom == 1f) "off" else zoomLabel(zoom)}" to {
                choose("Zoom", ZOOMS.map { if (it == 1f) "Off (1×)" else zoomLabel(it) }, ZOOMS.indexOfFirst { it >= zoom - 0.01f }) {
                    setZoom(ZOOMS[it])
                }
            }
            entries += "Restart from beginning" to { if (ended) startItem(qIndex, 0) else { p.setTime(0); p.play() } }
            if (queue.size > 1) {
                entries += "Next video  (${qIndex + 1} of ${queue.size})" to { skip(1) }
                entries += "Previous video" to { skip(-1) }
            }
            entries += "Picture mode:  ${Look.mode(this)}" to { pickPictureMode() }
        } else if (queue.size > 1) {
            entries += "Next station  (${qIndex + 1} of ${queue.size})" to { skip(1) }
            entries += "Previous station" to { skip(-1) }
        }
        entries += "Sound mode:  ${Sound.mode(this)}" to { pickSoundMode() }
        entries += "Surround sound:  ${if (passthrough) "pass Dolby/DTS to soundbar" else "decode on TV"}" to {
            choose("Surround sound (Dolby / DTS)", listOf(
                "Decode on the TV — always has sound (recommended)",
                "Pass through to TV/soundbar — only if it supports Dolby/DTS",
            ), if (passthrough) 1 else 0) { setPassthrough(it == 1) }
        }
        if (current?.online != true) {
            entries += externalEntries()
            entries += "Open with other app…" to { openExternal(null) }
        }
        entries += "Disconnect phone & close" to { disconnect() }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    // ------------------------------------------------------------ on-screen controls

    private fun setupControls() {
        btnPlay.setOnClickListener { togglePlay() }
        btnRew.setOnClickListener { seekBy(-10_000) }
        btnFwd.setOnClickListener { seekBy(10_000) }
        btnPrev.setOnClickListener { skip(-1) }
        btnNext.setOnClickListener { skip(1) }
        btnAudio.setOnClickListener { pickAudio() }
        btnSubs.setOnClickListener { pickSubs() }
        btnSpeed.setOnClickListener { pickSpeed() }
        btnAspect.setOnClickListener { cycleAspect() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu() }
        ctlSeek.setOnClickListener { togglePlay() }
    }

    private fun showControls(focus: View? = null) {
        if (mode != "video") return
        val wasVisible = controls.visibility == View.VISIBLE
        controls.visibility = View.VISIBLE
        updateControls()
        if (focus != null && focus.visibility == View.VISIBLE && focus.isShown) focus.requestFocus()
        else if (!wasVisible || controls.findFocus() == null) btnPlay.requestFocus()
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, CONTROLS_MS)
    }

    private fun updateControls() {
        val p = mp ?: return
        if (mode != "video") return
        val radio = current?.radio == true
        ctlTitle.text = title
        ctlInfo.text = buildList {
            if (radio) add("Live radio")
            if (queue.size > 1) add("${qIndex + 1} of ${queue.size}")
            p.currentVideoTrack?.let { v -> if (v.height > 0) add(qualityLabel(v.width, v.height)) }
            if (speed != 1f && !radio) add("Speed ${speedLabel(speed)}")
            if (reconnecting) add("Reconnecting…")
        }.joinToString("  ·  ")
        val pos = position()
        val seekable = !radio && length > 0
        ctlSeekRow.visibility = if (seekable) View.VISIBLE else View.GONE
        if (seekable) {
            ctlPos.text = fmt(pos)
            ctlDur.text = fmt(length)
            ctlSeek.progress = (pos * 1000 / length).toInt().coerceIn(0, 1000)
        }
        btnPlay.setImageResource(if (p.isPlaying && !ended) R.drawable.ic_ctl_pause else R.drawable.ic_ctl_play)
        val many = queue.size > 1
        btnPrev.visibility = if (many) View.VISIBLE else View.GONE
        btnNext.visibility = if (many) View.VISIBLE else View.GONE
        btnRew.visibility = if (seekable) View.VISIBLE else View.GONE
        btnFwd.visibility = if (seekable) View.VISIBLE else View.GONE
        val vis = if (radio) View.GONE else View.VISIBLE
        btnAudio.visibility = vis
        btnSubs.visibility = vis
        btnSpeed.visibility = vis
        btnAspect.visibility = vis
        btnSubs.text = if (subsOn && p.spuTrack != -1) "Subtitles: on" else "Subtitles: off"
        btnSpeed.text = speedLabel(speed)
        btnAspect.text = ASPECTS[aspect].second.substringBefore(" (")
    }

    private fun qualityLabel(w: Int, h: Int): String = when {
        h >= 2000 || w >= 3800 -> "4K"
        h >= 1000 || w >= 1900 -> "Full HD 1080p"
        h >= 700 || w >= 1260 -> "HD 720p"
        else -> "${h}p"
    } + "  ($w×$h)"

    // -------------------------------------------------- picture & sound modes

    private fun applyLook() {
        val f = Look.filter(this)
        textureView()?.setLayerPaint(f?.let { android.graphics.Paint().apply { colorFilter = it } })
        image.colorFilter = f
    }

    private fun applySound() {
        mp?.setEqualizer(Sound.vlcEqualizer(this))
    }

    private fun pickPictureMode() {
        val modes = Look.PRESETS.keys.toList()
        choose("Picture mode", modes, modes.indexOf(Look.mode(this))) {
            if (modes[it] == "Custom") customPicture()
            else { Look.setMode(this, modes[it]); ensureSurface(); applyLook(); showCaption("Picture: ${modes[it]}", 1500) }
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
            ensureSurface()
            applyLook()
        }
    }

    private fun pickSoundMode() {
        val modes = Sound.PRESETS.keys.toList()
        choose("Sound mode", modes, modes.indexOf(Sound.mode(this))) {
            if (modes[it] == "Custom") customSound()
            else {
                Sound.setMode(this, modes[it])
                applySound()
                showCaption("Sound: ${modes[it]}", 2000)
            }
        }
    }

    private fun customSound() {
        val c = Sound.custom(this)
        val names = listOf("Bass", "Low mid", "Mid (voices)", "High mid", "Treble")
        val rows = names.mapIndexed { i, n -> Triple(n, c.gains[i] + 12, 24) } + Triple("Loudness boost", c.boost, 12)
        sliders("Custom sound (dB)", rows) { v ->
            Sound.setCustom(this, Sound.Preset(v.take(5).map { it - 12 }, v[5]))
            applySound()
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
            val bar = SeekBar(this).apply {
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
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    refresh()
                    onChange(bars.map { it.third.progress })
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
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

    // --------------------------------------------------------------- images

    private fun showImages(r: CastRequest.Images) {
        saveProgress()
        mp?.stop()
        slideVideo = false
        mode = "images"
        queue = mutableListOf()
        controls.visibility = View.GONE
        spinner.visibility = View.GONE
        urls = r.urls
        names = r.names
        albumVideos = r.albumVideos
        videoLayout.visibility = View.GONE
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
        image.visibility = View.GONE
        videoLayout.visibility = View.VISIBLE
        val p = vlc()
        ensureSurface()
        val m = Media(libVlc, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=3000")
        p.media = m
        m.release()
        p.play()
        showCaption("Video   (${index + 1}/${urls.size})   ·   OK to pause · ⏭ to skip", 4000)
    }

    /** Back to pictures after an album video; [advance] moves on to the next item. */
    private fun endSlideVideo(advance: Boolean) {
        if (!slideVideo) return
        slideVideo = false
        mp?.stop()
        videoLayout.visibility = View.GONE
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
        if (apply) { ensureSurface(); applyTransform() }
    }

    private fun rotate(deg: Int) {
        rotation = ((rotation + deg) % 360 + 360) % 360
        panX = 0f; panY = 0f
        ensureSurface()
        applyTransform()
        showCaption("Rotation: $rotation°", 1500)
    }

    private fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, ZOOMS.last())
        if (zoom == 1f) { panX = 0f; panY = 0f }
        ensureSurface()
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
     * Applies rotation + zoom (+ pan for pictures) to the video or the picture.
     * For 90°/270° the content is shrunk so the rotated frame still fits the screen.
     */
    private fun applyTransform() {
        val video = mode == "video" || slideVideo
        val target: View = (if (video) textureView() else image) ?: return
        val cw = (if (video) videoLayout.width else image.width).toFloat()
        val ch = (if (video) videoLayout.height else image.height).toFloat()
        // Size of what is actually visible: the video as fitted inside its view, or the fitted picture.
        var w = target.width.toFloat()
        var h = target.height.toFloat()
        if (video) {
            val vt = mp?.currentVideoTrack
            if (vt != null && vt.width > 0 && vt.height > 0 && w > 0 && h > 0) {
                val sar = if (vt.sarNum > 0 && vt.sarDen > 0) vt.sarNum.toFloat() / vt.sarDen else 1f
                val vw = vt.width * sar
                val vh = vt.height.toFloat()
                val sc = minOf(w / vw, h / vh)
                w = vw * sc
                h = vh * sc
            }
        } else {
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
        val fit = if (turned && rw > 0 && rh > 0) minOf(cw / rw, ch / rh) else 1f
        val s = fit * zoom
        val maxX = maxOf(0f, (rw * s - cw) / 2f)
        val maxY = maxOf(0f, (rh * s - ch) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        target.rotation = rotation.toFloat()
        target.scaleX = s
        target.scaleY = s
        target.translationX = if (!video) panX else 0f
        target.translationY = if (!video) panY else 0f
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
            "play" -> if (errorText.isNotEmpty() || ended) togglePlay() else mp?.play()
            "pause" -> mp?.pause()
            "seek" -> if (ms != null && mode == "video") {
                handler.removeCallbacks(doSeek)
                seekTarget = -1L
                if (ended) startItem(qIndex, ms) else mp?.setTime(ms, false)
                lastTime = ms
                showCaption(fmt(ms), 1500)
            }
            "fwd" -> seekBy(ms ?: 10_000)
            "rew" -> seekBy(-(ms ?: 10_000))
            "subs" -> toggleSubs()
            "subtrack" -> selectTrack(TRACK_TEXT, v)
            "audiotrack" -> selectTrack(TRACK_AUDIO, v)
            "speed" -> v?.toFloatOrNull()?.let { setSpeed(it) }
            "audiodelay" -> ms?.let { setAudioDelay(it) }
            "subdelay" -> ms?.let { setSubDelay(it) }
            "aspect" -> if (v != null) setAspect(v.toIntOrNull() ?: 0) else cycleAspect()
            "setvol" -> v?.toIntOrNull()?.let { setVolume(it) }
            "volup" -> volume(AudioManager.ADJUST_RAISE)
            "voldown" -> volume(AudioManager.ADJUST_LOWER)
            "mute" -> if (Build.VERSION.SDK_INT >= 23) volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "external" -> openExternal(v?.takeIf { it.isNotEmpty() })
            "next" -> if (mode == "video") skip(1) else showImage(index + 1)
            "prev" -> if (mode == "video") skip(-1) else showImage(index - 1)
            "picmode" -> v?.let { m ->
                Look.setMode(this, m); ensureSurface(); applyLook(); showCaption("Picture: ${Look.mode(this)}", 1500)
            }
            "piccustom" -> v?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 4 }?.let {
                Look.setCustom(this, Look.Params(it[0], it[1], it[2], it[3])); ensureSurface(); applyLook()
            }
            "soundmode" -> v?.let { m -> Sound.setMode(this, m); applySound(); showCaption("Sound: ${Sound.mode(this)}", 1500) }
            "soundcustom" -> v?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 6 }?.let {
                Sound.setCustom(this, Sound.Preset(it.take(5), it[5])); applySound()
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
            .put("soundOk", true)
        when (mode) {
            "video" -> {
                val p = mp
                val c = current
                j.put("title", title)
                    .put("key", key)
                    .put("engine", "vlc")
                    .put("online", c?.online == true)
                    .put("radio", c?.radio == true)
                    .put("vpn", c?.online == true && (Vpn.up || AdBlock.vpnActive(this)))
                    .put("vpnName", if (Vpn.up) Vpn.provider(this) else "")
                    .put("qIndex", qIndex)
                    .put("qCount", queue.size)
                    .put("error", if (reconnecting) "" else errorText)
                    .put("reconnecting", reconnecting)
                    .put("playing", p != null && p.isPlaying && !ended)
                    .put("buffering", buffering)
                    .put("ended", ended)
                    .put("pos", position())
                    .put("dur", length)
                    .put("speed", speed.toDouble())
                    .put("audioDelay", prefs.getLong("audioDelay", 0))
                    .put("subDelay", prefs.getLong("subDelay", 0))
                    .put("aspect", ASPECTS[aspect].second)
                    .put("aspects", JSONArray(ASPECTS.map { it.second }))
                    .put("vol", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .put("volMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    .put("subs", subsOn)
                    .put("audioTracks", tracksJson(TRACK_AUDIO))
                    .put("subTracks", tracksJson(TRACK_TEXT))
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
        val code = event.keyCode
        if (mode == "images") {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (down) showImageMenu()
                return true
            }
            if (zoom > 1f) {
                // Zoomed in: arrows move around the picture, Back returns to normal view.
                val handled = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) pan(1, 0); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) pan(-1, 0); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (down) pan(0, 1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) pan(0, -1); true }
                    KeyEvent.KEYCODE_BACK -> { if (down) { setZoom(1f) }; true }
                    else -> false
                }
                if (handled) return true
            }
            val handled = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (down) { stopSlideshow(); showImage(index - 1) }; true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (down) { stopSlideshow(); showImage(index + 1) }; true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (down) {
                        val p = mp
                        if (slideVideo && p != null) { if (p.isPlaying) p.pause() else p.play() } else toggleSlideshow()
                    }
                    true
                }
                else -> false
            }
            return handled || super.dispatchKeyEvent(event)
        }
        if (mode == "video") {
            val radio = current?.radio == true
            when (code) {
                KeyEvent.KEYCODE_MENU -> { if (down) showMenu(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (down && event.repeatCount == 0) skip(if (code == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (down) {
                        val step = seekStep(event.repeatCount)
                        seekBy(if (code == KeyEvent.KEYCODE_MEDIA_REWIND) -step else step)
                        if (!radio && controls.visibility != View.VISIBLE) showControls(ctlSeek)
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (down && event.repeatCount == 0) { togglePlay(); showControls() }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down && mp?.isPlaying != true) togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) mp?.pause(); return true }
            }
            val visible = controls.visibility == View.VISIBLE
            if (!visible) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (down && event.repeatCount == 0) showControls(btnPlay)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val left = code == KeyEvent.KEYCODE_DPAD_LEFT
                        if (down && radio) { if (event.repeatCount == 0) skip(if (left) -1 else 1) }
                        else if (down) {
                            seekStep(event.repeatCount).let { seekBy(if (left) -it else it) }
                            showControls(ctlSeek)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) showControls()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        // One Back mid-movie is easy to press by mistake: ask for a second press.
                        if (!down && event.repeatCount == 0) {
                            val now = System.currentTimeMillis()
                            if (now - backAt < 2500 || ended || errorText.isNotEmpty()) finish()
                            else { backAt = now; showCaption("Press Back again to close", 2500) }
                        }
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
            scheduleHide()
            if (code == KeyEvent.KEYCODE_BACK) {
                // Back first hides the bar; pressed again (bar hidden) it closes the player.
                if (!down) controls.visibility = View.GONE
                return true
            }
            if (ctlSeek.hasFocus() && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (down) seekStep(event.repeatCount).let { seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -it else it) }
                return true
            }
            if (ctlSeek.hasFocus() && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                if (down && event.repeatCount == 0) togglePlay()
                return true
            }
            return super.dispatchKeyEvent(event)
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
        if (!handedOff) sayBye()
        io.shutdownNow()
        releaseVlc()
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
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val SLIDE_MS = 5000L
        private const val REQ_VPN = 41
        private const val RECONNECT_GIVE_UP_S = 15 * 60
        private const val SEEK_DELAY_MS = 450L
        private const val STALL_MS = 15_000L
        private const val CONTROLS_MS = 5000L
        private const val TRACK_AUDIO = 1
        private const val TRACK_TEXT = 3
        private val ZOOMS = listOf(1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        private val DELAYS = listOf(-2000L, -1000L, -500L, -300L, -200L, -100L, -50L, 0L, 50L, 100L, 200L, 300L, 500L, 1000L, 2000L)
        private val ASPECTS = listOf(
            MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Fit",
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Fill screen (crop edges)",
            MediaPlayer.ScaleType.SURFACE_FILL to "Stretch",
            MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
            MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
            MediaPlayer.ScaleType.SURFACE_221_1 to "Cinema 2.21:1",
            MediaPlayer.ScaleType.SURFACE_ORIGINAL to "Original size",
        )
    }
}
