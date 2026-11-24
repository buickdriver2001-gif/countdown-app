package com.gamehub.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shows the details of a crash so they can be sent along for a fix (in the NEXUS style, like every other screen). */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = App.crashFile(this)
        val report = if (file.exists()) file.readText() else "No error details were saved."
        val d = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(3, 6, 18), Color.rgb(8, 16, 42), Color.rgb(2, 4, 12)))
            setPadding((22 * d).toInt(), (40 * d).toInt(), (22 * d).toInt(), (20 * d).toInt())
            // Clear of the status bar and the phone's buttons, whatever their size
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding((22 * d).toInt(), (24 * d).toInt() + insets.systemWindowInsetTop, (22 * d).toInt(), (20 * d).toInt() + insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(TextView(this).apply {
            text = "NEXUS HIT AN ERROR"
            textSize = 18f; letterSpacing = 0.18f; typeface = NexusUi.light
            setTextColor(Color.rgb(255, 150, 130))
        })
        root.addView(View(this).apply { setBackgroundColor(Color.argb(150, 140, 190, 255)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)).apply { topMargin = (10 * d).toInt(); bottomMargin = (10 * d).toInt() })
        root.addView(TextView(this).apply {
            text = "Tap Copy, then include it when you report the problem so it can be fixed."
            textSize = 14f; typeface = NexusUi.light
            setTextColor(NexusUi.MUTED)
            setPadding(0, 0, 0, (10 * d).toInt())
        })
        root.addView(ScrollView(this).apply {
            background = NexusUi.glass(d, 8f)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            addView(TextView(this@CrashActivity).apply {
                text = report
                textSize = 11f
                setTextColor(NexusUi.TEXT)
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun button(label: String, primary: Boolean = false, click: () -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; typeface = NexusUi.light; letterSpacing = 0.06f
            setTextColor(if (primary) Color.WHITE else NexusUi.TEXT)
            gravity = Gravity.CENTER; minHeight = (48 * d).toInt()
            background = if (primary) GradientDrawable().apply {
                cornerRadius = 8 * d
                colors = intArrayOf(Color.rgb(70, 130, 245), Color.rgb(40, 85, 200))
            } else NexusUi.glass(d, 8f, strong = true)
            isClickable = true; isFocusable = true
            setOnClickListener { click() }
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = (10 * d).toInt()
            })
        }
        val gap = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() } }

        root.addView(row(
            button("Copy") {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("NEXUS error", report))
                NexusUi.note(this@CrashActivity, "Copied. Paste it into your message.")
            },
            button("Try again", primary = true) {
                file.delete()
                startActivity(Intent(this@CrashActivity, MainActivity::class.java))
                finish()
            }), gap())
        // Never leave anyone stuck: if NEXUS is the Home app and keeps failing, these get you out
        root.addView(row(
            button("Normal home screen") {
                file.delete()
                HomeMode.setOn(this@CrashActivity, false)
                if (!HomeMode.goNormalHome(this@CrashActivity)) NexusUi.note(this@CrashActivity, "No other home screen found")
                else finish()
            },
            button("Change Home app") {
                file.delete()
                try { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
                catch (_: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
            }), gap())
        setContentView(root)
    }
}
