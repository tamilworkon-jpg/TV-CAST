package com.cleantv.tv

import android.os.Handler
import android.os.Looper

sealed class CastRequest {
    data class Video(val url: String, val title: String, val sub: String?, val key: String, val startMs: Long) : CastRequest()
    data class Images(val urls: List<String>, val names: List<String>, val index: Int) : CastRequest()
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
    val main = Handler(Looper.getMainLooper())
}
