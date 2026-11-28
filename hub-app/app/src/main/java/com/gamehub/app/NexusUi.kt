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

    /**
     * One row of a panel. [value]: what it's set to, shown on the right (a label written "Name:  value" is split the
     * same way); [more]: it opens another page (a › on the right); [sound]: it plays the tap sound (off for rows that
     * play a sound of their own). [setValue] changes the value shown while the panel stays open.
     */
    class Option(val label: String, val primary: Boolean = false, val danger: Boolean = false, val value: String? = null,
                 val more: Boolean = false, val sound: Boolean = true,
                 /** A short line under the name (what this choice is). */ val sub: String? = null, val action: () -> Unit) {
        internal var valueView: TextView? = null
        fun setValue(v: String) { valueView?.text = v }
        companion object {
            /** A small heading between groups of rows (not a button). */
            fun header(title: String) = Option(HEADER + title) {}
            internal const val HEADER = "\u0000"
        }
    }

    /**
     * A NEXUS screen with NEXUS's own Back / Home / Recent buttons along the bottom: panels over it show the same
     * buttons (working as they do on the screen itself) in place of the phone's own bar.
     */
    interface NavHost {
        fun navTick()
        fun navHome()
        fun navRecent()
    }

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
     * the page before. Tapping outside never closes a panel: it's closed with its own Back / Done row, or the phone's
     * Back (a tap outside only gives the panel a little nudge, so it's clear it's still open).
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
        // Everything under the title scrolls together, so a long page never pushes its choices out of reach
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (message != null) body.addView(TextView(a).apply {
            text = message; setTextColor(MUTED); textSize = 14f; typeface = light; setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (12 * d).toInt())
        })
        val field = if (input != null) EditText(a).apply {
            setText(input); setSelection(text.length); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(TEXT); setHintTextColor(MUTED); textSize = 16f; typeface = light
            background = glass(d, 6f)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        } else null
        if (field != null) body.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * d).toInt() })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val host0 = a as? NavHost
        for (o in options) {
            if (o.label.startsWith(Option.HEADER)) { list.addView(header(a, o.label.removePrefix(Option.HEADER))); continue }
            list.addView(row(a, o) { if (o.sound) host0?.navTick(); if (onSelect) dialog.dismiss(); o.action() })
        }
        if (cancel != null) list.addView(row(a, Option(if (back != null) "‹  $cancel" else cancel) {}, quiet = true) {
            host0?.navTick(); dialog.dismiss(); back?.invoke()
        })
        if (back != null) dialog.setOnKeyListener { _, code, e ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP) { dialog.dismiss(); back(); true }
            else code == android.view.KeyEvent.KEYCODE_BACK
        }
        body.addView(list)
        col.addView(ScrollView(a).apply { addView(body); isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Going from one page of a menu to the next: the backdrop stays as it is (no fading out and in again)
        val switching = System.currentTimeMillis() - lastClosedAt < 450
        val root = android.widget.FrameLayout(a)
        dialog.setCanceledOnTouchOutside(false)
        root.addView(CrystalBackdrop(a, if (switching) 1f else 0f) {
            col.animate().cancel()
            col.animate().scaleX(1.025f).scaleY(1.025f).setDuration(90).withEndAction {
                col.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
        },
            android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val pw = minOf(a.resources.displayMetrics.widthPixels - (40 * d).toInt(), (420 * d).toInt())
        // NEXUS's Back / Home / Recent buttons stay along the bottom, over the panel's backdrop
        val host = a as? NavHost
        val nav = if (host != null && android.os.Build.VERSION.SDK_INT >= 30 && NavBar.order(a) != "off") NavBar(a,
            onBack = { host.navTick(); if (back != null) { dialog.dismiss(); back() } else dialog.cancel() },
            onHome = { host.navHome() },
            onRecent = { host.navRecent() }) else null
        val navPx = if (nav != null) NavBar.heightPx(a) else 0
        root.addView(col, android.widget.FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = (28 * d).toInt(); bottomMargin = (28 * d).toInt() + navPx
        })
        if (nav != null && android.os.Build.VERSION.SDK_INT >= 30) {
            root.addView(nav, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, navPx, Gravity.BOTTOM))
            // (Out of the way while the keyboard is up, as the phone's own bar is)
            root.viewTreeObserver.addOnGlobalLayoutListener {
                val typing = try { root.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true } catch (_: Exception) { false }
                val v = if (typing) View.GONE else View.VISIBLE
                if (nav.visibility != v) nav.visibility = v
            }
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes?.windowAnimations = 0
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            (if (field != null) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0))
        // With NEXUS's buttons showing, the phone's own bars stay hidden (as on the screen underneath)
        if (nav != null && android.os.Build.VERSION.SDK_INT >= 30) try {
            dialog.window?.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (_: Exception) {}
        // The panel itself comes in with a quick fade and a slight grow
        col.alpha = 0f; col.scaleX = 0.96f; col.scaleY = 0.96f
        col.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(if (switching) 120 else 180)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        showing.add(dialog); shownOrder.add(java.lang.ref.WeakReference(dialog))
        dialog.setOnDismissListener {
            showing.remove(dialog); shownOrder.removeAll { it.get() == null || it.get() === dialog }
            lastClosedAt = System.currentTimeMillis(); onPanels?.invoke(a, anyShowing(a))
        }
        dialog.show()
        onPanels?.invoke(a, true)
        return field
    }

    private var lastClosedAt = 0L
    /** The crystals behind the panels (one set, so they carry on turning from one page of a menu to the next). */
    private var crystals: Crystals? = null

    /**
     * Behind a panel: the screen dims and glass crystals orbit slowly around the middle (PS2-style, an
     * original design). Tapping outside the panel doesn't close it (see [panel]).
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

    /** Panels in the order they opened (the last one showing is on top). */
    private val shownOrder = ArrayList<java.lang.ref.WeakReference<Dialog>>()

    /** The panel on top over [a], if one is open. */
    private fun topPanel(a: Activity): Dialog? = shownOrder.lastOrNull { it.get()?.let { d -> d.ownerActivity === a && d.isShowing } == true }?.get()

    private var noteView: java.lang.ref.WeakReference<View>? = null

    /**
     * A short note in the NEXUS style (instead of Android's grey pop-up): a glass pill that fades in just above the
     * buttons, on top of any open panel, and fades away by itself. Never takes touches. [long]: a longer note
     * that needs a little more time to read.
     */
    fun note(a: Activity, text: String, long: Boolean = false) {
        if (a.isFinishing || a.isDestroyed) {
            try { android.widget.Toast.makeText(a.applicationContext, text, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            return
        }
        val d = a.resources.displayMetrics.density
        // On the panel on top if one is open (a note on the screen underneath would be hidden behind it)
        val host = ((topPanel(a)?.window ?: a.window)?.decorView as? ViewGroup) ?: return
        noteView?.get()?.let { old -> old.animate().cancel(); (old.parent as? ViewGroup)?.removeView(old) }
        val maxW = minOf(a.resources.displayMetrics.widthPixels - (48 * d).toInt(), (420 * d).toInt())
        val tv = TextView(a).apply {
            this.text = text; setTextColor(TEXT); textSize = 14f; typeface = light; letterSpacing = 0.02f
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f); maxWidth = maxW
            background = glass(d, 20f, strong = true)
            setPadding((18 * d).toInt(), (11 * d).toInt(), (18 * d).toInt(), (11 * d).toInt())
            // Above everything on the screen (the buttons, All apps, the guide), without a shadow
            elevation = 60 * d; outlineProvider = null
            // (TalkBack reads it out)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isClickable = false; isFocusable = false
            alpha = 0f; translationY = 8 * d
        }
        // Clear of the buttons along the bottom and the bars just above them (the home screen's, All apps' Add bar)
        val bottom = NavBar.heightPx(a) + (96 * d).toInt()
        host.addView(tv, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = bottom })
        noteView = java.lang.ref.WeakReference(tv)
        tv.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        tv.postDelayed({
            tv.animate().alpha(0f).translationY(4 * d).setDuration(220).withEndAction { (tv.parent as? ViewGroup)?.removeView(tv) }.start()
        }, if (long) 4200L else 2400L)
    }

    /** Closes every panel [a] has open. Returns true if there were any. */
    fun dismissAll(a: Activity): Boolean {
        val mine = showing.filter { it.ownerActivity === a && it.isShowing }
        for (dlg in mine) try { dlg.dismiss() } catch (_: Exception) {}
        return mine.isNotEmpty()
    }

    /** A group heading: small, spaced-out capitals in a soft blue, with a little room above it. */
    private fun header(a: Activity, title: String): View {
        val d = a.resources.displayMetrics.density
        return TextView(a).apply {
            text = title.uppercase(); textSize = 11f; typeface = light; letterSpacing = 0.22f
            setTextColor(Color.argb(200, 140, 190, 255))
            setPadding((4 * d).toInt(), (14 * d).toInt(), 0, (2 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
    }

    /** A row: its name on the left, and what it's set to (or a › for another page) on the right. */
    private fun row(a: Activity, o: Option, quiet: Boolean = false, click: () -> Unit): View {
        val d = a.resources.displayMetrics.density
        // "Name:  value" written in one label: shown as name and value
        val split = o.value == null && o.label.contains(":  ")
        val name = if (split) o.label.substringBefore(":  ") else o.label
        val value = if (split) o.label.substringAfter(":  ") else o.value
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), 0, (12 * d).toInt(), 0)
            minimumHeight = (48 * d).toInt()
            background = if (o.primary) glass(d, 6f, strong = true) else if (quiet) null else glass(d, 6f)
            isClickable = true
            setOnClickListener { click() }
            contentDescription = (if (value.isNullOrEmpty()) name else "$name, $value") + (if (o.sub.isNullOrEmpty()) "" else ". ${o.sub}")
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() }
        }
        val left = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        left.addView(TextView(a).apply {
            text = name
            textSize = 15f; typeface = light; letterSpacing = 0.06f
            setTextColor(when { o.danger -> Color.rgb(255, 150, 130); quiet -> MUTED; else -> TEXT })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        if (!o.sub.isNullOrEmpty()) left.addView(TextView(a).apply {
            text = o.sub
            textSize = 12.5f; typeface = light; letterSpacing = 0.02f; setLineSpacing(0f, 1.1f)
            setTextColor(MUTED); setPadding(0, (3 * d).toInt(), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        box.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = TextView(a).apply {
            text = value ?: ""
            textSize = 14f; typeface = light; letterSpacing = 0.04f
            setTextColor(BLUE); gravity = Gravity.END
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (value == null && !o.more) View.GONE else View.VISIBLE
        }
        o.valueView = v
        box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
        })
        if (o.more) box.addView(TextView(a).apply {
            text = "›"; textSize = 20f; typeface = light; setTextColor(MUTED)
            setPadding((10 * d).toInt(), 0, 0, (2 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return box
    }
}
