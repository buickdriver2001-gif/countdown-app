package com.zombiedots.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Shows the details of a crash so they can be sent to Claude for a fix. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = App.crashFile(this)
        val report = if (file.exists()) file.readText() else "No error details were saved."
        val d = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0D10"))
            setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        }
        root.addView(TextView(this).apply {
            text = "Zombie Dots hit an error"
            textSize = 20f
            setTextColor(Color.parseColor("#FF3B3B"))
        })
        root.addView(TextView(this).apply {
            text = "Tap Copy, then paste it to Claude so it can be fixed."
            textSize = 14f
            setTextColor(Color.parseColor("#8A93A3"))
            setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt())
        })
        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report
                textSize = 11f
                setTextColor(Color.WHITE)
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Zombie Dots error", report))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Try again"
            setOnClickListener {
                file.delete()
                startActivity(Intent(this@CrashActivity, MainActivity::class.java))
                finish()
            }
        })
        root.addView(buttons)
        setContentView(root)
    }
}
