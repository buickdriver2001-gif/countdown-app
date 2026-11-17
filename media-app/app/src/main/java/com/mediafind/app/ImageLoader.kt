package com.mediafind.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Downloads pictures in the background, shrinks them to fit, and keeps recent ones in memory. */
object ImageLoader {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, url: String, targetPx: Int, onDone: ((Boolean) -> Unit)? = null) {
        val key = "$url@$targetPx"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); onDone?.invoke(true); return }
        view.setImageDrawable(null)
        pool.execute {
            val bmp = fetch(url, targetPx)
            if (bmp != null) cache.put(key, bmp)
            main.post {
                if (view.tag == key) {
                    if (bmp != null) view.setImageBitmap(bmp)
                    onDone?.invoke(bmp != null)
                }
            }
        }
    }

    private fun fetch(url: String, targetPx: Int): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", RedditClient.USER_AGENT)
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx / 2) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
