package com.gamehub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-run guide (shown once; replay it from ⚙ → Tutorial). Explains NEXUS, sets it as Home,
 * turns on Open Apps, and explains how to go back to the phone's original launcher.
 */
@SuppressLint("ViewConstructor")
class TutorialView(private val a: Activity, private val sfx: Sfx, private val actions: Actions) : FrameLayout(a) {

    interface Actions {
        fun setHome()
        fun appInfo()
        fun accessibility()
        fun done()
    }

    private class Page(val step: String, val title: String, val body: String, val buttons: List<Pair<String, () -> Unit>> = emptyList())

    private val d = resources.displayMetrics.density
    private val pages = listOf(
        Page("01", "Welcome to NEXUS",
            "NEXUS is a new home screen for your phone: your favourite apps and games in one place, with a look of its own.\n\nThis quick guide takes about a minute."),
        Page("02", "Your apps",
            "• Tap an app once to highlight it, then tap it again to open it.\n• Hold an app and drag it to move it.\n• Hold an app without moving it to change its tile size (small, wide, tall, large or full row), or to edit or remove apps you added.\n• Tap the + ADD tile and tick as many apps as you like, then tap ADD.\n• Change the grid (3×5, 4×6, 4×8) in ⚙ → Grid size."),
        Page("03", "All apps",
            "Swipe up from the bottom of NEXUS, or tap ALL APPS, to see every app on your phone.\n\nSearch at the top. Games are listed first. Hold an app for more options."),
        Page("04", "Make NEXUS your home",
            "So pressing Home takes you to NEXUS:\n\n1. Tap the button below.\n2. Choose NEXUS as your Home app.\n\nNEXUS stays your home until you close it (see step 06).",
            listOf("Set NEXUS as Home" to { actions.setHome() })),
        Page("05", "Open apps (square button)",
            "See the apps you have open as NEXUS cards showing their last screen. Press the square button, or tap RECENT in NEXUS.\n\nTo turn it on:\n1. Tap Turn on helper, choose NEXUS Open Apps and switch it on.\n2. If Android says \"Restricted setting\", tap Allow restricted settings below, then tap ⋮ at the top right and choose Allow restricted settings. (Newer Android asks this for apps installed from a download.)\n3. Tap Turn on helper again and switch it on.\n\nNEXUS only uses this to remember each app's last screen and to show its own Open Apps screen. Pictures stay on your phone and are never sent anywhere. You can skip this step; everything else works without it.",
            listOf("Turn on helper" to { actions.accessibility() }, "Allow restricted settings" to { actions.appInfo() })),
        Page("06", "Closing NEXUS & going back",
            "• To use your normal home screen for a while: press Back in NEXUS and choose Close NEXUS. Open NEXUS from its icon to make it your home again.\n\n• To stop using NEXUS completely: Settings → Apps → Default apps → Home app, then choose your original launcher (the one your phone came with). Names can vary slightly by phone; searching Settings for \"Home app\" also works.",
            listOf("Open Home app settings" to { actions.setHome() })),
        Page("07", "You're all set",
            "You can see this guide again any time from ⚙ → Tutorial.\n\nEnjoy NEXUS.")
    )
    private var page = 0

    private val stepView = TextView(a)
    private val titleView = TextView(a)
    private val bodyView = TextView(a)
    private val buttonBox = LinearLayout(a)
    private val dots = LinearLayout(a)
    private val back = TextView(a)
    private val next = TextView(a)

    init {
        isClickable = true
        elevation = 40 * d
        setBackgroundColor(Color.argb(235, 2, 4, 14))
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.glass(d, 12f, strong = true)
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (18 * d).toInt())
        }
        stepView.apply { setTextColor(NexusUi.BLUE); textSize = 12f; letterSpacing = 0.4f; typeface = NexusUi.light }
        titleView.apply { setTextColor(NexusUi.TEXT); textSize = 22f; letterSpacing = 0.12f; typeface = NexusUi.light; setPadding(0, (6 * d).toInt(), 0, (12 * d).toInt()) }
        bodyView.apply { setTextColor(NexusUi.MUTED); textSize = 15f; typeface = NexusUi.light; setLineSpacing(0f, 1.25f) }
        panel.addView(stepView)
        panel.addView(titleView)
        panel.addView(View(a).apply { setBackgroundColor(Color.argb(150, 140, 190, 255)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)).apply { bottomMargin = (14 * d).toInt() })
        panel.addView(ScrollView(a).apply { addView(bodyView) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buttonBox.orientation = LinearLayout.VERTICAL
        panel.addView(buttonBox)
        dots.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, (16 * d).toInt(), 0, (10 * d).toInt()) }
        panel.addView(dots)
        val nav = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        back.apply { setTextColor(NexusUi.MUTED); textSize = 14f; letterSpacing = 0.2f; typeface = NexusUi.light; setPadding((8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
            setOnClickListener { if (page > 0) { sfx.tick(); page--; show() } else { sfx.tick(); actions.done() } } }
        next.apply { setTextColor(Color.WHITE); textSize = 14f; letterSpacing = 0.25f; typeface = NexusUi.light; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 22 * d; colors = intArrayOf(Color.rgb(70, 130, 245), Color.rgb(40, 85, 200)) }
            setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), (12 * d).toInt())
            setOnClickListener { sfx.confirm(); if (page < pages.size - 1) { page++; show() } else actions.done() } }
        nav.addView(back)
        nav.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(next)
        panel.addView(nav)
        val m = (18 * d).toInt()
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(m, (48 * d).toInt(), m, (36 * d).toInt()) })
        show()
    }

    private fun show() {
        val p = pages[page]
        stepView.text = "STEP ${p.step} / 0${pages.size}"
        titleView.text = p.title.uppercase()
        bodyView.text = p.body
        buttonBox.removeAllViews()
        for ((label, act) in p.buttons) buttonBox.addView(TextView(a).apply {
            text = label; setTextColor(NexusUi.TEXT); textSize = 15f; typeface = NexusUi.light; letterSpacing = 0.06f
            gravity = Gravity.CENTER; minHeight = (48 * d).toInt(); background = NexusUi.glass(d, 8f, strong = true)
            setOnClickListener { sfx.confirm(); act() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() }
        })
        dots.removeAllViews()
        for (i in pages.indices) dots.addView(View(a).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == page) Color.rgb(170, 210, 255) else Color.argb(80, 140, 190, 255)) }
        }, LinearLayout.LayoutParams((if (i == page) 9 else 6) * d.toInt(), (if (i == page) 9 else 6) * d.toInt()).apply { leftMargin = (4 * d).toInt(); rightMargin = (4 * d).toInt() })
        back.text = if (page == 0) "SKIP" else "BACK"
        next.text = if (page == pages.size - 1) "START" else "NEXT"
        alpha = 0.6f; animate().alpha(1f).setDuration(180).start()
    }

    /** Replay from the start. */
    fun restart() { page = 0; show() }
}
