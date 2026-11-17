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
        colors = intArrayOf(Color.argb(if (strong) 240 else 225, 10, 18, 44), Color.argb(if (strong) 240 else 225, 4, 8, 22))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(if (strong) 200 else 110, 140, 190, 255))
    }

    class Option(val label: String, val primary: Boolean = false, val danger: Boolean = false, val action: () -> Unit)

    /**
     * A panel with a title, optional message, optional text field, and a column of options.
     * Returns the text field (if asked for) so the caller can read it in an option's action.
     */
    fun panel(a: Activity, title: String, message: String? = null, options: List<Option>, cancel: String? = "Cancel",
              input: String? = null, onSelect: Boolean = true): EditText? {
        val d = a.resources.displayMetrics.density
        val dialog = Dialog(a)
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
        if (cancel != null) list.addView(row(a, cancel, false, false, quiet = true) { dialog.dismiss() })
        col.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(col)
        dialog.window?.setLayout((minOf(a.resources.displayMetrics.widthPixels - (40 * d).toInt(), (420 * d).toInt())), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setDimAmount(0.6f)
        dialog.window?.attributes?.windowAnimations = android.R.style.Animation_Dialog
        if (field != null) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        return field
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
