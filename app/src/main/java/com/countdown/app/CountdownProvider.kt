package com.countdown.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

/**
 * Shares the countdown (its name and end time) with NEXUS, so NEXUS can show it ticking on its tile.
 * Read-only, and only for apps signed with the same key as this one (your own apps): any other app gets nothing.
 */
class CountdownProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val c = context ?: return null
        if (!trusted(c)) return null
        return MatrixCursor(arrayOf("name", "target")).apply {
            addRow(arrayOf<Any>(CountdownStore.name(c), CountdownStore.target(c)))
        }
    }

    /** Only this app, or an app signed with the same key. */
    private fun trusted(c: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        return try { c.packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH } catch (_: Exception) { false }
    }

    // Nothing can be changed from outside
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
