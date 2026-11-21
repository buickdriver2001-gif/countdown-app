package com.gamehub.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlin.math.max

/**
 * The art on NEXUS Recent Apps cards, made from each app's own icon (NEXUS doesn't take pictures of your
 * screen). A soft wash of the icon's colours over the NEXUS dark blue, with a glow in the icon's main colour
 * where the icon sits, so every app's card has a look of its own. The icon itself is drawn sharp on top:
 * [ICON_SIZE] of the card's width across, centred at [ICON_Y] of its height.
 */
object CardArt {
    const val ICON_SIZE = 0.36f
    const val ICON_Y = 0.44f

    /** The art is small: it's all soft colour, and scales up smoothly to any card size. */
    private const val W = 96
    private val cache = LruCache<String, Bitmap>(30)

    /** The card art for [pkg], for a card [aspect] times as tall as it is wide. */
    fun get(pkg: String, icon: Drawable?, aspect: Float): Bitmap {
        val key = pkg + "|" + (aspect * 100).toInt()
        synchronized(cache) { cache.get(key) }?.let { return it }
        val b = try { make(icon, aspect) } catch (_: Throwable) { make(null, aspect) }
        synchronized(cache) { cache.put(key, b) }
        return b
    }

    /** Forgets [pkg]'s art (its icon may have changed with an update). */
    fun forget(pkg: String) {
        synchronized(cache) { cache.snapshot().keys.filter { it.startsWith("$pkg|") }.forEach { cache.remove(it) } }
    }

    /** The whole card as one picture, icon included, [w]×[h] (for the cover, which shrinks it into Recent Apps). */
    fun withIcon(pkg: String, icon: Drawable?, w: Int, h: Int): Bitmap? = try {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(get(pkg, icon, h.toFloat() / w), null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        copyOf(icon)?.let { drawIcon(c, it, w / 2f, h * ICON_Y, w * ICON_SIZE) }
        out
    } catch (_: Throwable) { null }

    /** Draws [icon] [s] across, centred on ([cx], [cy]). */
    fun drawIcon(c: Canvas, icon: Drawable, cx: Float, cy: Float, s: Float, alpha: Int = 255) {
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.alpha = alpha; icon.draw(c); icon.alpha = 255
    }

    /** A copy of the icon to draw with here (the original is drawn elsewhere at the same time). */
    private fun copyOf(d: Drawable?): Drawable? = d?.constantState?.newDrawable()?.mutate() ?: d

    private fun make(icon: Drawable?, aspect: Float): Bitmap {
        val w = W; val h = (W * aspect).toInt().coerceAtLeast(W)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(8, 12, 28))
        val ic = copyOf(icon)
        var accent = Color.rgb(80, 140, 255)
        if (ic != null) {
            // The icon's colours: drawn tiny, then stretched over the whole card, which blends them smoothly
            val tiny = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            ic.setBounds(0, 0, 4, 4); ic.draw(Canvas(tiny))
            c.drawBitmap(tiny, null, Rect(-w / 4, -h / 8, w + w / 4, h + h / 8), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 })
            tiny.recycle()
            accent = mainColour(ic)
        }
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // A glow in the icon's main colour, where the icon sits
        p.shader = RadialGradient(wf / 2f, hf * ICON_Y, wf * 0.85f,
            intArrayOf(withAlpha(accent, 190), withAlpha(accent, 70), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        // Deeper towards the bottom (and a touch at the top), NEXUS style
        p.shader = LinearGradient(0f, 0f, 0f, hf,
            intArrayOf(Color.argb(70, 4, 8, 20), Color.argb(10, 4, 8, 20), Color.argb(215, 4, 8, 20)), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        // A soft sheen across the top corner
        p.shader = LinearGradient(0f, 0f, wf * 0.6f, hf * 0.35f, Color.argb(34, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        return out
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** The icon's main colour: its most colourful pixels count most, then made bright enough to glow. */
    private fun mainColour(ic: Drawable): Int {
        val n = 24
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        ic.setBounds(0, 0, n, n); ic.draw(Canvas(b))
        val px = IntArray(n * n); b.getPixels(px, 0, n, 0, 0, n, n); b.recycle()
        var r = 0.0; var g = 0.0; var bl = 0.0; var wt = 0.0
        var ar = 0.0; var ag = 0.0; var ab = 0.0; var aw = 0.0
        val hsv = FloatArray(3)
        for (c in px) {
            val a = Color.alpha(c) / 255.0
            if (a < 0.3) continue
            Color.colorToHSV(c, hsv)
            val k = a * hsv[1] * hsv[1] * (0.3 + hsv[2])
            r += Color.red(c) * k; g += Color.green(c) * k; bl += Color.blue(c) * k; wt += k
            ar += Color.red(c) * a; ag += Color.green(c) * a; ab += Color.blue(c) * a; aw += a
        }
        val col = when {
            wt > 2.0 -> Color.rgb((r / wt).toInt(), (g / wt).toInt(), (bl / wt).toInt())
            aw > 0.0 -> Color.rgb((ar / aw).toInt(), (ag / aw).toInt(), (ab / aw).toInt())
            else -> return Color.rgb(80, 140, 255)
        }
        Color.colorToHSV(col, hsv)
        if (hsv[1] < 0.12f) { hsv[0] = 220f; hsv[1] = 0.35f } // black / white / grey icons: a pale NEXUS blue
        else hsv[1] = max(hsv[1], 0.5f)
        hsv[2] = hsv[2].coerceIn(0.6f, 0.95f)
        return Color.HSVToColor(hsv)
    }
}
