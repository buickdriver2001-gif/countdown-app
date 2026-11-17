package com.zombiedots.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.random.Random

/**
 * Draws a random city in the style of a dark-mode street map: blocks, a road grid,
 * parks and sometimes a river. Purely scenery; nothing on it blocks movement.
 */
object CityMap {

    fun render(width: Int, height: Int, density: Float, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val rnd = Random(seed)
        val d = density
        val w = width.toFloat()
        val h = height.toFloat()

        // Land
        c.drawColor(Color.parseColor("#1B1F26"))

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }

        // Minor streets: a tight grid with a little jitter
        val minorGap = (55f + rnd.nextFloat() * 20f) * d
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var x = rnd.nextFloat() * minorGap
        while (x < w) { xs.add(x); x += minorGap * (0.8f + rnd.nextFloat() * 0.4f) }
        var y = rnd.nextFloat() * minorGap
        while (y < h) { ys.add(y); y += minorGap * (0.8f + rnd.nextFloat() * 0.4f) }

        // Building footprints inside each block
        val building = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        val bx = listOf(0f) + xs + listOf(w)
        val by = listOf(0f) + ys + listOf(h)
        for (i in 0 until bx.size - 1) for (j in 0 until by.size - 1) {
            val l = bx[i] + 5 * d; val r = bx[i + 1] - 5 * d
            val t = by[j] + 5 * d; val b = by[j + 1] - 5 * d
            if (r - l < 10 * d || b - t < 10 * d) continue
            val count = 2 + rnd.nextInt(4)
            repeat(count) {
                val bw = (r - l) * (0.25f + rnd.nextFloat() * 0.45f)
                val bh = (b - t) * (0.25f + rnd.nextFloat() * 0.45f)
                val ox = l + rnd.nextFloat() * (r - l - bw)
                val oy = t + rnd.nextFloat() * (b - t - bh)
                rect.set(ox, oy, ox + bw, oy + bh)
                building.color = if (rnd.nextBoolean()) Color.parseColor("#232831") else Color.parseColor("#262B35")
                c.drawRoundRect(rect, 2 * d, 2 * d, building)
            }
        }

        // Parks
        fill.color = Color.parseColor("#1D3527")
        repeat(1 + rnd.nextInt(3)) {
            if (bx.size < 3 || by.size < 3) return@repeat
            val i = rnd.nextInt(bx.size - 2)
            val j = rnd.nextInt(by.size - 2)
            val spanX = 1 + rnd.nextInt(2)
            val spanY = 1 + rnd.nextInt(2)
            rect.set(bx[i] + 3 * d, by[j] + 3 * d,
                bx[(i + spanX).coerceAtMost(bx.size - 1)] - 3 * d, by[(j + spanY).coerceAtMost(by.size - 1)] - 3 * d)
            c.drawRoundRect(rect, 6 * d, 6 * d, fill)
            // a winding path through the park
            stroke.color = Color.parseColor("#2A4A36"); stroke.strokeWidth = 2 * d
            val p = Path()
            p.moveTo(rect.left, rect.centerY())
            p.quadTo(rect.centerX(), rect.top + rect.height() * rnd.nextFloat(), rect.right, rect.centerY())
            c.drawPath(p, stroke)
        }

        // River (sometimes)
        if (rnd.nextFloat() < 0.6f) {
            val river = Path()
            val vertical = rnd.nextBoolean()
            if (vertical) {
                val x0 = w * (0.2f + rnd.nextFloat() * 0.6f)
                river.moveTo(x0, -20 * d)
                river.cubicTo(x0 + (rnd.nextFloat() - 0.5f) * w * 0.6f, h * 0.33f,
                    x0 + (rnd.nextFloat() - 0.5f) * w * 0.6f, h * 0.66f,
                    x0 + (rnd.nextFloat() - 0.5f) * w * 0.3f, h + 20 * d)
            } else {
                val y0 = h * (0.2f + rnd.nextFloat() * 0.6f)
                river.moveTo(-20 * d, y0)
                river.cubicTo(w * 0.33f, y0 + (rnd.nextFloat() - 0.5f) * h * 0.3f,
                    w * 0.66f, y0 + (rnd.nextFloat() - 0.5f) * h * 0.3f,
                    w + 20 * d, y0 + (rnd.nextFloat() - 0.5f) * h * 0.2f)
            }
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.color = Color.parseColor("#1A2D44"); stroke.strokeWidth = (46f + rnd.nextFloat() * 20f) * d
            c.drawPath(river, stroke)
            stroke.color = Color.parseColor("#17283D"); stroke.strokeWidth *= 0.7f
            c.drawPath(river, stroke)
            stroke.strokeCap = Paint.Cap.BUTT
        }

        // Minor streets on top
        stroke.color = Color.parseColor("#2F3540"); stroke.strokeWidth = 4.5f * d
        for (sx in xs) c.drawLine(sx, 0f, sx, h, stroke)
        for (sy in ys) c.drawLine(0f, sy, w, sy, stroke)

        // A few major roads (wider, with a center line) and maybe a diagonal avenue
        val major = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#454D5B"); strokeWidth = 9 * d }
        val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#5E5A48"); strokeWidth = 1.2f * d }
        val majorXs = xs.filterIndexed { i, _ -> i % 3 == 1 }
        val majorYs = ys.filterIndexed { i, _ -> i % 3 == 1 }
        for (mx in majorXs) { c.drawLine(mx, 0f, mx, h, major); c.drawLine(mx, 0f, mx, h, center) }
        for (my in majorYs) { c.drawLine(0f, my, w, my, major); c.drawLine(0f, my, w, my, center) }
        if (rnd.nextFloat() < 0.5f) {
            val fromLeft = rnd.nextBoolean()
            val y1 = h * rnd.nextFloat() * 0.4f
            val y2 = h * (0.6f + rnd.nextFloat() * 0.4f)
            if (fromLeft) { c.drawLine(0f, y1, w, y2, major); c.drawLine(0f, y1, w, y2, center) }
            else { c.drawLine(w, y1, 0f, y2, major); c.drawLine(w, y1, 0f, y2, center) }
        }

        // Darken slightly so the dots stay easy to see
        c.drawColor(Color.argb(60, 0, 0, 0))
        return bmp
    }
}
