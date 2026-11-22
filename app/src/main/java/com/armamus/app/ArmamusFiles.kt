package com.armamus.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the system (content://) address of every file the person picked.
 * The page only gets a File object with a name and a size; with this table the native side
 * can find the real file by (name, size) and copy / share it directly, without sending it
 * through JavaScript in small base64 pieces (that was the 5-10 second delay).
 *
 * The table is also saved to disk: after the app was closed, a Dolby track still starts at once,
 * because Android already knows where the file lives (the picker grants a permanent permission).
 */
object ArmamusFiles {
    private val map = ConcurrentHashMap<String, Uri>()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var dirty = false

    private const val PREFS = "armamus_files"
    private const val KEY = "map"
    private const val MAX_ENTRIES = 20000

    private fun key(name: String, size: Long) = "$name|$size"

    /** loads the saved table (once per process) */
    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        try {
            val raw = p.getString(KEY, null) ?: return
            val o = JSONObject(raw)
            val names = o.keys()
            while (names.hasNext()) {
                val k = names.next()
                map.putIfAbsent(k, Uri.parse(o.getString(k)))
            }
        } catch (e: Exception) {
            // a damaged table is simply ignored: files are sent the slow way once
        }
    }

    fun put(name: String, size: Long, uri: Uri) {
        if (name.isNotEmpty() && size > 0L && map.size < MAX_ENTRIES) {
            map[key(name, size)] = uri
            dirty = true
        }
    }

    fun find(name: String, size: Long): Uri? = map[key(name, size)]

    /** writes the table to disk (cheap when nothing changed) */
    @Synchronized
    fun flush() {
        if (!dirty) return
        val p = prefs ?: return
        dirty = false
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v.toString())
            p.edit().putString(KEY, o.toString()).apply()
        } catch (e: Exception) {
            // not saved this time
        }
    }

    /** files chosen through the file picker */
    fun registerPicked(cr: ContentResolver, uris: Array<Uri>?) {
        if (uris == null) return
        for (u in uris) {
            try {
                // permanent permission: the address keeps working after the app is restarted
                try {
                    cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // this provider does not allow it: the address works until the app closes
                }
                cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val name = c.getString(0) ?: ""
                            val size = if (c.isNull(1)) 0L else c.getLong(1)
                            put(name, size, u)
                        }
                    }
            } catch (e: Exception) {
                // this file simply will not use the fast path
            }
        }
        flush()
    }
}
