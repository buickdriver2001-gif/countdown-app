package com.simplenotes.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Note editor. The note is a column of text boxes and pictures. Adding a picture
 * splits the text box at the cursor, so the picture lands exactly where you were typing.
 */
class EditorActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
        private const val REQ_PICK = 1
    }

    private lateinit var titleInput: EditText
    private lateinit var blocks: LinearLayout

    private lateinit var noteId: String
    private var originalContent: String = ""
    private var originalUpdated: Long = 0L
    private var deleted = false

    /** Every picture this note has used while open, so removed ones can be cleaned up. */
    private val knownImages = HashSet<String>()

    private var insertTarget: EditText? = null
    private var insertCursor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        titleInput = findViewById(R.id.title_input)
        blocks = findViewById(R.id.blocks)

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.delete_button).setOnClickListener { confirmDelete() }
        findViewById<View>(R.id.add_image_button).setOnClickListener { pickImages() }

        val existing = intent.getStringExtra(EXTRA_ID)?.let { NoteStore.load(this, it) }
        if (existing != null) {
            noteId = existing.id
            originalUpdated = existing.updated
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_ID, noteId)
    }

    override fun onPause() {
        super.onPause()
        if (!deleted) save()
    }

    // ---------- building the note ----------

    private fun buildBlocks(list: List<Block>) {
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
                    last.append(if (last.text.isEmpty()) b.value else "\n" + b.value)
                } else {
                    blocks.addView(textBlock(b.value))
                }
            }
        }
        if (blocks.childCount == 0 || blocks.getChildAt(blocks.childCount - 1) !is EditText) {
            blocks.addView(textBlock(""))
        }
        refreshHints()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun textBlock(text: String): EditText {
        val et = EditText(this)
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
        et.setText(text)
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
        val text = target.text.toString()
        val cut = insertCursor.coerceIn(0, text.length)
        val before = text.substring(0, cut).trimEnd('\n')
        val after = text.substring(cut).trimStart('\n')
        target.setText(before)

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
            val merged = listOf(prev.text.toString(), next.text.toString())
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            blocks.removeView(next)
            prev.setText(merged)
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
                    val t = v.text.toString()
                    if (t.isNotBlank()) list.add(Block(Block.TEXT, t.trimEnd()))
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
        originalUpdated = note.updated
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
