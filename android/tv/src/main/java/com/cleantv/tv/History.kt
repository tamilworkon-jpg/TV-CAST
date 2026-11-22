package com.cleantv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently played videos with last position, newest first.
 * Tiny by design: at most [MAX] entries, and anything not played for [KEEP_DAYS] days is forgotten.
 */
object History {
    private const val MAX = 50
    private const val KEEP_DAYS = 30
    private const val KEEP_MS = KEEP_DAYS * 24L * 60 * 60 * 1000

    private fun sp(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    private fun load(ctx: Context): JSONArray = try {
        JSONArray(sp(ctx).getString("items", "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    /** Returns entries newer than 30 days, dropping expired ones from storage. */
    @Synchronized
    fun all(ctx: Context): JSONArray {
        val old = load(ctx)
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optLong("t") >= cutoff && out.length() < MAX) out.put(o)
        }
        if (out.length() != old.length()) sp(ctx).edit().putString("items", out.toString()).apply()
        return out
    }

    @Synchronized
    fun save(ctx: Context, key: String, title: String, pos: Long, dur: Long) {
        if (key.isEmpty() || dur <= 0) return
        val old = all(ctx)
        val out = JSONArray()
        out.put(
            JSONObject().put("key", key).put("title", title).put("pos", pos).put("dur", dur)
                .put("t", System.currentTimeMillis())
        )
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optString("key") != key && out.length() < MAX) out.put(o)
        }
        sp(ctx).edit().putString("items", out.toString()).apply()
    }

    @Synchronized
    fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}

/** Wipes play history and any cached files (images, player temp data). */
fun clearAll(ctx: Context) {
    History.clear(ctx)
    ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
}
