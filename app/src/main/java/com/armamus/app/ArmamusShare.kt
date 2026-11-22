package com.armamus.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Real Android "Share": the audio file itself goes to WhatsApp / Telegram / ... */
object ArmamusShare {

    private fun launch(ctx: Context, intent: Intent) {
        val chooser = Intent.createChooser(intent, null)
        val a = ArmamusHost.activity
        if (a != null) {
            a.startActivity(chooser)
        } else {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        }
    }

    private fun shareDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, "share")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return dir
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "audio" }

    private fun mimeOf(name: String, mime: String): String {
        if (mime.isNotEmpty()) return mime
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/*"
    }

    private fun sendFileUri(ctx: Context, out: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(out.name, mime)
            putExtra(Intent.EXTRA_STREAM, uri)
            if (title.isNotEmpty()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            clipData = ClipData.newRawUri(out.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(ctx, send)
    }

    /** file that the person picked: copy it to the cache and share the copy */
    fun sendUri(ctx: Context, src: Uri, name: String, mime: String, title: String): Boolean {
        return try {
            val out = File(shareDir(ctx), safeName(name))
            val input = ctx.contentResolver.openInputStream(src) ?: return false
            input.use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            sendFileUri(ctx, out, mime, title)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** file that is already in our cache (sent by the page in pieces) */
    fun sendFile(ctx: Context, src: File, name: String, mime: String, title: String): Boolean {
        return try {
            if (!src.exists()) return false
            val out = File(shareDir(ctx), safeName(name))
            src.copyTo(out, overwrite = true)
            sendFileUri(ctx, out, mime, title)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendText(ctx: Context, title: String, text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
            }
            launch(ctx, send)
        } catch (e: Exception) {
            // no app to share with
        }
    }
}
