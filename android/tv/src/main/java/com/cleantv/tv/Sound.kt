package com.cleantv.tv

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import org.json.JSONArray

/**
 * Sound modes (Movie, Music, Voice …) using the player's audio session equalizer,
 * plus a custom per-band equalizer. Remembered on the TV.
 *
 * Note: if a track is sent to the TV speakers as passthrough (some Dolby tracks), Android
 * can't apply effects to it; switching to the AAC/stereo audio track makes the modes work.
 */
object Sound {
    /** Gains in dB for 5 regions: bass, low-mid, mid, high-mid, treble; plus loudness boost in dB. */
    data class Preset(val gains: List<Int>, val boost: Int = 0)

    val PRESETS = linkedMapOf(
        "Standard" to Preset(listOf(0, 0, 0, 0, 0)),
        "Movie" to Preset(listOf(4, 1, 0, 2, 3), boost = 2),
        "Music" to Preset(listOf(4, 2, -1, 2, 4)),
        "Voice / News" to Preset(listOf(-3, -1, 4, 4, 1), boost = 3),
        "Bass boost" to Preset(listOf(8, 4, 0, 0, 0)),
        "Night (soft)" to Preset(listOf(-4, -1, 2, 1, -2), boost = 0),
        "Loud" to Preset(listOf(2, 1, 1, 1, 2), boost = 8),
        "Custom" to Preset(listOf(0, 0, 0, 0, 0)),
    )

    private var eq: Equalizer? = null
    private var loud: LoudnessEnhancer? = null
    @Volatile var supported = false
        private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences("sound", Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = sp(ctx).getString("mode", "Standard")!!.takeIf { it in PRESETS } ?: "Standard"

    fun custom(ctx: Context): Preset = runCatching {
        val a = JSONArray(sp(ctx).getString("custom", "[0,0,0,0,0]")!!)
        Preset(List(5) { a.optInt(it) }, sp(ctx).getInt("boost", 0))
    }.getOrDefault(Preset(listOf(0, 0, 0, 0, 0)))

    fun setMode(ctx: Context, m: String) {
        if (m !in PRESETS) return
        sp(ctx).edit().putString("mode", m).apply()
        apply(ctx)
    }

    fun setCustom(ctx: Context, p: Preset) {
        val g = p.gains.take(5).map { it.coerceIn(-12, 12) }
        sp(ctx).edit().putString("mode", "Custom").putString("custom", JSONArray(g).toString())
            .putInt("boost", p.boost.coerceIn(0, 12)).apply()
        apply(ctx)
    }

    fun current(ctx: Context): Preset = mode(ctx).let { if (it == "Custom") custom(ctx) else PRESETS[it]!! }

    fun attach(ctx: Context, sessionId: Int) {
        release()
        if (sessionId == 0 || sessionId == -1) return
        try {
            eq = Equalizer(0, sessionId)
            loud = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
            supported = true
            apply(ctx)
        } catch (e: Exception) {
            Log.w("TvCast", "equalizer not available", e)
            supported = false
        }
    }

    fun apply(ctx: Context) {
        val e = eq ?: return
        val p = current(ctx)
        try {
            val bands = e.numberOfBands.toInt()
            val range = e.bandLevelRange // millibels
            for (b in 0 until bands) {
                // Map this band's centre frequency onto the 5 regions.
                val hz = e.getCenterFreq(b.toShort()) / 1000
                val region = when {
                    hz < 150 -> 0
                    hz < 500 -> 1
                    hz < 2000 -> 2
                    hz < 6000 -> 3
                    else -> 4
                }
                val mb = (p.gains[region] * 100).coerceIn(range[0].toInt(), range[1].toInt())
                e.setBandLevel(b.toShort(), mb.toShort())
            }
            e.enabled = p.gains.any { it != 0 }
            loud?.let {
                it.setTargetGain(p.boost * 100)
                it.enabled = p.boost > 0
            }
        } catch (ex: Exception) {
            Log.w("TvCast", "equalizer apply failed", ex)
        }
    }

    /** Same sound modes for the built-in VLC engine (its own 10-band equalizer; works for every audio format). */
    fun vlcEqualizer(ctx: Context): org.videolan.libvlc.MediaPlayer.Equalizer? {
        val p = current(ctx)
        if (p.gains.all { it == 0 } && p.boost == 0) return null
        val eq = org.videolan.libvlc.MediaPlayer.Equalizer.create()
        for (b in 0 until org.videolan.libvlc.MediaPlayer.Equalizer.getBandCount()) {
            val hz = org.videolan.libvlc.MediaPlayer.Equalizer.getBandFrequency(b)
            val region = when {
                hz < 150 -> 0
                hz < 500 -> 1
                hz < 2000 -> 2
                hz < 6000 -> 3
                else -> 4
            }
            eq.setAmp(b, p.gains[region].toFloat())
        }
        eq.setPreAmp(p.boost.toFloat())
        return eq
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { loud?.release() }
        eq = null
        loud = null
    }
}
