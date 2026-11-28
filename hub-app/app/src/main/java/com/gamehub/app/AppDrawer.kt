package com.gamehub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
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

/** One app icon on the phone (an app can have more than one, each its own [component]). */
class AppItem(val label: String, val pkg: String, val isGame: Boolean, val component: android.content.ComponentName? = null,
              /** Changes when the app is updated (so its new icon is picked up). */ val stamp: String = "") { var icon: Drawable? = null }

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
    /** Cells currently on screen, by package (an app can appear twice: Games and All apps). */
    private val cells = HashMap<String, MutableList<Pair<LinearLayout, TextView?>>>()
    private var builtKey = ""
    private var quiet = false
    // (Room at the end of the list for the "Add N apps" bar, so the last row can always be scrolled clear of it)
    private val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (96 * d).toInt()) }
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (40 * d).toInt(), 0, 0) }

    /**
     * Room at the bottom for NEXUS's Back / Home / Recent buttons: the list stops just above them (with a soft fade
     * at its bottom edge), so no app is ever half under the buttons' see-through strip, looking darkened.
     */
    fun setBottomInset(px: Int) {
        col.setPadding(0, (40 * d).toInt(), 0, px)
        // (The "Add N apps" bar floats just above the buttons)
        (addBar.layoutParams as? LayoutParams)?.let { it.bottomMargin = (28 * d).toInt() + px; addBar.layoutParams = it }
    }
    private val title = TextView(a)
    private val search = EditText(a)
    var isOpen = false
        private set
    /** Lets NEXUS pause its animated background while the drawer covers it. */
    var onVisible: (Boolean) -> Unit = {}

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        // Solid deep blue backdrop (nothing behind shows through), with a faint grid on top
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(4, 8, 24), Color.rgb(8, 16, 42), Color.rgb(2, 4, 12)))
        elevation = 30 * d
        addView(object : View(a) {
            val p = Paint()
            override fun onDraw(c: Canvas) {
                p.color = Color.argb(14, 140, 190, 255); p.strokeWidth = 1f
                var x = 0f; while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), p); x += 40 * d }
                var y = 0f; while (y < height) { c.drawLine(0f, y, width.toFloat(), y, p); y += 40 * d }
            }
        })
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
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                // Enter / Go on the keyboard opens the first match
                val q = text.toString().trim().lowercase()
                val first = if (q.isEmpty() || addMode) null else apps.firstOrNull { it.label.lowercase().startsWith(q) } ?: apps.firstOrNull { it.label.lowercase().contains(q) }
                if (first != null) { sfx.open(); onOpen(first) }
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!quiet) fill() }
            })
        }
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (18 * d).toInt(); rightMargin = (18 * d).toInt(); topMargin = (8 * d).toInt(); bottomMargin = (8 * d).toInt()
        })
        col.addView(ScrollView(a).apply {
            isVerticalScrollBarEnabled = false; addView(grid)
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength((28 * d).toInt())
        },
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

    private var loadedAt = 0L
    /** The icon that was just tapped (so the app can open out of it). */
    var lastTapped: View? = null
    /** Each load gets a number, so a slow older load can never overwrite a newer one. */
    private var loadGen = 0
    /** Goes up whenever a fresh list arrives (names or icons may have changed even if the count didn't). */
    private var appsVersion = 0
    private var appsSig: Int? = null

    /** Reads every app icon on the phone, the way launchers do (in the background; icons too). */
    fun load() {
        val gen = ++loadGen
        Bg.thread {
            val list = try { readLauncherApps() } catch (_: Exception) { null } ?: try { readByIntent() } catch (_: Exception) { null }
            val sig = list?.joinToString("\n") { "${it.component}|${it.label}|${it.stamp}" }?.hashCode()
            post {
                if (gen != loadGen || list == null) return@post
                loadedAt = android.os.SystemClock.uptimeMillis()
                // Nothing changed: keep the list as it is (no rebuilding while you're looking at it)
                if (sig == appsSig && apps.isNotEmpty()) return@post
                appsSig = sig
                apps = list; appsVersion++
                if (isOpen) fill()
                // Build the list ahead of time (a little each frame) so All Apps opens instantly
                else { removeCallbacks(prebuild); postDelayed(prebuild, 1500) }
            }
        }.start()
    }

    /** A–Z the way the phone's language sorts names (a new one per load: it isn't safe to share between threads). */
    private fun byName(): Comparator<AppItem> { val col = java.text.Collator.getInstance(); return Comparator { x, y -> col.compare(x.label, y.label) } }

    /** Android's launcher list: every icon (some apps have two), each with its own name and icon. */
    private fun readLauncherApps(): List<AppItem>? {
        val la = a.getSystemService(android.content.pm.LauncherApps::class.java) ?: return null
        return la.getActivityList(null, android.os.Process.myUserHandle())
            .filter { it.componentName.packageName != a.packageName }
            .distinctBy { it.componentName }
            .map { info ->
                AppItem(info.label.toString().trim().ifEmpty { info.componentName.packageName }, info.componentName.packageName,
                    info.applicationInfo.category == ApplicationInfo.CATEGORY_GAME, info.componentName, info.applicationInfo.sourceDir ?: "").also {
                    it.icon = try { info.getIcon(0) } catch (_: Exception) { null }
                }
            }
            .sortedWith(byName())
    }

    /** Fallback: ask the package manager. */
    private fun readByIntent(): List<AppItem> {
        val pm = a.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != a.packageName }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
            .map { r ->
                val ai = r.activityInfo
                AppItem(r.loadLabel(pm).toString().trim(), ai.packageName, ai.applicationInfo.category == ApplicationInfo.CATEGORY_GAME,
                    android.content.ComponentName(ai.packageName, ai.name), ai.applicationInfo.sourceDir ?: "").also { it.icon = try { r.loadIcon(pm) } catch (_: Exception) { null } }
            }
            .sortedWith(byName())
    }

    fun open(add: Boolean, preselect: String? = null) {
        addMode = add
        val old = HashSet(picked)
        picked.clear(); preselect?.let { picked.add(it) }
        title.text = if (add) "ADD TO NEXUS" else "ALL APPS"
        updateBar()
        quiet = true; if (search.text.isNotEmpty()) search.setText(""); quiet = false
        // Only rebuild the grid if something actually changed; otherwise just update the ticks
        if (key() != builtKey) fill() else for (pkg in old + picked) refreshCell(pkg)
        (grid.parent as? ScrollView)?.scrollTo(0, 0)
        isOpen = true
        onVisible(true)
        visibility = VISIBLE
        alpha = 0f; translationY = 48 * d
        animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).withLayer().start()
        // Refresh the list if it's empty or a while old (in case an app was installed or updated meanwhile)
        if (apps.isEmpty() || android.os.SystemClock.uptimeMillis() - loadedAt > 60_000) load()
    }

    /** Builds All Apps in the background (a couple of rows per frame) while it is closed. */
    private val prebuild = Runnable {
        if (isOpen || apps.isEmpty()) return@Runnable
        addMode = false; picked.clear()
        quiet = true; if (search.text.isNotEmpty()) search.setText(""); quiet = false
        if (key() != builtKey) fill(firstRows = 0, perFrame = 2)
    }

    private fun key() = "$addMode|${search.text}|$appsVersion|${apps.count { inNexus(it.pkg) }}"

    /** Updates one app's tick and highlight without rebuilding the grid. */
    private fun refreshCell(pkg: String) {
        val sel = pkg in picked
        val added = inNexus(pkg)
        for ((box, tick) in cells[pkg] ?: return) {
            box.background = if (sel) NexusUi.glass(d, 8f, strong = true) else android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), NexusUi.glass(d, 8f, strong = true))
            }
            tick?.text = if (sel || added) "✓" else ""
            (tick?.background as? android.graphics.drawable.GradientDrawable)?.setColor(when { added -> Color.rgb(70, 80, 100); sel -> Color.rgb(60, 120, 230); else -> Color.argb(120, 0, 0, 0) })
        }
    }

    /** [animate] = false when it isn't on screen anyway (e.g. Home pressed while in an app). */
    fun close(animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(search.windowToken, 0)
        onVisible(false)
        animate().cancel()
        if (!animate) { visibility = GONE; return }
        animate().alpha(0f).translationY(48 * d).setDuration(180).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).withLayer().withEndAction { visibility = GONE }.start()
    }

    private fun updateBar() {
        val n = picked.size
        addBar.text = if (n == 1) "ADD 1 APP" else "ADD $n APPS"
        addBar.visibility = if (addMode && n > 0) VISIBLE else GONE
        if (addMode) title.text = if (n > 0) "ADD TO NEXUS · $n SELECTED" else "ADD TO NEXUS"
    }

    // The list is built a few rows at a time: what's on screen first, the rest over the next frames,
    // so opening All Apps (and typing a search) never stalls the animation
    private val queue = ArrayDeque<() -> View>()
    private var rowsPerFrame = 3
    private val drain = object : Runnable {
        override fun run() {
            var n = 0
            while (queue.isNotEmpty() && n < rowsPerFrame) { grid.addView(queue.removeFirst()()); n++ }
            if (queue.isNotEmpty()) grid.postOnAnimation(this)
        }
    }

    private fun fill(firstRows: Int = 7, perFrame: Int = 3) {
        // Rebuilding while it's open (e.g. after adding an app): build down to where you are at once,
        // so the list doesn't jump back to the top
        val sv = grid.parent as? ScrollView
        val upTo = if (isOpen && sv != null) ((sv.scrollY + sv.height) / (80 * d)).toInt() + 3 else 0
        val first = maxOf(firstRows, upTo)
        grid.removeCallbacks(drain)
        queue.clear()
        grid.removeAllViews()
        cells.clear()
        builtKey = key()
        val q = search.text.toString().trim().lowercase()
        val shown = apps.filter { q.isEmpty() || it.label.lowercase().contains(q) }
        if (apps.isEmpty()) { grid.addView(section("LOADING…")); return }
        if (shown.isEmpty()) { grid.addView(section("NO MATCHES")); return }
        if (addMode) grid.addView(TextView(a).apply {
            text = "Tap apps to select them, then tap Add."; setTextColor(NexusUi.MUTED); textSize = 12f; typeface = NexusUi.light
            setPadding((6 * d).toInt(), (6 * d).toInt(), 0, 0)
        })
        val games = shown.filter { it.isGame }
        if (games.isNotEmpty() && q.isEmpty()) { queue.addLast { section("GAMES") }; addRows(games) }
        val head = if (q.isEmpty()) "ALL APPS" else "RESULTS"
        queue.addLast { section(head) }
        addRows(shown)
        var n = 0
        while (queue.isNotEmpty() && n < first) { grid.addView(queue.removeFirst()()); n++ }
        rowsPerFrame = perFrame
        if (queue.isNotEmpty()) grid.postOnAnimation(drain)
    }

    private fun section(t: String) = TextView(a).apply {
        text = t; setTextColor(NexusUi.BLUE); textSize = 11f; letterSpacing = 0.3f; typeface = NexusUi.light
        setPadding((6 * d).toInt(), (16 * d).toInt(), 0, (6 * d).toInt())
    }

    private fun addRows(list: List<AppItem>) {
        val perRow = 4
        var i = 0
        while (i < list.size) {
            val start = i
            queue.addLast {
                LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (k in 0 until perRow) {
                        val item = list.getOrNull(start + k)
                        addView(if (item != null) cell(item) else View(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                }
            }
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
        var tickView: TextView? = null
        if (addMode) iconFrame.addView(TextView(a).also { tickView = it }.apply {
            // Tick circle: filled when picked, grey when already in NEXUS, empty outline otherwise
            text = if (sel || added) "✓" else ""; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(when { added -> Color.rgb(70, 80, 100); sel -> Color.rgb(60, 120, 230); else -> Color.argb(120, 0, 0, 0) })
                setStroke((1.5f * d).toInt(), Color.argb(220, 200, 225, 255))
            }
        }, FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.END or Gravity.TOP))
        box.addView(iconFrame, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
        cells.getOrPut(item.pkg) { ArrayList() }.add(box to tickView)
        box.addView(TextView(a).apply {
            text = item.label; setTextColor(NexusUi.TEXT); textSize = 11.5f; typeface = NexusUi.light
            gravity = Gravity.CENTER; maxLines = 2; setPadding(0, (6 * d).toInt(), 0, 0)
        })
        box.setOnClickListener {
            if (addMode) {
                if (added) { NexusUi.note(a, "${item.label} is already in NEXUS"); return@setOnClickListener }
                sfx.tick()
                if (!picked.add(item.pkg)) picked.remove(item.pkg)
                updateBar(); refreshCell(item.pkg)
                box.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            } else { sfx.open(); lastTapped = box; onOpen(item) }
        }
        box.setOnLongClickListener {
            sfx.tick()
            val opts = ArrayList<NexusUi.Option>()
            if (!addMode) opts.addAll(AppActions.shortcutOptions(a, item.pkg))
            if (!added) opts.add(NexusUi.Option("Add to NEXUS", primary = true) { onAdd(listOf(item)); fill() })
            if (!added && !addMode) opts.add(NexusUi.Option("Select multiple to add") { open(add = true, preselect = item.pkg) })
            opts.add(NexusUi.Option("Open") { onOpen(item) })
            opts.add(AppActions.infoOption(a, item.pkg))
            AppActions.uninstallOption(a, item.pkg)?.let { opts.add(it) }
            NexusUi.panel(a, item.label, null, opts)
            true
        }
        return box
    }
}
