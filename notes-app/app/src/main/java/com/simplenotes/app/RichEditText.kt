package com.simplenotes.app

import android.content.Context
import android.widget.EditText

/** EditText that reports cursor/selection moves, so the format buttons can light up. */
class RichEditText(context: Context) : EditText(context) {
    var onSelection: (() -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke()
    }
}
