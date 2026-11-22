package com.gamehub.app

/**
 * Work done off the screen's thread (loading app lists, icons, card art...) runs at background priority,
 * so it never competes with drawing the screen and its animations. Returns the thread; call start() on it.
 */
object Bg {
    fun thread(block: () -> Unit): Thread = Thread {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
        block()
    }
}
