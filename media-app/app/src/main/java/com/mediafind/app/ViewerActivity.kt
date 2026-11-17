package com.mediafind.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import kotlin.math.abs

/** Full-screen viewer: pinch-zoom pictures, play videos (with sound) and loop GIFs, swipe through galleries. */
class ViewerActivity : Activity() {

    companion object {
        var current: Post? = null
    }

    private lateinit var post: Post
    private var index = 0
    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var spinner: ProgressBar
    private lateinit var counter: TextView
    private lateinit var errorText: TextView
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private var scale = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        post = current ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        video = VideoView(this).apply { visibility = View.GONE }
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        errorText = TextView(this).apply {
            setTextColor(Color.parseColor("#C8CED8")); textSize = 15f; gravity = Gravity.CENTER; visibility = View.GONE
            text = "Couldn't load this one."
        }
        root.addView(errorText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        fun topButton(label: String, desc: String, gravity: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            this.gravity = Gravity.CENTER
            contentDescription = desc
            setOnClickListener { onClick() }
            root.addView(this, FrameLayout.LayoutParams(dp(52), dp(52), gravity).apply { setMargins(dp(8), dp(24), dp(8), 0) })
        }
        topButton("✕", "Close", Gravity.TOP or Gravity.START) { finish() }
        topButton("↗", "Share link", Gravity.TOP or Gravity.END) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, post.permalink)
            startActivity(Intent.createChooser(send, "Share"))
        }
        counter = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setShadowLayer(6f, 0f, 1f, Color.BLACK); gravity = Gravity.CENTER
        }
        root.addView(counter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })

        setContentView(root)
        show(0)
    }

    private fun show(i: Int) {
        index = i.coerceIn(0, post.items.size - 1)
        resetZoom()
        errorText.visibility = View.GONE
        counter.text = if (post.items.size > 1) "${index + 1} / ${post.items.size}" else ""
        val item = post.items[index]
        video.stopPlayback()
        if (item.kind == MediaKind.IMAGE) {
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            spinner.visibility = View.VISIBLE
            ImageLoader.load(image, item.url, maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)) { ok ->
                spinner.visibility = View.GONE
                if (!ok) errorText.visibility = View.VISIBLE
            }
        } else {
            image.setImageDrawable(null)
            image.visibility = View.VISIBLE // stays on top to catch swipes; transparent
            video.visibility = View.VISIBLE
            spinner.visibility = View.VISIBLE
            playVideo(item, item.url, triedFallback = false)
        }
    }

    private fun playVideo(item: MediaItem, url: String, triedFallback: Boolean) {
        if (item.kind == MediaKind.VIDEO) {
            val mc = MediaController(this)
            mc.setAnchorView(video)
            video.setMediaController(mc)
        } else video.setMediaController(null)
        video.setOnPreparedListener { mp ->
            spinner.visibility = View.GONE
            mp.isLooping = item.kind == MediaKind.GIF
            video.start()
        }
        video.setOnErrorListener { _, _, _ ->
            val fb = item.fallback
            if (!triedFallback && fb != null && fb != url) {
                playVideo(item, fb, triedFallback = true) // plain mp4 (may have no sound)
            } else {
                spinner.visibility = View.GONE
                errorText.visibility = View.VISIBLE
            }
            true
        }
        video.setVideoURI(Uri.parse(url))
    }

    // ---------- gestures: pinch to zoom, drag when zoomed, double-tap, swipe for gallery, tap for video controls

    private fun resetZoom() {
        scale = 1f
        image.scaleX = 1f; image.scaleY = 1f
        image.translationX = 0f; image.translationY = 0f
    }

    private val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            if (post.items[index].kind != MediaKind.IMAGE) return false
            scale = (scale * det.scaleFactor).coerceIn(1f, 5f)
            image.scaleX = scale; image.scaleY = scale
            if (scale == 1f) { image.translationX = 0f; image.translationY = 0f }
            return true
        }
    })

    private val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (post.items[index].kind != MediaKind.IMAGE) return false
            if (scale > 1f) resetZoom() else { scale = 2.5f; image.scaleX = scale; image.scaleY = scale }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (post.items[index].kind == MediaKind.VIDEO) {
                if (video.isPlaying) video.pause() else video.start()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale > 1f) {
                image.translationX -= dx
                image.translationY -= dy
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scale > 1f || e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > abs(dy) && abs(dx) > dp(60)) {
                if (dx < 0 && index < post.items.size - 1) show(index + 1)
                else if (dx > 0 && index > 0) show(index - 1)
                return true
            }
            if (dy > dp(140) && abs(dy) > abs(dx)) { finish(); return true } // swipe down to close
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Let the top buttons work normally; handle gestures everywhere else
        val handledByChild = super.dispatchTouchEvent(ev)
        scaler.onTouchEvent(ev)
        if (!scaler.isInProgress) gestures.onTouchEvent(ev)
        return handledByChild || true
    }

    override fun onDestroy() {
        super.onDestroy()
        video.stopPlayback()
    }
}
