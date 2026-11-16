package com.simplenotes.app

import android.graphics.Typeface
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray
import org.json.JSONObject

/** Text formatting: bold, italic, underline, strikethrough, color, highlight and size. */
object RichText {

    enum class Kind(val code: String) {
        BOLD("b"), ITALIC("i"), UNDERLINE("u"), STRIKE("s"), COLOR("c"), HIGHLIGHT("h"), SIZE("z")
    }

    /** Which formatting kinds a span carries (a bold+italic span counts as both). */
    fun kindsOf(span: Any): List<Kind> = when {
        span is StyleSpan -> when (span.style) {
            Typeface.BOLD -> listOf(Kind.BOLD)
            Typeface.ITALIC -> listOf(Kind.ITALIC)
            Typeface.BOLD_ITALIC -> listOf(Kind.BOLD, Kind.ITALIC)
            else -> emptyList()
        }
        span.javaClass == UnderlineSpan::class.java -> listOf(Kind.UNDERLINE)
        span is StrikethroughSpan -> listOf(Kind.STRIKE)
        span is ForegroundColorSpan -> listOf(Kind.COLOR)
        span is BackgroundColorSpan -> listOf(Kind.HIGHLIGHT)
        span is RelativeSizeSpan -> listOf(Kind.SIZE)
        else -> emptyList()
    }

    /** A fresh span for [kind]; [value] is the color or size where that applies. */
    fun make(kind: Kind, value: Double = 0.0): Any = when (kind) {
        Kind.BOLD -> StyleSpan(Typeface.BOLD)
        Kind.ITALIC -> StyleSpan(Typeface.ITALIC)
        Kind.UNDERLINE -> UnderlineSpan()
        Kind.STRIKE -> StrikethroughSpan()
        Kind.COLOR -> ForegroundColorSpan(value.toInt())
        Kind.HIGHLIGHT -> BackgroundColorSpan(value.toInt())
        Kind.SIZE -> RelativeSizeSpan(value.toFloat())
    }

    private fun valueOf(span: Any): Double = when (span) {
        is ForegroundColorSpan -> span.foregroundColor.toDouble()
        is BackgroundColorSpan -> span.backgroundColor.toDouble()
        is RelativeSizeSpan -> span.sizeChange.toDouble()
        else -> 0.0
    }

    private fun isUserSpan(text: Spanned, span: Any) =
        (text.getSpanFlags(span) and Spanned.SPAN_COMPOSING) == 0 && kindsOf(span).isNotEmpty()

    /** True if every character in [start, end) has [kind]. */
    fun has(text: Spanned, start: Int, end: Int, kind: Kind): Boolean {
        if (start >= end) return false
        val covered = BooleanArray(end - start)
        for (span in text.getSpans(start, end, Any::class.java)) {
            if (!isUserSpan(text, span) || kind !in kindsOf(span)) continue
            val s = maxOf(text.getSpanStart(span), start)
            val e = minOf(text.getSpanEnd(span), end)
            for (i in s until e) covered[i - start] = true
        }
        return covered.all { it }
    }

    /** Removes [kind] from [start, end), keeping it on the parts outside that range. */
    fun remove(text: Spannable, start: Int, end: Int, kind: Kind) {
        for (span in text.getSpans(start, end, Any::class.java)) {
            if (!isUserSpan(text, span)) continue
            val kinds = kindsOf(span)
            if (kind !in kinds) continue
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (e <= start || s >= end) continue
            val v = valueOf(span)
            text.removeSpan(span)
            for (k in kinds) {
                if (s < start) text.setSpan(make(k, v), s, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (e > end) text.setSpan(make(k, v), end, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // A bold+italic span losing one style keeps the other inside the range
                if (k != kind) text.setSpan(make(k, v), maxOf(s, start), minOf(e, end), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun add(text: Spannable, start: Int, end: Int, kind: Kind, value: Double = 0.0) {
        if (start >= end) return
        // Color, highlight and size replace whatever was there
        if (kind == Kind.COLOR || kind == Kind.HIGHLIGHT || kind == Kind.SIZE) remove(text, start, end, kind)
        text.setSpan(make(kind, value), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Saves the formatting of [text] (first [length] chars) as JSON. */
    fun toJson(text: Spanned, length: Int): String {
        val arr = JSONArray()
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            if (!isUserSpan(text, span)) continue
            val s = text.getSpanStart(span).coerceIn(0, length)
            val e = text.getSpanEnd(span).coerceIn(0, length)
            if (s >= e) continue
            for (k in kindsOf(span)) {
                arr.put(JSONObject().put("t", k.code).put("s", s).put("e", e).put("v", valueOf(span)))
            }
        }
        return if (arr.length() == 0) "" else arr.toString()
    }

    /** Re-applies formatting saved by [toJson]. */
    fun apply(text: Spannable, json: String, offset: Int = 0) {
        if (json.isEmpty()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = Kind.values().firstOrNull { it.code == o.getString("t") } ?: continue
                val s = (o.getInt("s") + offset).coerceIn(0, text.length)
                val e = (o.getInt("e") + offset).coerceIn(0, text.length)
                if (s < e) text.setSpan(make(kind, o.optDouble("v", 0.0)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } catch (e: Exception) {
            // Ignore broken formatting rather than losing the note
        }
    }
}
