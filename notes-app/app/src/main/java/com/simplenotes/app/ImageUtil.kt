package com.simplenotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.max

/** Copies picked photos into the app (resized, upright) so notes keep them even if the original is deleted. */
object ImageUtil {
    private const val MAX_SIDE = 1600

    private fun dir(ctx: Context) = File(ctx.filesDir, "images").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    /** Returns the saved file name, or null if the picture couldn't be read. */
    fun import(ctx: Context, uri: Uri): String? {
        return try {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val rotation = cr.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            val scale = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
            val matrix = Matrix()
            if (scale < 1f) matrix.postScale(scale, scale)
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }

            val name = UUID.randomUUID().toString() + ".jpg"
            FileOutputStream(file(ctx, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            name
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Loads a saved picture scaled down to roughly [reqWidth] pixels wide. */
    fun load(ctx: Context, name: String, reqWidth: Int): Bitmap? {
        val f = file(ctx, name)
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun delete(ctx: Context, name: String) {
        file(ctx, name).delete()
    }

    private fun exifRotation(input: InputStream): Int =
        when (ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
