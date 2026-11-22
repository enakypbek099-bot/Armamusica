package com.armamus.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the system (content://) address of every file the person picked.
 * The page only gets a File object with a name and a size; with this table the native side
 * can find the real file by (name, size) and copy / share it directly, without sending it
 * through JavaScript in small base64 pieces (that was the 5-10 second delay).
 */
object ArmamusFiles {
    private val map = ConcurrentHashMap<String, Uri>()

    private fun key(name: String, size: Long) = "$name|$size"

    fun put(name: String, size: Long, uri: Uri) {
        if (name.isNotEmpty() && size > 0L) map[key(name, size)] = uri
    }

    fun find(name: String, size: Long): Uri? = map[key(name, size)]

    /** files chosen through the normal file picker */
    fun registerPicked(cr: ContentResolver, uris: Array<Uri>?) {
        if (uris == null) return
        for (u in uris) {
            try {
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
    }
}
