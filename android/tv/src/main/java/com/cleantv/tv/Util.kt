package com.cleantv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import coil.ImageLoader
import coil.memory.MemoryCache

/** Other video players installed on the TV, as (package, label). */
fun externalPlayers(ctx: Context): List<Pair<String, String>> {
    val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://localhost/x.mkv"), "video/*")
    val pm = ctx.packageManager
    return pm.queryIntentActivities(probe, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != ctx.packageName }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}

private var loader: ImageLoader? = null

/**
 * Pictures are streamed from the phone each time; nothing is written to the TV's small disk.
 * The TV has only 1 GB of RAM, so only a few decoded pictures are kept in memory.
 */
fun imageLoader(ctx: Context): ImageLoader = loader ?: ImageLoader.Builder(ctx.applicationContext)
    .diskCache(null)
    .memoryCache { MemoryCache.Builder(ctx.applicationContext).maxSizePercent(0.08).build() }
    .build()
    .also { loader = it }

/** Frees decoded pictures when Android says memory is low. */
fun trimImages() { loader?.memoryCache?.clear() }
