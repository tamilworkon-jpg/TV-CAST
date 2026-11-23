package com.cleantv.tv

import android.os.Handler
import android.os.Looper

/** One entry of a video play queue. [online] = from the internet (ad-blocked, no resume history). */
data class VideoItem(
    val url: String,
    val title: String,
    val sub: String? = null,
    val key: String = "",
    val online: Boolean = false,
    val radio: Boolean = false,
    val artwork: String? = null,
)

sealed class CastRequest {
    data class Video(val items: List<VideoItem>, val index: Int, val startMs: Long) : CastRequest() {
        constructor(url: String, title: String, sub: String?, key: String, startMs: Long) :
            this(listOf(VideoItem(url, title, sub, key)), 0, startMs)
    }
    data class Images(val urls: List<String>, val names: List<String>, val index: Int, val slideshow: Boolean = false) : CastRequest()
}

interface CastController {
    fun onCommand(cmd: String, v: String?)
}

/** Shared state between the HTTP server thread and the player screen. */
object Bus {
    const val IDLE = "{\"mode\":\"idle\"}"

    @Volatile var pending: CastRequest? = null
    @Volatile var controller: CastController? = null
    @Volatile var status: String = IDLE
    @Volatile var onPhoneChanged: (() -> Unit)? = null
    @Volatile var onPaired: (() -> Unit)? = null
    @Volatile var onPairRequest: (() -> Unit)? = null
    val main = Handler(Looper.getMainLooper())
}
