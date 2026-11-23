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
    val folder: String = "",
    val dateAdded: Long = 0,
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
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
        )
        ctx.contentResolver.query(videoCollection, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Media(id, videoUri(id), c.getString(1) ?: "video", c.getLong(2), c.getLong(3), true,
                    c.getString(4) ?: "Other", c.getLong(5))
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
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        ctx.contentResolver.query(imageCollection, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Media(id, imageUri(id), c.getString(1) ?: "picture", c.getLong(2), 0, false,
                    c.getString(3) ?: "Other", c.getLong(4))
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

    /** Subtitle extension (srt/vtt) for every video that has one next to it. Each folder is listed once. */
    @Suppress("DEPRECATION")
    fun subtitleExts(ctx: Context): Map<Long, String> {
        val out = HashMap<Long, String>()
        val dirCache = HashMap<String, List<File>>()
        ctx.contentResolver.query(
            videoCollection, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val f = File(c.getString(1) ?: continue)
                val dir = f.parent ?: continue
                val sibs = dirCache.getOrPut(dir) { f.parentFile?.listFiles()?.toList().orEmpty() }
                matchSubtitle(f, sibs)?.let { out[c.getLong(0)] = it.extension.lowercase() }
            }
        }
        return out
    }

    /** Whole library as JSON for the TV's browser. */
    fun libraryJson(ctx: Context): String {
        val subs = subtitleExts(ctx)
        val v = JSONArray()
        for (m in videos(ctx)) {
            val o = JSONObject().put("id", m.id).put("name", m.name).put("size", m.size)
                .put("dur", m.durationMs).put("key", m.key).put("folder", m.folder).put("date", m.dateAdded)
            subs[m.id]?.let { o.put("sub", it) }
            v.put(o)
        }
        val i = JSONArray()
        for (m in images(ctx)) i.put(
            JSONObject().put("id", m.id).put("name", m.name).put("size", m.size)
                .put("folder", m.folder).put("date", m.dateAdded)
        )
        return JSONObject().put("videos", v).put("images", i).toString()
    }

    /** APK files on the phone (Download, WhatsApp, etc.). Needs All-files access to see every folder. */
    fun apks(ctx: Context): List<ApkFile> {
        val out = mutableListOf<ApkFile>()
        val col = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        @Suppress("DEPRECATION")
        val proj = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.DATA)
        ctx.contentResolver.query(
            col, proj, "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("%.apk"),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(3) ?: continue
                val info = runCatching { ctx.packageManager.getPackageArchiveInfo(path, 0) }.getOrNull()
                info?.applicationInfo?.let { it.sourceDir = path; it.publicSourceDir = path }
                val label = info?.applicationInfo?.let { runCatching { ctx.packageManager.getApplicationLabel(it).toString() }.getOrNull() }
                out += ApkFile(
                    c.getString(0) ?: File(path).name, c.getLong(1), c.getLong(2) * 1000, path,
                    label, info?.packageName, info?.versionName,
                )
            }
        }
        return out
    }
}

data class ApkFile(
    val fileName: String, val size: Long, val modified: Long, val path: String,
    val appName: String?, val packageName: String?, val version: String?,
)
