package com.gamehub.app

import kotlin.math.max
import kotlin.math.min

/**
 * How the text on a home-screen tile is laid out (plain Kotlin, no Android, so it can be tested on its own).
 *
 * An app's name is never cut short with "…": it is shown in full, first by easing the text size down a
 * little, then by wrapping it over more lines (and shrinking the icon to make room). If even that isn't
 * enough, [plan] reports that it doesn't fit and the home screen makes the tile taller until it does.
 * The description is shown in full the same way.
 */
object TileText {
    enum class Kind { WIDE, LARGE, TALL, SMALL }

    /** Measures text width for a text size and letter spacing. */
    fun interface Measure { fun width(s: String, size: Float, spacing: Float): Float }

    class Plan(
        val kind: Kind,
        val name: List<String>, val nameSize: Float,
        val sub: List<String>, val subSize: Float,
        /** Icon size, and (small tiles) the icon's distance from the top of the tile. */
        val icon: Float, val iconTop: Float,
        val fits: Boolean,
    )

    const val NAME_SPACING = 0.08f
    const val SUB_SPACING = 0.04f
    const val SMALL_SPACING = 0.04f

    fun nameLineH(size: Float) = size * 1.2f
    fun subLineH(size: Float) = size * 1.28f

    /** Height of the name + description block. */
    fun blockHeight(p: Plan, d: Float): Float {
        var hgt = p.name.size * nameLineH(p.nameSize)
        if (p.sub.isNotEmpty()) hgt += 5 * d + p.sub.size * subLineH(p.subSize)
        return hgt
    }

    /** Tile shape, judged from the size the user picked (not from any extra height added for text). */
    fun kindOf(w: Float, baseH: Float, cellW: Float): Kind = when {
        w > baseH * 1.45f -> Kind.WIDE
        baseH > w * 1.45f -> Kind.TALL
        w > cellW * 1.5f -> Kind.LARGE
        else -> Kind.SMALL
    }

    /**
     * Breaks [s] into lines no wider than [maxW]. Lines break between words (and after "/" or "-");
     * a single word too long for a line is split across lines rather than cut off. Nothing is dropped.
     */
    fun wrap(s: String, maxW: Float, m: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        val text = s.trim()
        if (text.isEmpty()) return out
        if (m(text) <= maxW) { out.add(text); return out }
        // Pieces to place; each carries its leading space (if it starts a new word)
        val pieces = ArrayList<String>()
        for ((wi, word) in text.split(Regex("\\s+")).withIndex()) {
            var start = 0
            for (i in word.indices) {
                if ((word[i] == '/' || word[i] == '-') && i < word.length - 1) {
                    pieces.add((if (start == 0 && wi > 0) " " else "") + word.substring(start, i + 1))
                    start = i + 1
                }
            }
            pieces.add((if (start == 0 && wi > 0) " " else "") + word.substring(start))
        }
        var cur = ""
        for (pc in pieces) {
            val cand = if (cur.isEmpty()) pc.trimStart() else cur + pc
            if (cur.isEmpty() || m(cand) <= maxW) cur = cand
            else { out.add(cur); cur = pc.trimStart() }
            // One piece wider than a whole line: split it by letters, with a hyphen
            while (cur.length > 1 && m(cur) > maxW) {
                var e = cur.length - 1
                while (e > 1 && m(cur.substring(0, e) + "-") > maxW) e--
                out.add(cur.substring(0, e) + "-"); cur = cur.substring(e)
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    // Steps tried in order: (text scale, most name lines allowed, icon scale). Text never gets smaller
    // than the last step: past that the tile grows instead.
    private val WIDE_STEPS = listOf(
        Triple(1f, 1, 1f), Triple(0.9f, 1, 1f), Triple(0.82f, 1, 1f),
        Triple(0.92f, 2, 1f), Triple(0.85f, 2, 0.85f), Triple(0.82f, 3, 0.8f), Triple(0.8f, 99, 0.72f))
    private val LARGE_STEPS = listOf(
        Triple(1f, 1, 1f), Triple(0.92f, 1, 1f),
        Triple(1f, 2, 1f), Triple(0.92f, 2, 0.85f), Triple(0.88f, 3, 0.75f), Triple(0.85f, 99, 0.6f))
    private val SMALL_STEPS = listOf(
        Triple(1f, 1, 1f), Triple(0.92f, 1, 1f),
        Triple(1f, 2, 0.8f), Triple(0.92f, 2, 0.72f), Triple(0.88f, 3, 0.64f), Triple(0.85f, 99, 0.56f))

    /** True if every word fits on a line by itself (so nothing has to be split mid-word). */
    private fun wordsFit(s: String, maxW: Float, m: (String) -> Float): Boolean =
        s.split(Regex("[\\s/-]+")).all { it.isEmpty() || m(it) <= maxW }

    /**
     * Lays out [name] and [sub] for a tile [w] × [h] (the user's chosen height is [baseH]; [h] may be taller).
     * [d] is the screen density (1 dp). Returns the first arrangement that fits, or the most compact one
     * with fits = false.
     */
    fun plan(name: String, sub: String, w: Float, h: Float, baseH: Float, cellW: Float, d: Float, m: Measure): Plan {
        val kind = kindOf(w, baseH, cellW)
        when (kind) {
            Kind.WIDE -> {
                val nameBase = min(18 * d, baseH * 0.24f)
                val subBase = min(11.5f * d, baseH * 0.16f)
                val iconBase = min(baseH - 22 * d, 56 * d)
                var last: Plan? = null
                for ((s, maxLines, ic) in WIDE_STEPS) {
                    val isz = iconBase * ic
                    val tw = w - (14 * d + isz + 14 * d) - 12 * d
                    val ns = nameBase * s; val ss = subBase * max(0.86f, s)
                    val nl = wrap(name, tw) { m.width(it, ns, NAME_SPACING) }
                    val sl = wrap(sub, tw) { m.width(it, ss, SUB_SPACING) }
                    val p = Plan(kind, nl, ns, sl, ss, isz, 0f, false)
                    last = p
                    if (nl.size > maxLines) continue
                    if (s != WIDE_STEPS.last().first && !(wordsFit(name, tw) { m.width(it, ns, NAME_SPACING) } && wordsFit(sub, tw) { m.width(it, ss, SUB_SPACING) })) continue
                    if (blockHeight(p, d) <= h - 14 * d) return Plan(kind, nl, ns, sl, ss, isz, 0f, true)
                }
                return last!!
            }
            Kind.LARGE, Kind.TALL -> {
                val nameBase = min(18 * d, w * 0.13f)
                val subBase = min(11.5f * d, w * 0.085f)
                val iconBase = min(w * (if (kind == Kind.LARGE) 0.32f else 0.5f), 64 * d)
                val tw = w - 24 * d
                var last: Plan? = null
                for ((s, maxLines, ic) in LARGE_STEPS) {
                    val isz = iconBase * ic
                    val ns = nameBase * s; val ss = subBase * max(0.86f, s)
                    val nl = wrap(name, tw) { m.width(it, ns, NAME_SPACING) }
                    val sl = wrap(sub, tw) { m.width(it, ss, SUB_SPACING) }
                    val p = Plan(kind, nl, ns, sl, ss, isz, 14 * d, false)
                    last = p
                    if (nl.size > maxLines) continue
                    if (s != LARGE_STEPS.last().first && !(wordsFit(name, tw) { m.width(it, ns, NAME_SPACING) } && wordsFit(sub, tw) { m.width(it, ss, SUB_SPACING) })) continue
                    // Room under the icon, above the bottom padding
                    if (blockHeight(p, d) <= h - (14 * d + isz + 10 * d) - 14 * d) return Plan(kind, nl, ns, sl, ss, isz, 14 * d, true)
                }
                return last!!
            }
            Kind.SMALL -> {
                val nameBase = min(11.5f * d, w * 0.12f)
                val iconBase = min(w, baseH) * 0.46f
                val tw = w - 10 * d
                var last: Plan? = null
                for ((s, maxLines, ic) in SMALL_STEPS) {
                    val isz = iconBase * ic
                    val top = if (ic >= 1f) baseH * 0.16f else 8 * d
                    val ns = nameBase * s
                    val nl = wrap(name, tw) { m.width(it, ns, SMALL_SPACING) }
                    val p = Plan(kind, nl, ns, emptyList(), 0f, isz, top, false)
                    last = p
                    if (nl.size > maxLines) continue
                    if (s != SMALL_STEPS.last().first && !wordsFit(name, tw) { m.width(it, ns, SMALL_SPACING) }) continue
                    if (blockHeight(p, d) <= h - (top + isz + 4 * d) - 6 * d) return Plan(kind, nl, ns, emptyList(), 0f, isz, top, true)
                }
                return last!!
            }
        }
    }
}
