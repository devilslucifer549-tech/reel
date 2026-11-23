package com.example.handscroll

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/** Gets the MediaPipe hand model: from app assets if bundled, otherwise downloaded once. */
object ModelManager {
    private const val FILE = "hand_landmarker.task"
    private const val URL_STR =
        "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
    private const val MIN_BYTES = 1_000_000L

    private fun localFile(ctx: Context) = File(ctx.filesDir, FILE)

    private fun hasLocal(ctx: Context) = localFile(ctx).let { it.exists() && it.length() > MIN_BYTES }

    private fun hasAsset(ctx: Context) = try {
        ctx.assets.open(FILE).close(); true
    } catch (e: IOException) { false }

    fun isAvailable(ctx: Context) = hasLocal(ctx) || hasAsset(ctx)

    fun baseOptions(ctx: Context): BaseOptions =
        if (hasLocal(ctx)) {
            val bytes = localFile(ctx).readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.rewind()
            BaseOptions.builder().setModelAssetBuffer(buf).build()
        } else {
            BaseOptions.builder().setModelAssetPath(FILE).build()
        }

    /** Blocking – call from a background thread. */
    @Throws(IOException::class)
    fun download(ctx: Context, onProgress: (Int) -> Unit) {
        val dest = localFile(ctx)
        val tmp = File(dest.path + ".tmp")
        val conn = URL(URL_STR).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            val total = conn.contentLength
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() < MIN_BYTES) {
            tmp.delete()
            throw IOException("Download incomplete")
        }
        if (!tmp.renameTo(dest)) throw IOException("Could not save model")
    }
}
