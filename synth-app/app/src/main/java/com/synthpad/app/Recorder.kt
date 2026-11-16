package com.synthpad.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Captures the synth's output. The audio thread hands over small chunks; a background
 * thread writes them to a temp file so playback never stutters.
 */
class Recorder(val file: File, val sampleRate: Int) {
    private val queue = ConcurrentLinkedQueue<ShortArray>()
    @Volatile private var active = true
    @Volatile var frames = 0L
        private set

    private val writer = Thread({
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            var bytes = ByteArray(0)
            while (true) {
                val chunk = queue.poll()
                if (chunk == null) {
                    if (!active) break
                    Thread.sleep(10)
                    continue
                }
                if (bytes.size != chunk.size * 2) bytes = ByteArray(chunk.size * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk)
                out.write(bytes)
            }
        }
    }, "synth-recorder").also { it.start() }

    /** Called from the audio thread with interleaved stereo floats. */
    fun write(buf: FloatArray, frameCount: Int) {
        if (!active) return
        val s = ShortArray(frameCount * 2)
        for (i in s.indices) {
            val v = buf[i].coerceIn(-1f, 1f)
            s[i] = (v * 32767f).toInt().toShort()
        }
        queue.add(s)
        frames += frameCount
    }

    /** Stops recording and waits for everything to be written. */
    fun finish() {
        active = false
        writer.join()
    }

    val seconds: Float get() = frames.toFloat() / sampleRate
}

/** Saved recordings live in the phone's Music/SynthPad folder as .wav files. */
object WavStore {
    private const val FOLDER = "SynthPad"
    private val collection: Uri get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    class Item(val uri: Uri, val name: String, val seconds: Long)

    fun writeHeader(out: OutputStream, dataBytes: Long, sampleRate: Int) {
        val channels = 2
        val bits = 16
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * channels * bits / 8)
        b.putShort((channels * bits / 8).toShort())
        b.putShort(bits.toShort())
        b.put("data".toByteArray())
        b.putInt(dataBytes.toInt())
        out.write(b.array())
    }

    /** Turns a finished raw recording into a .wav in Music/SynthPad. Returns false on failure. */
    fun save(ctx: Context, raw: File, sampleRate: Int, name: String): Boolean {
        val cr = ctx.contentResolver
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Recording" }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, values) ?: return false
        return try {
            cr.openOutputStream(uri)?.use { out ->
                writeHeader(out, raw.length(), sampleRate)
                FileInputStream(raw).use { it.copyTo(out, 1 shl 16) }
            } ?: throw IllegalStateException("no stream")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            false
        } finally {
            raw.delete()
        }
    }

    fun list(ctx: Context, sampleRate: Int): List<Item> {
        val out = ArrayList<Item>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        try {
            ctx.contentResolver.query(
                collection, projection,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_MUSIC}/$FOLDER%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val dur = c.getLong(durCol)
                    val secs = if (dur > 0) dur / 1000 else (c.getLong(sizeCol) - 44) / (sampleRate * 4L)
                    out.add(
                        Item(
                            ContentUris.withAppendedId(collection, id),
                            c.getString(nameCol).removeSuffix(".wav"),
                            secs.coerceAtLeast(0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Nothing saved yet, or storage not available
        }
        return out
    }
}
