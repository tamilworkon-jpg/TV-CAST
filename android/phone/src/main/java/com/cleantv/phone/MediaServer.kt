package com.cleantv.phone

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import android.os.ParcelFileDescriptor

/** Serves phone media to the TV over HTTP with byte-range support (needed for seeking). */
class MediaServer(private val ctx: Context, private val token: String, private val onBye: () -> Unit) : NanoHTTPD(PORT) {
    @Volatile var lastHit = System.currentTimeMillis()

    override fun serve(session: IHTTPSession): Response {
        val all = session.uri.trim('/').split('/')
        if (all.firstOrNull() == "ping") return newFixedLengthResponse("ok")
        // Every media URL starts with a per-install secret so other devices on the Wi-Fi can't browse the phone.
        if (all.firstOrNull() != token) return notFound()
        // …and only the TV this phone is paired with may fetch anything.
        val tvIp = Prefs.lastTv(ctx)?.ip
        if (tvIp == null || session.remoteIpAddress != tvIp) return notFound()
        lastHit = System.currentTimeMillis()
        val parts = all.drop(1)
        return try {
            val id = parts.getOrNull(1)?.substringBefore('.')?.toLongOrNull()
            when (parts.getOrNull(0)) {
                "list" -> newFixedLengthResponse(Response.Status.OK, "application/json", MediaRepo.libraryJson(ctx))
                "alive" -> newFixedLengthResponse("ok")
                "bye" -> {
                    lastHit = 0
                    onBye()
                    newFixedLengthResponse("ok")
                }
                "t" -> serveThumb(parts.getOrNull(1), parts.getOrNull(2)?.toLongOrNull())
                "v" -> if (id == null) notFound() else serveUri(session, MediaRepo.videoUri(id))
                "i" -> if (id == null) notFound() else serveUri(session, MediaRepo.imageUri(id))
                "s" -> if (id == null) notFound() else serveSubtitle(id)
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error")
        }
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")

    /** Small JPEG preview for the TV browser, from MediaStore's own thumbnail cache. */
    private fun serveThumb(kind: String?, id: Long?): Response {
        if (id == null) return notFound()
        val uri = if (kind == "v") MediaRepo.videoUri(id) else MediaRepo.imageUri(id)
        val bmp = ctx.contentResolver.loadThumbnail(uri, Size(400, 400), null)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun serveUri(session: IHTTPSession, uri: Uri): Response {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return notFound()
        val size = pfd.statSize
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"

        var start = 0L
        var end = size - 1
        val range = session.headers["range"]
        val partial = range != null && range.startsWith("bytes=")
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',').split('-')
            if (spec[0].isEmpty()) {
                start = maxOf(0L, size - (spec.getOrNull(1)?.toLongOrNull() ?: 0L))
            } else {
                start = spec[0].toLong()
                spec.getOrNull(1)?.toLongOrNull()?.let { end = minOf(it, size - 1) }
            }
            if (start >= size || start > end) {
                pfd.close()
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */$size") }
            }
        }

        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        stream.channel.position(start)
        val length = end - start + 1
        val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        return newFixedLengthResponse(status, mime, stream, length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (partial) addHeader("Content-Range", "bytes $start-$end/$size")
        }
    }

    private fun serveSubtitle(videoId: Long): Response {
        val f = MediaRepo.findSubtitle(ctx, videoId) ?: return notFound()
        val mime = if (f.extension.equals("vtt", true)) "text/vtt" else "application/x-subrip"
        return newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", FileInputStream(f), f.length())
    }

    companion object {
        const val PORT = 8090
    }
}
