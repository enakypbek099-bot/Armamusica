package com.armamus.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.FileOutputStream

/**
 * window.ArmamusNative bridge for the HTML player.
 * Plays Dolby Atmos (E-AC-3 JOC / AC-4) and other files through Media3 ExoPlayer.
 *
 * The page sends a file in chunks (begin -> chunk... -> commit), then calls open() / queueNext().
 * Files stay in the cache, so a track that was already sent (or pre-sent) starts instantly.
 * queueNext() lets the player move to the next track by itself, even when the page is asleep in the background.
 */
class ArmamusMedia3Bridge(private val ctx: Context, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private val dir = File(ctx.cacheDir, "armamus_exo").apply { mkdirs() }
    private val keepFiles = 8
    private val player: ExoPlayer = ArmamusPlayer.get(ctx)

    @Volatile
    private var alive = true

    private val ticker = object : Runnable {
        override fun run() {
            if (!alive) return
            if (player.isPlaying) js("time")
            main.postDelayed(this, 250)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> js("ready")
                Player.STATE_ENDED -> js("ended")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            js(if (isPlaying) "play" else "pause")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // the player moved to the queued track on its own
            if (mediaItem != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                js("advance", mediaItem.mediaId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            js("error")
        }
    }

    init {
        player.addListener(listener)
        ArmamusPlayer.remote = { action ->
            web.post {
                try {
                    web.evaluateJavascript("window.__exoRemote&&window.__exoRemote('$action')", null)
                } catch (e: Exception) {
                    // page is gone
                }
            }
        }
        main.post(ticker)
    }

    // must be called on the main thread
    private fun js(type: String, extra: String = "") {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = if (player.duration == C.TIME_UNSET) -1L else player.duration
        val safeExtra = extra.replace("\\", "\\\\").replace("'", "\\'")
        web.post {
            try {
                web.evaluateJavascript(
                    "window.__exoEvent&&window.__exoEvent('$type',$pos,$dur,'$safeExtra')", null
                )
            } catch (e: Exception) {
                // page is gone
            }
        }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun partOf(key: String) = File(dir, safe(key) + ".part")
    private fun doneOf(key: String) = File(dir, safe(key) + ".done")
    private fun finalOf(key: String, ext: String) = File(dir, safe(key) + "." + safe(ext).ifEmpty { "bin" })

    // ---------- receiving the file (the page sends it in chunks) ----------

    @JavascriptInterface
    fun cached(key: String, ext: String): Boolean {
        val f = finalOf(key, ext)
        return doneOf(key).exists() && f.exists() && f.length() > 0L
    }

    @JavascriptInterface
    fun begin(key: String) {
        doneOf(key).delete()
        partOf(key).delete()
    }

    @JavascriptInterface
    fun chunk(key: String, base64: String) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        FileOutputStream(partOf(key), true).use { it.write(bytes) }
    }

    @JavascriptInterface
    fun commit(key: String, ext: String): Boolean {
        val part = partOf(key)
        val target = finalOf(key, ext)
        if (!part.exists()) return target.exists() && target.length() > 0L
        target.delete()
        val moved = part.renameTo(target)
        if (moved && target.length() > 0L) {
            doneOf(key).writeText("1")
            trim()
            return true
        }
        return false
    }

    // ---------- playback ----------

    private fun mediaItemOf(key: String, file: File, title: String, artist: String, art: String): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(if (title.isEmpty()) "Armamus" else title)
            .setArtist(artist)
        if (art.isNotEmpty()) {
            try {
                meta.setArtworkData(Base64.decode(art, Base64.DEFAULT), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            } catch (e: Exception) {
                // no cover in the notification
            }
        }
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun startService() {
        try {
            ctx.startService(Intent(ctx, ArmamusPlaybackService::class.java))
        } catch (e: Exception) {
            // app is in the background and cannot start it now; playback still works
        }
    }

    /** ext - file extension (eac3, ec3, ac3, ac4, m4a, mp4 ...) */
    @JavascriptInterface
    fun open(key: String, ext: String, autoplay: Boolean, title: String, artist: String, art: String) {
        val file = finalOf(key, ext)
        if (!file.exists()) {
            main.post { js("error") }
            return
        }
        file.setLastModified(System.currentTimeMillis())
        val item = mediaItemOf(key, file, title, artist, art)
        startService()
        main.post {
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = autoplay
        }
    }

    /** The next track (already sent to the cache): the player switches to it by itself when the current one ends. */
    @JavascriptInterface
    fun queueNext(key: String, ext: String, title: String, artist: String, art: String) {
        val file = finalOf(key, ext)
        if (!file.exists()) return
        file.setLastModified(System.currentTimeMillis())
        val item = mediaItemOf(key, file, title, artist, art)
        main.post {
            val count = player.mediaItemCount
            val index = player.currentMediaItemIndex
            if (count > 0) {
                if (count > index + 1) player.removeMediaItems(index + 1, count)
                player.addMediaItem(item)
            }
        }
    }

    @JavascriptInterface
    fun play() {
        main.post { player.play() }
    }

    @JavascriptInterface
    fun pause() {
        main.post { player.pause() }
    }

    @JavascriptInterface
    fun seek(ms: Long) {
        main.post { player.seekTo(ms) }
    }

    @JavascriptInterface
    fun setRate(rate: Float) {
        main.post { player.playbackParameters = PlaybackParameters(rate) }
    }

    @JavascriptInterface
    fun setVolume(v: Float) {
        main.post { player.volume = v.coerceIn(0f, 1f) }
    }

    /** Fully release the audio output (used when the page switches to a normal stereo track). */
    @JavascriptInterface
    fun stop() {
        main.post {
            player.stop()
            player.clearMediaItems()
        }
        try {
            ctx.stopService(Intent(ctx, ArmamusPlaybackService::class.java))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun trim() {
        val files = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".done") }
            ?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keepFiles).forEach { f ->
            f.delete()
            File(dir, f.name.substringBeforeLast('.') + ".done").delete()
        }
    }

    /** The activity is going away: stop talking to the page. Playback itself belongs to the service. */
    fun detach() {
        alive = false
        main.removeCallbacks(ticker)
        player.removeListener(listener)
        ArmamusPlayer.remote = null
    }
}
