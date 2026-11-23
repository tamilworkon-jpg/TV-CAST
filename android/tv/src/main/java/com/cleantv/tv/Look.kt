package com.cleantv.tv

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.json.JSONObject

/**
 * Picture modes (Movie, Vivid, News …) applied as a colour matrix to the video and pictures.
 * Custom mode has brightness / contrast / saturation / warmth sliders. Remembered on the TV.
 */
object Look {
    data class Params(val brightness: Int = 0, val contrast: Int = 100, val saturation: Int = 100, val warmth: Int = 0)

    val PRESETS = linkedMapOf(
        "Standard" to Params(),
        "Movie" to Params(brightness = -6, contrast = 112, saturation = 105, warmth = 12),
        "Vivid" to Params(brightness = 4, contrast = 118, saturation = 145, warmth = 0),
        "News" to Params(brightness = 6, contrast = 105, saturation = 95, warmth = -4),
        "High contrast" to Params(brightness = 0, contrast = 135, saturation = 110, warmth = 0),
        "Sports" to Params(brightness = 8, contrast = 115, saturation = 130, warmth = -6),
        "Warm" to Params(warmth = 30),
        "Cool" to Params(warmth = -30),
        "Black & white" to Params(saturation = 0, contrast = 110),
        "Custom" to Params(),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("look", Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = sp(ctx).getString("mode", "Standard")!!.takeIf { it in PRESETS } ?: "Standard"

    fun custom(ctx: Context): Params = runCatching {
        val j = JSONObject(sp(ctx).getString("custom", "{}")!!)
        Params(j.optInt("b", 0), j.optInt("c", 100), j.optInt("s", 100), j.optInt("w", 0))
    }.getOrDefault(Params())

    fun setMode(ctx: Context, m: String) { if (m in PRESETS) sp(ctx).edit().putString("mode", m).apply() }

    fun setCustom(ctx: Context, p: Params) {
        val c = Params(p.brightness.coerceIn(-50, 50), p.contrast.coerceIn(50, 150), p.saturation.coerceIn(0, 200), p.warmth.coerceIn(-50, 50))
        sp(ctx).edit().putString("mode", "Custom")
            .putString("custom", JSONObject().put("b", c.brightness).put("c", c.contrast).put("s", c.saturation).put("w", c.warmth).toString())
            .apply()
    }

    fun current(ctx: Context): Params = mode(ctx).let { if (it == "Custom") custom(ctx) else PRESETS[it]!! }

    /** null = no filter needed (Standard). */
    fun filter(ctx: Context): ColorMatrixColorFilter? {
        val p = current(ctx)
        if (p == Params()) return null
        val m = ColorMatrix().apply { setSaturation(p.saturation / 100f) }
        val c = p.contrast / 100f
        val t = (1f - c) * 128f + p.brightness * 2.55f
        m.postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )))
        if (p.warmth != 0) {
            val w = p.warmth / 100f
            m.postConcat(ColorMatrix(floatArrayOf(
                1f + w * 0.25f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f - w * 0.25f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        return ColorMatrixColorFilter(m)
    }
}
