package com.gamehub.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** NEXUS-styled pop-up panels: dark glass, thin blue edges, light type. Replaces the default grey dialogs. */
object NexusUi {
    val BLUE = Color.rgb(140, 190, 255)
    val TEXT = Color.rgb(235, 242, 255)
    val MUTED = Color.rgb(160, 185, 230)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun glass(d: Float, radius: Float = 8f, strong: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius * d
        // (A little see-through, so the crystals behind a panel show faintly through it)
        colors = intArrayOf(Color.argb(if (strong) 212 else 205, 10, 18, 44), Color.argb(if (strong) 212 else 205, 4, 8, 22))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(if (strong) 200 else 110, 140, 190, 255))
    }

    class Option(val label: String, val primary: Boolean = false, val danger: Boolean = false, val action: () -> Unit)

    /**
     * A panel with a title, optional message, optional text field, and a column of options.
     * Returns the text field (if asked for) so the caller can read it in an option's action.
     */
    /** Told whenever panels open or close over an activity (true: one is open), e.g. so the home screen can show its crystals. */
    var onPanels: ((Activity, Boolean) -> Unit)? = null

    /** Is a panel open over [a] right now? */
    fun anyShowing(a: Activity): Boolean = showing.any { it.ownerActivity === a && it.isShowing }

    /**
     * [back]: this panel is a page of a bigger menu: its last row (named [cancel]) and the phone's Back go back to
     * the page before (tapping outside still closes it).
     */
    fun panel(a: Activity, title: String, message: String? = null, options: List<Option>, cancel: String? = "Cancel",
              input: String? = null, onSelect: Boolean = true, back: (() -> Unit)? = null): EditText? {
        val d = a.resources.displayMetrics.density
        // A full-screen layer: a dimmed backdrop with the crystals turning on it, and the panel in the middle
        val dialog = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(d, 10f, strong = true)
            setPadding((22 * d).toInt(), (20 * d).toInt(), (22 * d).toInt(), (14 * d).toInt())
        }
        col.addView(TextView(a).apply {
            text = title.uppercase(); setTextColor(TEXT); textSize = 16f; letterSpacing = 0.18f; typeface = light
        })
        col.addView(View(a).apply { setBackgroundColor(Color.argb(150, 140, 190, 255)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)).apply { topMargin = (10 * d).toInt(); bottomMargin = (12 * d).toInt() })
        if (message != null) col.addView(TextView(a).apply {
            text = message; setTextColor(MUTED); textSize = 14f; typeface = light; setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (12 * d).toInt())
        })
        val field = if (input != null) EditText(a).apply {
            setText(input); setSelection(text.length); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(TEXT); setHintTextColor(MUTED); textSize = 16f; typeface = light
            background = glass(d, 6f)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        } else null
        if (field != null) col.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * d).toInt() })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (o in options) list.addView(row(a, o.label, o.primary, o.danger) { if (onSelect) dialog.dismiss(); o.action() })
        if (cancel != null) list.addView(row(a, if (back != null) "‹  $cancel" else cancel, false, false, quiet = true) { dialog.dismiss(); back?.invoke() })
        if (back != null) dialog.setOnKeyListener { _, code, e ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP) { dialog.dismiss(); back(); true }
            else code == android.view.KeyEvent.KEYCODE_BACK
        }
        col.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Going from one page of a menu to the next: the backdrop stays as it is (no fading out and in again)
        val switching = System.currentTimeMillis() - lastClosedAt < 450
        val root = android.widget.FrameLayout(a)
        root.addView(CrystalBackdrop(a, if (switching) 1f else 0f) { dialog.cancel() },
            android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val pw = minOf(a.resources.displayMetrics.widthPixels - (40 * d).toInt(), (420 * d).toInt())
        root.addView(col, android.widget.FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = (28 * d).toInt(); bottomMargin = (28 * d).toInt()
        })
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes?.windowAnimations = 0
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            (if (field != null) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0))
        // The panel itself comes in with a quick fade and a slight grow
        col.alpha = 0f; col.scaleX = 0.96f; col.scaleY = 0.96f
        col.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(if (switching) 120 else 180)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        showing.add(dialog)
        dialog.setOnDismissListener { showing.remove(dialog); lastClosedAt = System.currentTimeMillis(); onPanels?.invoke(a, anyShowing(a)) }
        dialog.show()
        onPanels?.invoke(a, true)
        return field
    }

    private var lastClosedAt = 0L
    /** The crystals behind the panels (one set, so they carry on turning from one page of a menu to the next). */
    private var crystals: Crystals? = null

    /**
     * Behind a panel: the screen dims and glass crystals orbit slowly around the middle (PS2-style, an
     * original design). Tapping anywhere outside the panel closes it.
     */
    private class CrystalBackdrop(c: android.content.Context, startK: Float, private val onTap: () -> Unit) : View(c) {
        private var k = startK
        private var last = 0L
        private val dim = android.graphics.Paint()
        override fun onDraw(cv: android.graphics.Canvas) {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0f else minOf(0.05f, (now - last) / 1e9f)
            last = now
            k = minOf(1f, k + dt * 4f)
            dim.color = Color.argb((120 * k).toInt(), 0, 2, 8)
            cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val cr = crystals ?: Crystals(context.applicationContext).also { crystals = it }
            cr.draw(cv, width, height, dt, k)
            postInvalidateOnAnimation()
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP) onTap()
            return true
        }
    }

    /** Panels on screen right now (so pressing Home can tidy them away, like other launchers do). */
    private val showing = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Dialog, Boolean>())

    /** Closes every panel [a] has open. Returns true if there were any. */
    fun dismissAll(a: Activity): Boolean {
        val mine = showing.filter { it.ownerActivity === a && it.isShowing }
        for (dlg in mine) try { dlg.dismiss() } catch (_: Exception) {}
        return mine.isNotEmpty()
    }

    private fun row(a: Activity, label: String, primary: Boolean, danger: Boolean, quiet: Boolean = false, click: () -> Unit): View {
        val d = a.resources.displayMetrics.density
        return TextView(a).apply {
            text = label
            textSize = 15f; typeface = light; letterSpacing = 0.06f
            setTextColor(when { danger -> Color.rgb(255, 150, 130); quiet -> MUTED; else -> TEXT })
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            minHeight = (48 * d).toInt()
            background = if (primary) glass(d, 6f, strong = true) else if (quiet) null else glass(d, 6f)
            isClickable = true
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() }
        }
    }
}
