package com.gamehub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One app on the phone. */
class AppItem(val label: String, val pkg: String, val isGame: Boolean) { var icon: Drawable? = null }

/**
 * Full-screen app drawer in NEXUS style: search, Games first, then every app A–Z in a grid.
 * In "add" mode, tapping an app adds it to NEXUS instead of opening it.
 */
@SuppressLint("ViewConstructor")
class AppDrawer(private val a: Activity, private val sfx: Sfx,
                private val onOpen: (AppItem) -> Unit, private val onAdd: (List<AppItem>) -> Unit,
                private val inNexus: (String) -> Boolean) : FrameLayout(a) {

    private val d = resources.displayMetrics.density
    private var apps: List<AppItem> = emptyList()
    private var addMode = false
    /** Apps ticked for adding (by package). */
    private val picked = LinkedHashSet<String>()
    private val addBar = TextView(a)
    private val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (40 * d).toInt()) }
    private val title = TextView(a)
    private val search = EditText(a)
    var isOpen = false
        private set

    init {
        visibility = GONE
        isClickable = true
        // Deep blue backdrop with a faint grid
        addView(object : View(a) {
            val p = Paint()
            override fun onDraw(c: Canvas) {
                p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(Color.rgb(4, 8, 24), Color.rgb(8, 16, 42), Color.rgb(2, 4, 12)), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p); p.shader = null
                p.color = Color.argb(14, 140, 190, 255); p.strokeWidth = 1f
                var x = 0f; while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), p); x += 40 * d }
                var y = 0f; while (y < height) { c.drawLine(0f, y, width.toFloat(), y, p); y += 40 * d }
            }
        })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (40 * d).toInt(), 0, 0) }
        // Header: title and close
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((22 * d).toInt(), 0, (12 * d).toInt(), 0) }
        title.apply { setTextColor(NexusUi.TEXT); textSize = 20f; letterSpacing = 0.3f; typeface = NexusUi.light }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(a).apply {
            text = "✕"; setTextColor(NexusUi.MUTED); textSize = 20f; gravity = Gravity.CENTER
            setOnClickListener { sfx.tick(); close() }
        }, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
        col.addView(head)
        // Search
        search.apply {
            hint = "Search apps"; setSingleLine(); setTextColor(NexusUi.TEXT); setHintTextColor(Color.argb(140, 160, 185, 230))
            textSize = 15f; typeface = NexusUi.light; background = NexusUi.glass(d, 6f)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { fill() }
            })
        }
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (18 * d).toInt(); rightMargin = (18 * d).toInt(); topMargin = (8 * d).toInt(); bottomMargin = (8 * d).toInt()
        })
        col.addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(grid) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col)
        // Floating "Add N apps" bar for multi-select
        addBar.apply {
            setTextColor(Color.WHITE); textSize = 15f; letterSpacing = 0.2f; typeface = NexusUi.light
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26 * d
                colors = intArrayOf(Color.rgb(70, 130, 245), Color.rgb(40, 85, 200))
                setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(200, 190, 220, 255))
            }
            elevation = 8 * d
            visibility = GONE
            setOnClickListener {
                val items = apps.filter { it.pkg in picked }
                if (items.isEmpty()) return@setOnClickListener
                sfx.confirm(); onAdd(items); close()
            }
        }
        addView(addBar, LayoutParams((240 * d).toInt(), (52 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (28 * d).toInt() })
    }

    /** Reads the phone's apps (in the background; icons too). */
    fun load() {
        Thread {
            val pm = a.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != a.packageName }
                .map { info -> AppItem(pm.getApplicationLabel(info).toString(), info.packageName, Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME) }
                .sortedBy { it.label.lowercase() }
            for (it in list) it.icon = try { pm.getApplicationIcon(it.pkg) } catch (_: Exception) { null }
            post { apps = list; if (isOpen) fill() }
        }.start()
    }

    fun open(add: Boolean, preselect: String? = null) {
        addMode = add
        picked.clear(); preselect?.let { picked.add(it) }
        updateBar()
        title.text = if (add) "ADD TO NEXUS" else "ALL APPS"
        search.setText("")
        fill()
        isOpen = true
        visibility = VISIBLE
        alpha = 0f; translationY = 60 * d
        animate().alpha(1f).translationY(0f).setDuration(260).start()
        if (apps.isEmpty()) load()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(search.windowToken, 0)
        animate().alpha(0f).translationY(60 * d).setDuration(200).withEndAction { visibility = GONE }.start()
    }

    private fun updateBar() {
        val n = picked.size
        addBar.text = if (n == 1) "ADD 1 APP" else "ADD $n APPS"
        addBar.visibility = if (addMode && n > 0) VISIBLE else GONE
        if (addMode) title.text = if (n > 0) "ADD TO NEXUS · $n SELECTED" else "ADD TO NEXUS"
    }

    private fun fill() {
        grid.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val shown = apps.filter { q.isEmpty() || it.label.lowercase().contains(q) }
        if (apps.isEmpty()) { grid.addView(section("LOADING…")); return }
        if (shown.isEmpty()) { grid.addView(section("NO MATCHES")); return }
        if (addMode) grid.addView(TextView(a).apply {
            text = "Tap apps to select them, then tap Add."; setTextColor(NexusUi.MUTED); textSize = 12f; typeface = NexusUi.light
            setPadding((6 * d).toInt(), (6 * d).toInt(), 0, 0)
        })
        val games = shown.filter { it.isGame }
        if (games.isNotEmpty() && q.isEmpty()) { grid.addView(section("GAMES")); addRows(games) }
        grid.addView(section(if (q.isEmpty()) "ALL APPS" else "RESULTS"))
        addRows(shown)
    }

    private fun section(t: String) = TextView(a).apply {
        text = t; setTextColor(NexusUi.BLUE); textSize = 11f; letterSpacing = 0.3f; typeface = NexusUi.light
        setPadding((6 * d).toInt(), (16 * d).toInt(), 0, (6 * d).toInt())
    }

    private fun addRows(list: List<AppItem>) {
        val perRow = 4
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until perRow) {
                val item = list.getOrNull(i + k)
                row.addView(if (item != null) cell(item) else View(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(row)
            i += perRow
        }
    }

    private fun cell(item: AppItem): View {
        val added = inNexus(item.pkg)
        val sel = item.pkg in picked
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding((4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt())
            isClickable = true
            background = if (sel) NexusUi.glass(d, 8f, strong = true) else android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), NexusUi.glass(d, 8f, strong = true))
            }
        }
        val iconFrame = FrameLayout(a)
        iconFrame.addView(ImageView(a).apply { setImageDrawable(item.icon); alpha = if (addMode && added) 0.45f else 1f },
            FrameLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
        if (addMode) iconFrame.addView(TextView(a).apply {
            // Tick circle: filled when picked, grey when already in NEXUS, empty outline otherwise
            text = if (sel || added) "✓" else ""; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(when { added -> Color.rgb(70, 80, 100); sel -> Color.rgb(60, 120, 230); else -> Color.argb(120, 0, 0, 0) })
                setStroke((1.5f * d).toInt(), Color.argb(220, 200, 225, 255))
            }
        }, FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.END or Gravity.TOP))
        box.addView(iconFrame, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
        box.addView(TextView(a).apply {
            text = item.label; setTextColor(NexusUi.TEXT); textSize = 11.5f; typeface = NexusUi.light
            gravity = Gravity.CENTER; maxLines = 2; setPadding(0, (6 * d).toInt(), 0, 0)
        })
        box.setOnClickListener {
            if (addMode) {
                if (added) { android.widget.Toast.makeText(a, "${item.label} is already in NEXUS", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                sfx.tick()
                if (!picked.add(item.pkg)) picked.remove(item.pkg)
                updateBar(); fill()
            } else { sfx.confirm(); onOpen(item) }
        }
        box.setOnLongClickListener {
            sfx.tick()
            val opts = ArrayList<NexusUi.Option>()
            if (!added) opts.add(NexusUi.Option("Add to NEXUS", primary = true) { onAdd(listOf(item)); fill() })
            if (!added && !addMode) opts.add(NexusUi.Option("Select multiple to add") { open(add = true, preselect = item.pkg) })
            opts.add(NexusUi.Option("Open") { onOpen(item) })
            opts.add(NexusUi.Option("App info") {
                try { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${item.pkg}"))) } catch (_: Exception) {}
            })
            NexusUi.panel(a, item.label, null, opts)
            true
        }
        return box
    }
}
