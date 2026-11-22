package com.cleantv.phone

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Media(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val isVideo: Boolean,
) {
    /** Stable identity across MediaStore rescans; used for resume history. */
    val key: String get() = "$name|$size"
}

object MediaRepo {
    private val videoCollection: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val imageCollection: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun videoUri(id: Long): Uri = ContentUris.withAppendedId(videoCollection, id)
    fun imageUri(id: Long): Uri = ContentUris.withAppendedId(imageCollection, id)

    fun videos(ctx: Context): List<Media> {
        val out = mutableListOf<Media>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
        )
        ctx.contentResolver.query(videoCollection, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Media(id, videoUri(id), c.getString(1) ?: "video", c.getLong(2), c.getLong(3), true)
            }
        }
        return out
    }

    fun images(ctx: Context): List<Media> {
        val out = mutableListOf<Media>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
        )
        ctx.contentResolver.query(imageCollection, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Media(id, imageUri(id), c.getString(1) ?: "picture", c.getLong(2), 0, false)
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun videoPath(ctx: Context, id: Long): String? =
        ctx.contentResolver.query(videoUri(id), arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Finds "Movie.srt" / "Movie.en.srt" / "Movie.vtt" next to "Movie.mkv". Needs All-files access. */
    fun findSubtitle(ctx: Context, videoId: Long): File? {
        val path = videoPath(ctx, videoId) ?: return null
        return matchSubtitle(File(path), File(path).parentFile?.listFiles()?.toList().orEmpty())
    }

    private fun matchSubtitle(video: File, siblings: List<File>): File? {
        val base = video.nameWithoutExtension.lowercase()
        return siblings.filter {
            val n = it.name.lowercase()
            (n.endsWith(".srt") || n.endsWith(".vtt")) && n.startsWith(base)
        }.minByOrNull { it.name.length }
    }

    /** Whole library as JSON for the TV's browser. Each folder is listed once to find subtitles. */
    @Suppress("DEPRECATION")
    fun libraryJson(ctx: Context): String {
        val paths = HashMap<Long, String>()
        ctx.contentResolver.query(
            videoCollection, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA), null, null, null
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { paths[c.getLong(0)] = it } }
        val dirCache = HashMap<String, List<File>>()

        val v = JSONArray()
        for (m in videos(ctx)) {
            val o = JSONObject().put("id", m.id).put("name", m.name).put("size", m.size)
                .put("dur", m.durationMs).put("key", m.key)
            paths[m.id]?.let { p ->
                val f = File(p)
                val dir = f.parent ?: return@let
                val sibs = dirCache.getOrPut(dir) { f.parentFile?.listFiles()?.toList().orEmpty() }
                matchSubtitle(f, sibs)?.let { o.put("sub", it.extension.lowercase()) }
            }
            v.put(o)
        }
        val i = JSONArray()
        for (m in images(ctx)) i.put(JSONObject().put("id", m.id).put("name", m.name))
        return JSONObject().put("videos", v).put("images", i).toString()
    }
}
