package com.simplenotes.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One piece of a note: either a run of text or a picture. */
data class Block(val type: String, val value: String) {
    companion object {
        const val TEXT = "text"
        const val IMAGE = "image"
    }
}

data class Note(
    val id: String,
    val updated: Long,
    val title: String,
    val blocks: List<Block>
) {
    fun preview(): String =
        blocks.filter { it.type == Block.TEXT }
            .joinToString(" ") { it.value }
            .replace('\n', ' ')
            .trim()

    fun images(): List<String> = blocks.filter { it.type == Block.IMAGE }.map { it.value }

    fun isEmpty(): Boolean =
        title.isBlank() && blocks.all { it.type == Block.TEXT && it.value.isBlank() }

    /** JSON of the content only (no timestamp), used to tell if anything changed. */
    fun contentJson(): String {
        val arr = JSONArray()
        for (b in blocks) arr.put(JSONObject().put("type", b.type).put("value", b.value))
        return JSONObject().put("title", title).put("blocks", arr).toString()
    }
}

/** Keeps each note as a small JSON file inside the app's private storage. */
object NoteStore {

    fun newId(): String = UUID.randomUUID().toString()

    private fun dir(ctx: Context) = File(ctx.filesDir, "notes").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    fun all(ctx: Context): List<Note> =
        (dir(ctx).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".json") }
            .mapNotNull { read(it) }
            .sortedByDescending { it.updated }

    fun load(ctx: Context, id: String): Note? = read(file(ctx, id))

    fun save(ctx: Context, note: Note) {
        val arr = JSONArray()
        for (b in note.blocks) arr.put(JSONObject().put("type", b.type).put("value", b.value))
        val json = JSONObject()
            .put("id", note.id)
            .put("updated", note.updated)
            .put("title", note.title)
            .put("blocks", arr)
        val target = file(ctx, note.id)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(json.toString())
            tmp.delete()
        }
    }

    /** Deletes the note and every picture in it. */
    fun delete(ctx: Context, id: String) {
        load(ctx, id)?.images()?.forEach { ImageUtil.delete(ctx, it) }
        file(ctx, id).delete()
    }

    private fun read(f: File): Note? = try {
        val o = JSONObject(f.readText())
        val arr = o.optJSONArray("blocks") ?: JSONArray()
        val blocks = (0 until arr.length()).map {
            val b = arr.getJSONObject(it)
            Block(b.getString("type"), b.optString("value", ""))
        }
        Note(o.getString("id"), o.optLong("updated"), o.optString("title", ""), blocks)
    } catch (e: Exception) {
        null
    }
}
