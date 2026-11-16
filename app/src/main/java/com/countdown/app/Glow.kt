package com.countdown.app

import android.graphics.Color

/** The loot-style glow around the widget. "Auto" gets rarer as the date gets closer. */
enum class Glow(val label: String, val drawable: Int, val color: Int) {
    NONE("No glow", R.drawable.widget_bg, Color.parseColor("#FFB547")),
    COMMON("Common (white)", R.drawable.glow_common, Color.parseColor("#C9CED6")),
    UNCOMMON("Uncommon (green)", R.drawable.glow_uncommon, Color.parseColor("#4BE36B")),
    RARE("Rare (blue)", R.drawable.glow_rare, Color.parseColor("#3DA5FF")),
    EPIC("Epic (purple)", R.drawable.glow_epic, Color.parseColor("#C65BFF")),
    LEGENDARY("Legendary (gold)", R.drawable.glow_legendary, Color.parseColor("#FFB13B")),
    AUTO("Auto: rarer as it gets closer", R.drawable.glow_legendary, Color.parseColor("#FFB13B"));

    /** The glow actually shown, resolving AUTO from how much time is left. */
    fun resolve(remainingMs: Long): Glow {
        if (this != AUTO) return this
        val hours = remainingMs / 3_600_000.0
        return when {
            hours > 24 * 30 -> COMMON
            hours > 24 * 7 -> UNCOMMON
            hours > 24 -> RARE
            hours > 1 -> EPIC
            else -> LEGENDARY
        }
    }
}
