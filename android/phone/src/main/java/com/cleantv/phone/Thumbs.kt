package com.cleantv.phone

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import java.io.ByteArrayOutputStream

/** Video thumbnails that show a real scene (used by the phone's lists and sent to the TV). */
object Thumbs {
    /**
     * Films start with black frames (fade-in, studio logos), and Android's own thumbnail is taken
     * from the start, so it comes out black. Take a frame from ~10% in instead, skipping dark ones,
     * and keep it in the phone's cache so it's made only once.
     */
    @Synchronized
    fun videoJpeg(ctx: Context, id: Long): ByteArray {
        val dir = java.io.File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val file = java.io.File(dir, "v$id.jpg")
        if (file.length() > 0) return file.readBytes()
        val bmp = runCatching { middleFrame(ctx, MediaRepo.videoUri(id)) }.getOrNull()
            ?: ctx.contentResolver.loadThumbnail(MediaRepo.videoUri(id), Size(400, 400), null)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        runCatching { file.writeBytes(bytes) }
        return bytes
    }

    private fun middleFrame(ctx: Context, uri: Uri): Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                r.setDataSource(pfd.fileDescriptor)
                val durUs = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                var best: Bitmap? = null
                for (f in listOf(0.10, 0.25, 0.40, 0.03)) {
                    val t = if (durUs > 0) (durUs * f).toLong() else 5_000_000L
                    val frame = r.getScaledFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 400, 400) ?: continue
                    if (brightness(frame) > 28) { best?.recycle(); return frame }
                    if (best == null) best = frame else frame.recycle()
                }
                return best
            }
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Average brightness 0–255 from a sparse grid of pixels. */
    private fun brightness(b: Bitmap): Int {
        var sum = 0L
        var n = 0
        val stepX = maxOf(1, b.width / 16)
        val stepY = maxOf(1, b.height / 16)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

}
