package com.gamehub.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * NEXUS's own notification panel. Swipe down from the top of the NEXUS home screen and it comes down with your
 * finger (a tap on the top bar brings it down too). At the top: the time and date, and shortcuts (Wi-Fi,
 * Bluetooth, the flashlight, the phone's quick settings, Settings). Below: every notification, in the order the
 * phone's own shade lists them, with the app, when, what it says and its buttons. Tap one to open it, swipe it
 * sideways to clear it, or Clear all. It goes back up with a swipe up, the handle, Back or Home.
 *
 * On the NEXUS home screen it lives in the home screen itself (and follows your finger); in apps, NEXUS's helper
 * shows it in its own layer when the phone's notification shade starts to come down (see NexusAccessibility).
 * Only with Notification access, only while the phone is unlocked: it never sits over the lock screen.
 * [bottomSpace]: room left at the bottom for the Back / Home / Recent buttons.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class NotifPanel(private val act: Context, private val sfx: Sfx, private val host: Host, bottomSpace: Int) : FrameLayout(act) {

    interface Host {
        /** Opens what a notification leads to (as from the phone's shade), growing out of [from]. */
        fun openNotification(pkg: String, intent: PendingIntent?, key: String, autoCancel: Boolean, from: RectF?)
        /** Sends one of a notification's buttons (e.g. Mark as read, Pause). */
        fun sendAction(intent: PendingIntent)
        /** The phone's own quick settings. */
        fun phoneQuickSettings()
        /** While the panel covers the home screen completely (the home screen can rest meanwhile). */
        fun covering(on: Boolean)
        /** A short message at the bottom of the screen. */
        fun note(text: String)
        /** All the way up and put away. */
        fun gone() {}
        /** A shortcut opened something (Wi-Fi, Bluetooth, Settings): over an app, the panel steps aside for it. */
        fun launched() {}
    }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(act).scaledTouchSlop.toFloat()

    /** How far down it is: 0 = up out of sight, 1 = all the way down. */
    private var shown = 0f
    private var anim: android.animation.ValueAnimator? = null
    val isOpen: Boolean get() = visibility == VISIBLE && shown > 0f
    /** All the way down (covering the home screen completely). */
    val fullyOpen: Boolean get() = visibility == VISIBLE && shown >= 1f

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // Never an invisible layer catching touches: up out of sight, it's put away and the touch goes to the home screen
        if (e.actionMasked == MotionEvent.ACTION_DOWN && shown < 0.02f && anim?.isRunning != true) {
            pulling = false; dragging = false; place(0f)
            return false
        }
        return super.dispatchTouchEvent(e)
    }

    private val sheet = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val clock = TextView(act)
    private val date = TextView(act)
    private val listHead = LinearLayout(act)
    private val clearBtn = TextView(act)
    private val scroll = ScrollView(act)
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val shortcuts = LinearLayout(act)
    private var torchBtn: Shortcut? = null

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        // The sheet: dark glass from the top, with a light edge along its bottom
        sheet.background = object : Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(c: Canvas) {
                val b = bounds
                p.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                    intArrayOf(Color.argb(246, 8, 14, 36), Color.argb(240, 4, 8, 22)), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(b, p); p.shader = null
                p.color = Color.argb(150, 140, 190, 255)
                c.drawRect(b.left.toFloat(), b.bottom - 1.2f * d, b.right.toFloat(), b.bottom.toFloat(), p)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        val pad = (20 * d).toInt()
        sheet.setPadding(pad, (44 * d).toInt(), pad, 0)

        // Time and date
        clock.textSize = 40f; clock.typeface = NexusUi.light; clock.setTextColor(NexusUi.TEXT); clock.letterSpacing = 0.02f
        clock.setShadowLayer(14 * d, 0f, 0f, Color.argb(150, 80, 140, 255))
        date.textSize = 13f; date.typeface = NexusUi.light; date.setTextColor(NexusUi.MUTED); date.letterSpacing = 0.14f
        sheet.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (18 * d).toInt() })

        // Shortcuts
        shortcuts.orientation = LinearLayout.HORIZONTAL
        fun add(sc: Shortcut) = shortcuts.addView(sc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(Shortcut(WIFI, "Wi-Fi") {
            open(if (android.os.Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS)
        })
        add(Shortcut(BLUETOOTH, "Bluetooth") { open(Settings.ACTION_BLUETOOTH_SETTINGS) })
        torchBtn = Shortcut(TORCH, "Flashlight") { toggleTorch() }.also { add(it) }
        add(Shortcut(QUICK, "Quick settings") { host.phoneQuickSettings() })
        add(Shortcut(GEAR, "Settings") { open(Settings.ACTION_SETTINGS) })
        sheet.addView(shortcuts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (16 * d).toInt() })

        // "NOTIFICATIONS" · Clear all
        listHead.orientation = LinearLayout.HORIZONTAL
        listHead.gravity = Gravity.CENTER_VERTICAL
        val head = TextView(act).apply {
            text = "NOTIFICATIONS"; textSize = 11f; typeface = NexusUi.light; letterSpacing = 0.2f; setTextColor(NexusUi.MUTED)
        }
        listHead.addView(head, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clearBtn.text = "Clear all"; clearBtn.textSize = 13f; clearBtn.typeface = NexusUi.light; clearBtn.letterSpacing = 0.06f
        clearBtn.setTextColor(NexusUi.BLUE)
        clearBtn.setPadding((12 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
        clearBtn.setOnClickListener { clearAll() }
        listHead.addView(clearBtn)
        sheet.addView(listHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4 * d).toInt() })

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isVerticalFadingEdgeEnabled = true
        scroll.setFadingEdgeLength((24 * d).toInt())
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The handle: tap it (or swipe up) to put the panel away; the space below it is NEXUS's buttons'
        val handle = object : View(act) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 160, 195, 245) }
            override fun onDraw(c: Canvas) {
                val w = 40 * d; val y = 14 * d
                c.drawRoundRect(width / 2f - w / 2, y - 2 * d, width / 2f + w / 2, y + 2 * d, 2 * d, 2 * d, p)
            }
        }
        handle.contentDescription = "Close notifications"
        handle.setOnClickListener { sfx.tick(); close() }
        sheet.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (30 * d).toInt()))
        if (bottomSpace > 0) sheet.addView(View(act), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomSpace))

        addView(sheet, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ coming down and going up

    /** Being pulled down from the home screen right now (it stays, even at the very top, until let go). */
    private var pulling = false

    /** Pulled down from the top of the home screen by [dy] (it follows the finger). */
    fun pull(dy: Float) {
        pulling = true
        anim?.cancel()
        if (visibility != VISIBLE) prepare()
        // (Until it's been measured, the screen's height)
        val h = if (height > 0) height else resources.displayMetrics.heightPixels
        place((dy / max(1f, h * 0.85f)).coerceIn(0f, 1f))
    }

    /** Let go after pulling: down if it's most of the way or was flicked down, otherwise back up. [vy]: px/s, down > 0. */
    fun release(vy: Float) {
        pulling = false
        if (vy > 900 * d || (shown > 0.35f && vy > -600 * d)) animateTo(1f) else animateTo(0f)
    }

    /** Comes all the way down (a tap on the top bar). */
    fun open() {
        if (visibility != VISIBLE) {
            // (Starts just out of sight above the screen, then comes down)
            prepare(); shown = 0f
            sheet.translationY = -(if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
            setBackgroundColor(Color.TRANSPARENT)
        }
        animateTo(1f)
    }

    fun close() { if (visibility == VISIBLE) animateTo(0f) }

    /** Gone at once (NEXUS left for an app). */
    fun closeNow() { anim?.cancel(); pulling = false; dragging = false; if (visibility == VISIBLE) place(0f) }

    private fun prepare() {
        visibility = VISIBLE
        updateClock()
        rebuild()
        NexusNotifications.onChange = { if (visibility == VISIBLE) { if (swiping == 0) rebuild() else pendingRebuild = true } }
        watchTorch(true)
        removeCallbacks(minuteTick); postDelayed(minuteTick, 60_000L - System.currentTimeMillis() % 60_000L)
    }

    private fun animateTo(target: Float) {
        anim?.cancel()
        val from = shown
        if (from == target) { place(target); return }
        anim = android.animation.ValueAnimator.ofFloat(from, target).apply {
            duration = (140 + 200 * abs(target - from)).toLong()
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { place(it.animatedValue as Float) }
            start()
        }
    }

    private fun place(k: Float) {
        val was = shown
        shown = k
        val h = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        sheet.translationY = -h * (1f - k)
        // The home screen dims behind it as it comes down
        setBackgroundColor(Color.argb((150 * k).toInt(), 0, 0, 6))
        if (k >= 1f && was < 1f) host.covering(true)
        if (k < 1f && was >= 1f) host.covering(false)
        // All the way up and not held: put away
        if (k <= 0f && !dragging && !pulling) {
            visibility = GONE
            NexusNotifications.onChange = null
            watchTorch(false)
            removeCallbacks(minuteTick)
            list.removeAllViews()
            host.gone()
        }
    }

    // A swipe up on the panel (outside the list, or once the list is at its end) puts it away, following the finger
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var dragFrom = 1f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; dragFrom = shown }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY; val dx = e.x - downX
                if (!dragging && dy < -slop && abs(dy) > abs(dx) * 1.2f) {
                    val inList = scrollHit(e.x, e.y)
                    if (!inList || !scroll.canScrollVertically(1)) { startDrag(e); return true }
                }
            }
        }
        return false
    }

    private fun scrollHit(x: Float, y: Float): Boolean {
        val r = android.graphics.Rect(); scroll.getHitRect(r)
        return r.contains(x.toInt(), (y - sheet.translationY).toInt())
    }

    private fun startDrag(e: MotionEvent) {
        dragging = true; anim?.cancel(); dragFrom = shown; downY = e.y
        tracker?.recycle(); tracker = VelocityTracker.obtain()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Below the sheet while it's only part way down: a tap there puts it back up
                downX = e.x; downY = e.y; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.y - downY) > slop) startDrag(e)
                if (dragging) {
                    tracker?.addMovement(e)
                    place((dragFrom + (e.y - downY) / max(1f, height * 0.85f)).coerceIn(0f, 1f))
                }
            }
            MotionEvent.ACTION_UP -> {
                val was = dragging
                dragging = false
                if (was) {
                    tracker?.addMovement(e); tracker?.computeCurrentVelocity(1000)
                    release(tracker?.yVelocity ?: 0f)
                } else if (e.y > sheet.translationY + height) close()
                tracker?.recycle(); tracker = null
            }
            MotionEvent.ACTION_CANCEL -> { val was = dragging; dragging = false; if (was) release(0f); tracker?.recycle(); tracker = null }
        }
        return true
    }

    // ------------------------------------------------------------------ what it shows

    private val minuteTick = object : Runnable {
        override fun run() { updateClock(); refreshTimes(); postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L) }
    }

    private fun updateClock() {
        val now = java.util.Date()
        val fmt = if (android.text.format.DateFormat.is24HourFormat(act)) "HH:mm" else "h:mm"
        clock.text = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(now)
        date.text = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now).uppercase()
    }

    private var swiping = 0
    private var pendingRebuild = false

    private fun rebuild() {
        pendingRebuild = false
        val items = NexusNotifications.panelList()
        list.removeAllViews()
        clearBtn.visibility = if (items.any { clearable(it.sbn.notification) || NexusNotifications.isMusic(it.sbn.notification) }) VISIBLE else GONE
        if (items.isEmpty()) {
            list.addView(TextView(act).apply {
                text = "No notifications"; textSize = 14f; typeface = NexusUi.light; letterSpacing = 0.06f
                setTextColor(NexusUi.MUTED); gravity = Gravity.CENTER
                setPadding(0, (48 * d).toInt(), 0, (48 * d).toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        var silentHead = false
        for (s in items) {
            if (s.silent && !silentHead && items.any { !it.silent }) {
                silentHead = true
                list.addView(TextView(act).apply {
                    text = "SILENT"; textSize = 11f; typeface = NexusUi.light; letterSpacing = 0.2f; setTextColor(NexusUi.MUTED)
                    setPadding((2 * d).toInt(), (14 * d).toInt(), 0, (4 * d).toInt())
                })
            }
            list.addView(Card(s), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
        list.addView(View(act), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (12 * d).toInt()))
    }

    private fun refreshTimes() { for (i in 0 until list.childCount) (list.getChildAt(i) as? Card)?.updateTime() }

    private fun clearable(n: Notification?): Boolean =
        n != null && n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE) == 0

    /** Clear all: each clearable notification slides away in turn, then they're cleared (and the panel goes up if that's all). */
    private fun clearAll() {
        sfx.confirm()
        var delay = 0L
        val gone = ArrayList<Card>()
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i) as? Card ?: continue
            if (!c.clearable) continue
            gone.add(c)
            c.animate().translationX(width.toFloat()).alpha(0f).setStartDelay(delay).setDuration(180).start()
            delay += 35
        }
        postDelayed({
            NexusNotifications.clearAll()
            // (Anything the apps won't let go of, like music players, stays out of NEXUS too)
            NexusNotifications.hide(act, gone.map { it.s.sbn.key to it.s.sbn.postTime })
            val left = (0 until list.childCount).any { (list.getChildAt(it) as? Card)?.clearable == false }
            if (!left) close() else rebuild()
        }, delay + 200)
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 24 * 60 -> "${m / 60}h"; else -> "${m / (24 * 60)}d" }
    }

    private fun open(action: String) {
        try { act.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { try { act.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
        sfx.tick()
        host.launched()
    }

    // ------------------------------------------------------------------ flashlight

    private var torchId: String? = null
    private var torchOn = false
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) { torchOn = enabled; torchBtn?.setOn(enabled) }
        }
    }

    private fun cams(): CameraManager? = act.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private fun watchTorch(on: Boolean) {
        val cm = cams() ?: return
        try {
            if (on) {
                if (torchId == null) torchId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                if (torchId != null) cm.registerTorchCallback(torchCb, null)
            } else cm.unregisterTorchCallback(torchCb)
        } catch (_: Exception) {}
    }

    private fun toggleTorch() {
        val cm = cams(); val id = torchId
        if (cm == null || id == null) { host.note("No flashlight on this phone"); return }
        try { cm.setTorchMode(id, !torchOn); sfx.tick() } catch (_: Exception) { host.note("The flashlight is busy") }
    }

    // ------------------------------------------------------------------ a shortcut: a round glass button with a symbol

    private inner class Shortcut(private val kind: Int, label: String, private val click: () -> Unit) : LinearLayout(act) {
        private var on = false
        private val glyph = object : View(act) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            private val path = Path()
            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - 1.5f * d
                p.style = Paint.Style.FILL
                p.color = if (on) Color.argb(235, 90, 150, 255) else Color.argb(isPressed.let { if (it) 120 else 70 }, 40, 80, 200)
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d
                p.color = Color.argb(if (on) 230 else 130, 150, 195, 255)
                c.drawCircle(cx, cy, r, p)
                p.color = if (on) Color.rgb(8, 16, 40) else NexusUi.TEXT
                p.strokeWidth = 1.8f * d; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                val u = r * 0.42f
                when (kind) {
                    WIFI -> {
                        for (i in 1..3) {
                            val rr = u * 0.42f * i
                            c.drawArc(cx - rr, cy + u * 0.55f - rr, cx + rr, cy + u * 0.55f + rr, 225f, 90f, false, p)
                        }
                        p.style = Paint.Style.FILL; c.drawCircle(cx, cy + u * 0.55f, 1.6f * d, p)
                    }
                    BLUETOOTH -> {
                        path.reset()
                        path.moveTo(cx - u * 0.5f, cy - u * 0.45f); path.lineTo(cx + u * 0.45f, cy + u * 0.45f)
                        path.lineTo(cx, cy + u * 0.95f); path.lineTo(cx, cy - u * 0.95f)
                        path.lineTo(cx + u * 0.45f, cy - u * 0.45f); path.lineTo(cx - u * 0.5f, cy + u * 0.45f)
                        c.drawPath(path, p)
                    }
                    TORCH -> {
                        path.reset()
                        path.moveTo(cx - u * 0.55f, cy - u * 0.75f); path.lineTo(cx + u * 0.55f, cy - u * 0.75f)
                        path.lineTo(cx + u * 0.3f, cy - u * 0.15f); path.lineTo(cx - u * 0.3f, cy - u * 0.15f); path.close()
                        c.drawPath(path, p)
                        c.drawRoundRect(cx - u * 0.3f, cy - u * 0.15f, cx + u * 0.3f, cy + u * 0.95f, 2 * d, 2 * d, p)
                        if (on) { c.drawLine(cx, cy - u * 1.05f, cx, cy - u * 1.3f, p); c.drawLine(cx - u * 0.75f, cy - u * 0.95f, cx - u * 0.95f, cy - u * 1.15f, p); c.drawLine(cx + u * 0.75f, cy - u * 0.95f, cx + u * 0.95f, cy - u * 1.15f, p) }
                    }
                    QUICK -> {
                        c.drawLine(cx - u * 0.9f, cy - u * 0.4f, cx + u * 0.9f, cy - u * 0.4f, p)
                        c.drawLine(cx - u * 0.9f, cy + u * 0.4f, cx + u * 0.9f, cy + u * 0.4f, p)
                        p.style = Paint.Style.FILL
                        c.drawCircle(cx + u * 0.35f, cy - u * 0.4f, 3.2f * d, p); c.drawCircle(cx - u * 0.35f, cy + u * 0.4f, 3.2f * d, p)
                    }
                    GEAR -> {
                        c.drawCircle(cx, cy, u * 0.42f, p)
                        for (i in 0 until 8) {
                            val a = Math.toRadians(i * 45.0)
                            val x0 = cx + (u * 0.62f) * kotlin.math.cos(a).toFloat(); val y0 = cy + (u * 0.62f) * kotlin.math.sin(a).toFloat()
                            val x1 = cx + (u * 0.92f) * kotlin.math.cos(a).toFloat(); val y1 = cy + (u * 0.92f) * kotlin.math.sin(a).toFloat()
                            c.drawLine(x0, y0, x1, y1, p)
                        }
                        c.drawCircle(cx, cy, u * 0.62f, p)
                    }
                }
            }
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            contentDescription = label
            addView(glyph, LinearLayout.LayoutParams((50 * d).toInt(), (50 * d).toInt()))
            addView(TextView(act).apply {
                text = label; textSize = 10.5f; typeface = NexusUi.light; letterSpacing = 0.04f
                setTextColor(NexusUi.MUTED); gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, (6 * d).toInt(), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { click() }
        }

        fun setOn(v: Boolean) { on = v; glyph.invalidate(); if (kind == TORCH) contentDescription = if (v) "Flashlight, on" else "Flashlight, off" }

        override fun dispatchSetPressed(pressed: Boolean) { super.dispatchSetPressed(pressed); glyph.invalidate() }
        override fun drawableStateChanged() { super.drawableStateChanged(); glyph.invalidate() }
    }

    private companion object {
        const val WIFI = 0; const val BLUETOOTH = 1; const val TORCH = 2; const val QUICK = 3; const val GEAR = 4
    }

    // ------------------------------------------------------------------ a notification card

    /**
     * One notification: the app's icon, the app · when, the title, what it says (a few lines), its picture (a
     * sender's photo or album art) if it has one, a progress bar if it's doing something, and its buttons.
     * Tap to open it, swipe it sideways to clear it (one that can't be cleared only gives a little).
     */
    private inner class Card(val s: NexusNotifications.Shown) : FrameLayout(act) {
        private val n: Notification = s.sbn.notification
        // (A music player can always be swiped away here, even when the app keeps it: it then stays out of NEXUS)
        val clearable = clearable(n) || NexusNotifications.isMusic(n)
        private val whenView = TextView(act)
        private var cx0 = 0f; private var cy0 = 0f
        private var swipe = false
        private var vt: VelocityTracker? = null

        init {
            background = NexusUi.glass(d, 14f)
            isClickable = true
            val ex: Bundle = n.extras ?: Bundle()
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val p = (12 * d).toInt()
            row.setPadding(p, p, p, p)
            // The app's icon (or the notification's own small symbol)
            val icon = ImageView(act)
            val appIcon = AppCache.get(act, s.sbn.packageName)?.icon?.constantState?.newDrawable(resources)?.mutate()
            if (appIcon != null) icon.setImageDrawable(appIcon)
            else try { n.smallIcon?.loadDrawable(act)?.mutate()?.also { it.setTint(NexusUi.TEXT) }?.let { icon.setImageDrawable(it) } } catch (_: Exception) {}
            row.addView(icon, LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt()).apply { rightMargin = (12 * d).toInt(); topMargin = (2 * d).toInt() })

            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            whenView.textSize = 10.5f; whenView.typeface = NexusUi.light; whenView.letterSpacing = 0.12f; whenView.setTextColor(NexusUi.MUTED)
            whenView.maxLines = 1; whenView.ellipsize = TextUtils.TruncateAt.END
            updateTime()
            col.addView(whenView)
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            if (title.isNotEmpty()) col.addView(TextView(act).apply {
                text = title; textSize = 15f; setTextColor(NexusUi.TEXT); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, (3 * d).toInt(), 0, 0)
            })
            val body = bodyText(ex)
            if (body.isNotEmpty()) col.addView(TextView(act).apply {
                text = body; textSize = 13.5f; typeface = NexusUi.light; setTextColor(Color.rgb(205, 220, 245))
                maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(2 * d, 1f)
                setPadding(0, (2 * d).toInt(), 0, 0)
            })
            // Doing something (a download, an upload): its progress
            val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val indet = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
            if (max > 0 || indet) col.addView(Progress(if (indet) -1f else ex.getInt(Notification.EXTRA_PROGRESS, 0) / max.toFloat()),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (10 * d).toInt()).apply { topMargin = (6 * d).toInt() })
            // Its buttons (Reply opens the conversation, as typing a reply here isn't possible)
            val acts = n.actions?.filter { it.title != null && it.actionIntent != null }?.take(3).orEmpty()
            if (acts.isNotEmpty()) {
                val ar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (a in acts) ar.addView(TextView(act).apply {
                    text = a.title.toString().uppercase(); textSize = 11.5f; letterSpacing = 0.1f; setTextColor(NexusUi.BLUE)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, (10 * d).toInt(), (18 * d).toInt(), (2 * d).toInt())
                    isClickable = true
                    setOnClickListener {
                        sfx.tick()
                        if (a.remoteInputs?.isNotEmpty() == true) openIt() else host.sendAction(a.actionIntent)
                    }
                })
                col.addView(ar)
            }
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // Its picture, if it has one
            val big = try { if (android.os.Build.VERSION.SDK_INT >= 23) n.getLargeIcon()?.loadDrawable(act) else null } catch (_: Exception) { null }
            if (big != null) row.addView(ImageView(act).apply {
                setImageDrawable(big); scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 10 * d) }
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams((42 * d).toInt(), (42 * d).toInt()).apply { leftMargin = (10 * d).toInt() })
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            contentDescription = "${s.app}. " + listOf(title, body).filter { it.isNotEmpty() }.joinToString(". ")
            if (clearable) {
                // TalkBack: Dismiss, as in the notification shade
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
                    }
                    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS) { fly(1f); return true }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            }
            setOnClickListener { openIt() }
        }

        fun updateTime() {
            whenView.text = s.app.uppercase() + "  ·  " + ago(s.sbn.postTime)
        }

        private fun bodyText(ex: Bundle): String {
            // A conversation: its last few messages, with who sent them
            val msgs = try { @Suppress("DEPRECATION") ex.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (_: Exception) { null }
            if (!msgs.isNullOrEmpty()) {
                val lines = msgs.takeLast(3).mapNotNull { m ->
                    val b = m as? Bundle ?: return@mapNotNull null
                    val t = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                    if (t.isEmpty()) null else {
                        val who = b.getCharSequence("sender")?.toString()
                            ?: (if (android.os.Build.VERSION.SDK_INT >= 28) try { @Suppress("DEPRECATION") (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString() } catch (_: Exception) { null } else null)
                        if (who.isNullOrBlank()) t else "$who: $t"
                    }
                }
                if (lines.isNotEmpty()) return lines.joinToString("\n")
            }
            // A list (an inbox): its lines
            val inbox = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (!inbox.isNullOrEmpty()) return inbox.take(4).joinToString("\n") { it.toString().trim() }
            return (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        }

        private fun openIt() {
            sfx.confirm()
            val loc = IntArray(2); getLocationInWindow(loc)
            host.openNotification(s.sbn.packageName, n.contentIntent, s.sbn.key, n.flags and Notification.FLAG_AUTO_CANCEL != 0,
                RectF(loc[0].toFloat(), loc[1].toFloat(), loc[0] + width.toFloat(), loc[1] + height.toFloat()))
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            // A sideways swipe that starts on one of its buttons still moves the card
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { cx0 = e.rawX; cy0 = e.rawY; swipe = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - cx0; val dy = e.rawY - cy0
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) { beginSwipe(); return true }
                }
            }
            return false
        }

        private fun beginSwipe() {
            if (swipe) return
            swipe = true; swiping++
            isPressed = false
            parent?.requestDisallowInterceptTouchEvent(true)
            vt?.recycle(); vt = VelocityTracker.obtain()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { cx0 = e.rawX; cy0 = e.rawY; swipe = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - cx0; val dy = e.rawY - cy0
                    if (!swipe && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                        beginSwipe()
                        // (Its own tap handling stands down)
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel); cancel.recycle()
                    }
                    if (swipe) {
                        vt?.addMovement(e)
                        translationX = if (clearable) dx else dx * 0.25f
                        alpha = 1f - (abs(translationX) / max(1f, width.toFloat())).coerceIn(0f, 1f) * 0.8f
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (swipe) {
                        vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                        val vx = vt?.xVelocity ?: 0f
                        vt?.recycle(); vt = null
                        swipe = false; swiping = max(0, swiping - 1)
                        val far = abs(translationX) > width * 0.35f || (abs(vx) > 1000 * d && vx * translationX > 0f)
                        if (clearable && far && e.actionMasked == MotionEvent.ACTION_UP) fly(if (translationX < 0f) -1f else 1f)
                        else { animate().translationX(0f).alpha(1f).setDuration(200).start(); if (pendingRebuild) post { if (swiping == 0) rebuild() } }
                        return true
                    }
                }
            }
            return super.onTouchEvent(e)
        }

        /** Off it goes, the cards below close the gap, and it's cleared (with its group's summary if it was the last). */
        fun fly(dir: Float) {
            sfx.tick()
            animate().translationX(dir * width).alpha(0f).setDuration(170).withEndAction {
                val h0 = height
                val va = android.animation.ValueAnimator.ofInt(h0, 0).setDuration(170)
                va.addUpdateListener { layoutParams = layoutParams.apply { height = it.animatedValue as Int }; requestLayout() }
                va.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val keys = arrayListOf(s.sbn.key)
                        val lastOfGroup = s.summaryKey != null && (0 until list.childCount).none {
                            val o = list.getChildAt(it) as? Card
                            o != null && o !== this@Card && o.s.sbn.groupKey == s.sbn.groupKey
                        }
                        if (lastOfGroup) keys.add(s.summaryKey!!)
                        list.removeView(this@Card)
                        NexusNotifications.dismissAll(keys)
                        // (And it stays gone from NEXUS even if the app won't let go of it)
                        NexusNotifications.hide(act, listOf(s.sbn.key to s.sbn.postTime))
                    }
                })
                va.start()
            }.start()
        }
    }

    /** A thin progress bar ([k] < 0: going, no telling how far). */
    private inner class Progress(private val k: Float) : View(act) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t0 = SystemClock.uptimeMillis()
        override fun onDraw(c: Canvas) {
            val y = height / 2f; val r = 2 * d
            p.color = Color.argb(70, 140, 190, 255)
            c.drawRoundRect(0f, y - r, width.toFloat(), y + r, r, r, p)
            p.color = NexusUi.BLUE
            if (k >= 0f) c.drawRoundRect(0f, y - r, width * k.coerceIn(0f, 1f), y + r, r, r, p)
            else {
                val f = ((SystemClock.uptimeMillis() - t0) % 1400L) / 1400f
                val x0 = (width * 1.4f) * f - width * 0.4f
                c.drawRoundRect(max(0f, x0), y - r, min(width.toFloat(), x0 + width * 0.4f), y + r, r, r, p)
                postInvalidateOnAnimation()
            }
        }
    }
}
