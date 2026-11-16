package com.simplenotes.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.simplenotes.app.RichText.Kind

/**
 * Note editor. The note is a column of text boxes and pictures. Adding a picture
 * splits the text box at the cursor, so the picture lands exactly where you were typing.
 */
class EditorActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
        private const val REQ_PICK = 1

        private val TEXT_COLORS = listOf(
            "Default" to null,
            "Red" to Color.parseColor("#E53935"),
            "Orange" to Color.parseColor("#F57C00"),
            "Gold" to Color.parseColor("#C99400"),
            "Green" to Color.parseColor("#43A047"),
            "Teal" to Color.parseColor("#00897B"),
            "Blue" to Color.parseColor("#1E88E5"),
            "Purple" to Color.parseColor("#8E24AA"),
            "Pink" to Color.parseColor("#D81B60"),
            "Gray" to Color.parseColor("#757575"),
        )
        private val HIGHLIGHTS = listOf(
            "None" to null,
            "Yellow" to Color.parseColor("#FFF176"),
            "Green" to Color.parseColor("#C5E1A5"),
            "Blue" to Color.parseColor("#B3E5FC"),
            "Pink" to Color.parseColor("#F8BBD0"),
            "Orange" to Color.parseColor("#FFCC80"),
            "Purple" to Color.parseColor("#E1BEE7"),
        )
        private val SIZES = listOf(
            "Small" to 0.85f,
            "Normal" to null,
            "Large" to 1.3f,
            "Heading" to 1.7f,
        )
    }

    private lateinit var titleInput: EditText
    private lateinit var blocks: LinearLayout

    private lateinit var noteId: String
    private var originalContent: String = ""
    private var deleted = false

    /** Every picture this note has used while open, so removed ones can be cleaned up. */
    private val knownImages = HashSet<String>()

    private var insertTarget: EditText? = null
    private var insertCursor = 0

    // Formatting
    private var activeText: RichEditText? = null
    private val pending = HashSet<Kind>()
    private var pendingColor: Int? = null
    private var pendingHighlight: Int? = null
    private var pendingSize: Float? = null
    private var applying = false
    private lateinit var fmtButtons: Map<Kind, TextView>
    private lateinit var colorButton: TextView
    private lateinit var highlightButton: TextView
    private lateinit var sizeButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        titleInput = findViewById(R.id.title_input)
        blocks = findViewById(R.id.blocks)

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.delete_button).setOnClickListener { confirmDelete() }
        findViewById<View>(R.id.add_image_button).setOnClickListener { pickImages() }
        setupToolbar()

        val existing = intent.getStringExtra(EXTRA_ID)?.let { NoteStore.load(this, it) }
        if (existing != null) {
            noteId = existing.id
            originalContent = existing.contentJson()
            titleInput.setText(existing.title)
            buildBlocks(existing.blocks)
            knownImages.addAll(existing.images())
        } else {
            noteId = savedInstanceState?.getString(EXTRA_ID) ?: NoteStore.newId()
            originalContent = Note(noteId, 0, "", emptyList()).contentJson()
            buildBlocks(emptyList())
            titleInput.requestFocus()
        }
        updateToolbar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_ID, noteId)
    }

    override fun onPause() {
        super.onPause()
        if (!deleted) save()
    }

    // ---------- formatting toolbar ----------

    private fun setupToolbar() {
        fmtButtons = mapOf(
            Kind.BOLD to findViewById(R.id.fmt_bold),
            Kind.ITALIC to findViewById(R.id.fmt_italic),
            Kind.UNDERLINE to findViewById(R.id.fmt_underline),
            Kind.STRIKE to findViewById(R.id.fmt_strike),
        )
        colorButton = findViewById(R.id.fmt_color)
        highlightButton = findViewById(R.id.fmt_highlight)
        sizeButton = findViewById(R.id.fmt_size)

        // Show each button's own style on its letter
        findViewById<TextView>(R.id.fmt_underline).let {
            it.paintFlags = it.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        }
        findViewById<TextView>(R.id.fmt_strike).let {
            it.paintFlags = it.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }

        for ((kind, button) in fmtButtons) button.setOnClickListener { toggle(kind) }
        colorButton.setOnClickListener { pickColor() }
        highlightButton.setOnClickListener { pickHighlight() }
        sizeButton.setOnClickListener { pickSize() }
    }

    /** The text box being edited, or null (with a hint) if the cursor isn't in the note body. */
    private fun target(): RichEditText? {
        val et = activeText
        if (et == null || et.parent !== blocks) {
            Toast.makeText(this, "Tap into your note text first", Toast.LENGTH_SHORT).show()
            return null
        }
        return et
    }

    private fun selection(et: EditText): Pair<Int, Int> {
        val a = et.selectionStart.coerceAtLeast(0)
        val b = et.selectionEnd.coerceAtLeast(0)
        return minOf(a, b) to maxOf(a, b)
    }

    private fun toggle(kind: Kind) {
        val et = target() ?: return
        val (s, e) = selection(et)
        if (s < e) {
            val text = et.text
            if (RichText.has(text, s, e, kind)) RichText.remove(text, s, e, kind) else RichText.add(text, s, e, kind)
        } else {
            if (!pending.remove(kind)) pending.add(kind)
        }
        updateToolbar()
    }

    private fun swatchList(items: List<Pair<String, Int?>>, highlight: Boolean): Array<CharSequence> =
        items.map { (name, color) ->
            val label = SpannableString("  ●   $name  ")
            if (color != null) {
                if (highlight) {
                    label.setSpan(ForegroundColorSpan(color), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    label.setSpan(BackgroundColorSpan(color), 6, label.length - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    label.setSpan(ForegroundColorSpan(color), 2, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                label.setSpan(ForegroundColorSpan(Color.parseColor("#BBBBBB")), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            label as CharSequence
        }.toTypedArray()

    private fun pickColor() {
        val et = target() ?: return
        AlertDialog.Builder(this)
            .setTitle("Text color")
            .setItems(swatchList(TEXT_COLORS, false)) { _, which ->
                applyValue(et, Kind.COLOR, TEXT_COLORS[which].second?.toDouble()) { pendingColor = it?.toInt() }
            }
            .show()
    }

    private fun pickHighlight() {
        val et = target() ?: return
        AlertDialog.Builder(this)
            .setTitle("Highlight")
            .setItems(swatchList(HIGHLIGHTS, true)) { _, which ->
                applyValue(et, Kind.HIGHLIGHT, HIGHLIGHTS[which].second?.toDouble()) { pendingHighlight = it?.toInt() }
            }
            .show()
    }

    private fun pickSize() {
        val et = target() ?: return
        val labels = SIZES.map { (name, size) ->
            val s = SpannableString(name)
            if (size != null) s.setSpan(android.text.style.RelativeSizeSpan(size), 0, s.length, 0)
            s as CharSequence
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Text size")
            .setItems(labels) { _, which ->
                applyValue(et, Kind.SIZE, SIZES[which].second?.toDouble()) { pendingSize = it?.toFloat() }
            }
            .show()
    }

    /** Applies a color/highlight/size to the selection, or sets it for what you type next. */
    private fun applyValue(et: RichEditText, kind: Kind, value: Double?, setPending: (Double?) -> Unit) {
        val (s, e) = selection(et)
        if (s < e) {
            if (value == null) RichText.remove(et.text, s, e, kind) else RichText.add(et.text, s, e, kind, value)
        } else {
            setPending(value)
        }
        et.requestFocus()
        updateToolbar()
    }

    private fun updateToolbar() {
        val et = activeText?.takeIf { it.parent === blocks }
        val sel = et?.let { selection(it) }
        for ((kind, button) in fmtButtons) {
            val on = if (et != null && sel != null && sel.first < sel.second) {
                RichText.has(et.text, sel.first, sel.second, kind)
            } else kind in pending
            button.isSelected = on
        }
        colorButton.setTextColor(pendingColor ?: getColor(R.color.text_primary))
        colorButton.isSelected = pendingColor != null
        highlightButton.isSelected = pendingHighlight != null
        sizeButton.isSelected = pendingSize != null
    }

    // ---------- building the note ----------

    private fun styled(b: Block): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(b.value)
        RichText.apply(ssb, b.styles)
        return ssb
    }

    private fun buildBlocks(list: List<Block>) {
        applying = true
        blocks.removeAllViews()
        for (b in list) {
            if (b.type == Block.IMAGE) {
                if (blocks.childCount == 0 || blocks.getChildAt(blocks.childCount - 1) !is EditText) {
                    blocks.addView(textBlock(""))
                }
                blocks.addView(imageBlock(b.value))
            } else {
                val last = blocks.getChildAt(blocks.childCount - 1)
                if (last is EditText) {
                    if (last.text.isNotEmpty()) last.text.append("\n")
                    last.text.append(styled(b))
                } else {
                    blocks.addView(textBlock(styled(b)))
                }
            }
        }
        if (blocks.childCount == 0 || blocks.getChildAt(blocks.childCount - 1) !is EditText) {
            blocks.addView(textBlock(""))
        }
        applying = false
        refreshHints()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun textBlock(text: CharSequence): RichEditText {
        val et = RichEditText(this)
        et.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        et.background = null
        et.setPadding(0, dp(4), 0, dp(4))
        et.gravity = Gravity.TOP or Gravity.START
        et.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        et.setLineSpacing(dp(3).toFloat(), 1f)
        et.setTextColor(getColor(R.color.text_primary))
        et.setHintTextColor(getColor(R.color.text_muted))
        val wasApplying = applying
        applying = true
        et.setText(text, android.widget.TextView.BufferType.EDITABLE)
        applying = wasApplying

        et.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeText = et
                updateToolbar()
            }
        }
        et.onSelection = { if (et === activeText) updateToolbar() }
        et.addTextChangedListener(object : TextWatcher {
            private var start = 0
            private var count = 0
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                this.start = start
                this.count = count
            }
            override fun afterTextChanged(ed: Editable) {
                if (applying || count <= 0 || et !== activeText) return
                val s = start
                val e = (start + count).coerceAtMost(ed.length)
                if (s >= e) return
                applying = true
                for (k in pending) RichText.add(ed, s, e, k)
                pendingColor?.let { RichText.add(ed, s, e, Kind.COLOR, it.toDouble()) }
                pendingHighlight?.let { RichText.add(ed, s, e, Kind.HIGHLIGHT, it.toDouble()) }
                pendingSize?.let { RichText.add(ed, s, e, Kind.SIZE, it.toDouble()) }
                applying = false
            }
        })
        return et
    }

    private fun imageBlock(name: String): ImageView {
        val iv = ImageView(this)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        lp.bottomMargin = dp(8)
        iv.layoutParams = lp
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.tag = name
        iv.clipToOutline = true
        iv.setBackgroundResource(R.drawable.image_bg)
        val bmp = ImageUtil.load(this, name, resources.displayMetrics.widthPixels)
        if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageResource(R.drawable.ic_broken_image)
        iv.setOnClickListener { imageOptions(iv) }
        return iv
    }

    /** Only the very first, empty note shows the "Start writing" hint. */
    private fun refreshHints() {
        for (i in 0 until blocks.childCount) {
            val v = blocks.getChildAt(i)
            if (v is EditText) {
                v.hint = if (blocks.childCount == 1) "Start writing…" else null
                v.minLines = if (i == blocks.childCount - 1) 6 else 1
            }
        }
    }

    // ---------- pictures ----------

    private fun pickImages() {
        val focused = currentFocus
        if (focused is EditText && focused.parent === blocks) {
            insertTarget = focused
            insertCursor = focused.selectionStart.coerceAtLeast(0)
        } else {
            val last = blocks.getChildAt(blocks.childCount - 1) as EditText
            insertTarget = last
            insertCursor = last.text.length
        }

        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
        } else {
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "No photo picker found on this phone", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return

        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return

        Toast.makeText(this, if (uris.size == 1) "Adding photo…" else "Adding ${uris.size} photos…", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        Thread {
            val names = uris.mapNotNull { ImageUtil.import(appContext, it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    names.forEach { ImageUtil.delete(appContext, it) }
                    return@runOnUiThread
                }
                if (names.size < uris.size) {
                    Toast.makeText(this, "Some photos couldn't be added", Toast.LENGTH_LONG).show()
                }
                if (names.isNotEmpty()) insertImages(names)
            }
        }.start()
    }

    private fun insertImages(names: List<String>) {
        knownImages.addAll(names)
        var target = insertTarget
        if (target == null || target.parent !== blocks) {
            target = blocks.getChildAt(blocks.childCount - 1) as EditText
            insertCursor = target.text.length
        }
        val ed = target.text
        val cut = insertCursor.coerceIn(0, ed.length)
        var beforeEnd = cut
        while (beforeEnd > 0 && ed[beforeEnd - 1] == '\n') beforeEnd--
        var afterStart = cut
        while (afterStart < ed.length && ed[afterStart] == '\n') afterStart++
        val before = SpannableStringBuilder(ed.subSequence(0, beforeEnd))
        val after = SpannableStringBuilder(ed.subSequence(afterStart, ed.length))
        applying = true
        target.setText(before, android.widget.TextView.BufferType.EDITABLE)
        applying = false

        var pos = blocks.indexOfChild(target) + 1
        names.forEachIndexed { i, name ->
            blocks.addView(imageBlock(name), pos++)
            if (i < names.size - 1) blocks.addView(textBlock(""), pos++)
        }
        val afterBlock = textBlock(after)
        blocks.addView(afterBlock, pos)
        refreshHints()
        afterBlock.requestFocus()
        afterBlock.setSelection(0)
        insertTarget = null
        save()
    }

    private fun imageOptions(iv: ImageView) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("View full screen", "Remove photo")) { _, which ->
                if (which == 0) viewFull(iv.tag as String) else removeImage(iv)
            }
            .show()
    }

    private fun viewFull(name: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setImageBitmap(ImageUtil.load(this, name, resources.displayMetrics.heightPixels))
        iv.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(iv)
        dialog.show()
    }

    private fun removeImage(iv: ImageView) {
        val idx = blocks.indexOfChild(iv)
        if (idx < 0) return
        val prev = blocks.getChildAt(idx - 1) as? EditText
        val next = blocks.getChildAt(idx + 1) as? EditText
        blocks.removeView(iv)
        if (prev != null && next != null) {
            val merged = SpannableStringBuilder(prev.text)
            if (merged.isNotEmpty() && next.text.isNotEmpty()) merged.append("\n")
            merged.append(next.text)
            blocks.removeView(next)
            applying = true
            prev.setText(merged, android.widget.TextView.BufferType.EDITABLE)
            applying = false
        }
        refreshHints()
        save()
    }

    // ---------- saving ----------

    private fun currentNote(updated: Long): Note {
        val list = ArrayList<Block>()
        for (i in 0 until blocks.childCount) {
            when (val v = blocks.getChildAt(i)) {
                is EditText -> {
                    val t = v.text
                    if (t.isNotBlank()) {
                        var end = t.length
                        while (end > 0 && t[end - 1].isWhitespace()) end--
                        list.add(Block(Block.TEXT, t.substring(0, end), RichText.toJson(t, end)))
                    }
                }
                is ImageView -> list.add(Block(Block.IMAGE, v.tag as String))
            }
        }
        return Note(noteId, updated, titleInput.text.toString().trim(), list)
    }

    private fun save() {
        val note = currentNote(System.currentTimeMillis())
        // Clean up pictures that were removed from this note
        val inUse = note.images().toSet()
        for (name in knownImages) if (name !in inUse) ImageUtil.delete(this, name)
        knownImages.retainAll(inUse)

        if (note.isEmpty()) {
            NoteStore.delete(this, noteId)
            return
        }
        val content = note.contentJson()
        if (content == originalContent) return
        NoteStore.save(this, note)
        originalContent = content
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this note?")
            .setPositiveButton("Delete") { _, _ ->
                deleted = true
                knownImages.addAll(currentNote(0).images())
                knownImages.forEach { ImageUtil.delete(this, it) }
                NoteStore.delete(this, noteId)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
