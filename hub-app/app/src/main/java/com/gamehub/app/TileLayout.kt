package com.gamehub.app

/** Home-screen tile packing (plain Kotlin, no Android, so it can be tested on its own). */
object TileLayout {
    /**
     * Places tiles in order, each in the first free spot (top to bottom, left to right) where it fits.
     * [sizes] holds each tile's [width, height] in cells; widths larger than [cols] are clamped.
     * Returns, for each tile, [column, row, width, height].
     */
    fun pack(sizes: List<IntArray>, cols: Int): Array<IntArray> {
        val c = cols.coerceAtLeast(1)
        val occ = ArrayList<BooleanArray>()
        fun free(r: Int, col: Int, w: Int, h: Int): Boolean {
            for (y in r until r + h) {
                while (occ.size <= y) occ.add(BooleanArray(c))
                for (x in col until col + w) if (occ[y][x]) return false
            }
            return true
        }
        val out = Array(sizes.size) { IntArray(4) }
        for ((i, sz) in sizes.withIndex()) {
            val w = sz[0].coerceIn(1, c)
            val h = sz[1].coerceAtLeast(1)
            var r = 0
            search@ while (true) {
                for (col in 0..c - w) if (free(r, col, w, h)) {
                    for (y in r until r + h) for (x in col until col + w) occ[y][x] = true
                    out[i][0] = col; out[i][1] = r; out[i][2] = w; out[i][3] = h
                    break@search
                }
                r++
            }
        }
        return out
    }
}
