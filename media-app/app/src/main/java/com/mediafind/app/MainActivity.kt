package com.mediafind.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("mediafind", Context.MODE_PRIVATE) }
    private val client = RedditClient { prefs.getString("clientId", null) }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var input: EditText
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var sortRow: LinearLayout
    private lateinit var timeRow: LinearLayout

    private val posts = ArrayList<Post>()
    private val seen = HashSet<String>()
    private var after: String? = null
    private var loading = false
    private var query = ""
    private var sort = "relevance"
    private var time = "all"
    private var requestId = 0

    private val bg = Color.parseColor("#0E1014")
    private val panel = Color.parseColor("#1A1D24")
    private val accent = Color.parseColor("#FF5A36")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun pill(color: Int, r: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = r * d }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(if (selected) Color.WHITE else Color.parseColor("#AAB2C0"))
        background = pill(if (selected) accent else panel, 16)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        }
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Search bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(6))
        }
        input = EditText(this).apply {
            hint = "Search Reddit, or r/subreddit"
            setHintTextColor(Color.parseColor("#6B7382"))
            setTextColor(Color.WHITE)
            textSize = 16f
            background = pill(panel, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) { startSearch(); true } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(TextView(this).apply {
            text = "🔍"
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = "Search"
            setOnClickListener { startSearch() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        bar.addView(TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.parseColor("#AAB2C0"))
            gravity = Gravity.CENTER
            contentDescription = "Settings"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(42), dp(46)))
        root.addView(bar)

        // Sort and time filters
        sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(4)) }
        timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sortRow) })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(timeRow) })
        buildFilters()

        // Results grid
        val frame = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = gridAdapter
            setOnItemClickListener { _, _, pos, _ -> openViewer(posts[pos]) }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (total > 0 && first + visible >= total - 9) loadMore()
                }
            })
        }
        frame.addView(grid)
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#8A93A3"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(30), 0, dp(30), 0)
            text = "Search for anything to see\nits pictures and videos."
        }
        frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        frame.addView(spinner, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildFilters() {
        sortRow.removeAllViews()
        for ((label, key) in listOf("Relevance" to "relevance", "Top" to "top", "New" to "new", "Hot" to "hot")) {
            sortRow.addView(chip(label, sort == key) { sort = key; buildFilters(); if (query.isNotEmpty()) startSearch(query) })
        }
        timeRow.removeAllViews()
        for ((label, key) in listOf("All time" to "all", "Year" to "year", "Month" to "month", "Week" to "week", "Today" to "day")) {
            timeRow.addView(chip(label, time == key) { time = key; buildFilters(); if (query.isNotEmpty()) startSearch(query) })
        }
    }

    private fun startSearch(q: String = input.text.toString()) {
        val text = q.trim()
        if (text.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        query = text
        posts.clear(); seen.clear()
        after = null
        gridAdapter.notifyDataSetChanged()
        status.text = ""
        loading = false
        requestId++
        loadMore(first = true)
    }

    private fun loadMore(first: Boolean = false) {
        if (loading || query.isEmpty() || (!first && after == null)) return
        loading = true
        spinner.visibility = View.VISIBLE
        val rid = requestId
        val q = query; val s = sort; val t = time; val a = after
        io.execute {
            // Keep paging until we find some media (many results are text-only)
            var page: Page? = null
            var error: String? = null
            var cursor = a
            try {
                var tries = 0
                val found = ArrayList<Post>()
                do {
                    val p = client.search(q, s, t, cursor)
                    found.addAll(p.posts)
                    cursor = p.after
                    tries++
                } while (found.size < 12 && cursor != null && tries < 4)
                page = Page(found, cursor)
            } catch (e: RedditBlockedException) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't reach Reddit. Check your internet connection and try again."
            }
            main.post {
                if (rid != requestId) return@post
                loading = false
                spinner.visibility = View.GONE
                if (page != null) {
                    for (p in page.posts) if (seen.add(p.id)) posts.add(p)
                    after = page.after
                    gridAdapter.notifyDataSetChanged()
                    status.text = if (posts.isEmpty()) "No pictures or videos found for \"$q\"." else ""
                } else {
                    after = null
                    if (posts.isEmpty()) status.text = error
                    if (error?.contains("Settings") == true && posts.isEmpty()) showSettings(error)
                }
            }
        }
    }

    private fun openViewer(post: Post) {
        ViewerActivity.current = post
        startActivity(Intent(this, ViewerActivity::class.java))
    }

    private val gridAdapter = object : BaseAdapter() {
        override fun getCount() = posts.size
        override fun getItem(position: Int) = posts[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.widthPixels / 3)
                setBackgroundColor(panel)
                addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; id = 1 })
                addView(TextView(this@MainActivity).apply {
                    id = 2
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            }
            val post = posts[position]
            val img = cell.findViewById<ImageView>(1)
            val badge = cell.findViewById<TextView>(2)
            badge.text = when {
                post.isGallery -> "▣ ${post.items.size}"
                post.items[0].kind == MediaKind.VIDEO -> "▶"
                post.items[0].kind == MediaKind.GIF -> "GIF"
                else -> ""
            }
            img.contentDescription = post.title
            ImageLoader.load(img, post.thumb, resources.displayMetrics.widthPixels / 3)
            return cell
        }
    }

    private fun showSettings(reason: String? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
        }
        box.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#C8CED8"))
            text = (if (reason != null) "$reason\n\n" else "") +
                "Optional: a free Reddit app key makes searching reliable.\n\n" +
                "1. Open reddit.com/prefs/apps (logged in) and tap \"create another app\".\n" +
                "2. Name it anything, choose \"installed app\", and enter http://localhost as the redirect uri.\n" +
                "3. Tap \"create app\". Copy the short code under the app's name (the client ID) and paste it below."
        })
        val field = EditText(this).apply {
            hint = "Client ID"
            setText(prefs.getString("clientId", ""))
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7382"))
        }
        box.addView(field)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Settings")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("clientId", field.text.toString().trim()).apply()
                if (query.isNotEmpty()) startSearch(query)
            }
            .setNeutralButton("Open reddit.com/prefs/apps") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com/prefs/apps"))) } catch (_: Exception) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
