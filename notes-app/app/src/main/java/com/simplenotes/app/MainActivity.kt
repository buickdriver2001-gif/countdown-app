package com.simplenotes.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var list: ListView
    private var notes: List<Note> = emptyList()
    private val thumbs = HashMap<String, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.note_list)
        list.emptyView = findViewById(R.id.empty_view)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ -> open(notes[position].id) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(notes[position])
            true
        }
        findViewById<View>(R.id.new_button).setOnClickListener { open(null) }
    }

    override fun onResume() {
        super.onResume()
        notes = NoteStore.all(this)
        adapter.notifyDataSetChanged()
    }

    private fun open(id: String?) {
        val intent = Intent(this, EditorActivity::class.java)
        if (id != null) intent.putExtra(EditorActivity.EXTRA_ID, id)
        startActivity(intent)
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle("Delete this note?")
            .setMessage(note.title.ifBlank { note.preview().take(60).ifBlank { "Untitled" } })
            .setPositiveButton("Delete") { _, _ ->
                NoteStore.delete(this, note.id)
                notes = NoteStore.all(this)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun thumb(name: String): Bitmap? =
        thumbs.getOrPut(name) {
            ImageUtil.load(this, name, (64 * resources.displayMetrics.density).toInt() * 2)
        }

    private fun formatDate(t: Long): String =
        if (DateUtils.isToday(t)) {
            DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_TIME)
        } else {
            DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = notes.size
        override fun getItem(position: Int) = notes[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_note, parent, false)
            val note = notes[position]
            val preview = note.preview()

            val title = view.findViewById<TextView>(R.id.item_title)
            val body = view.findViewById<TextView>(R.id.item_preview)
            val date = view.findViewById<TextView>(R.id.item_date)
            val image = view.findViewById<ImageView>(R.id.item_thumb)

            title.text = note.title.ifBlank { if (preview.isNotEmpty()) preview else "Untitled" }
            val showPreview = note.title.isNotBlank() && preview.isNotEmpty()
            body.visibility = if (showPreview) View.VISIBLE else View.GONE
            body.text = preview

            val count = note.images().size
            date.text = formatDate(note.updated) +
                if (count == 0) "" else if (count == 1) "  ·  1 photo" else "  ·  $count photos"

            val first = note.images().firstOrNull()
            val bmp = first?.let { thumb(it) }
            image.visibility = if (bmp != null) View.VISIBLE else View.GONE
            image.setImageBitmap(bmp)
            return view
        }
    }
}
