package com.armamus.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap

/**
 * window.ArmamusNative bridge for the HTML player.
 * Plays Dolby Atmos (E-AC-3 JOC / AC-4) and other files through Media3 ExoPlayer.
 *
 * The file gets into the cache either by a fast native copy (importUri: the file the person picked is copied
 * by Android itself) or, if the address is unknown, by the page in chunks (begin -> chunk... -> commit).
 * Files stay in the cache, so a track that was already prepared starts instantly.
 * queueNext() lets the player move to the next track by itself, even when the page is asleep in the background.
 */
class ArmamusMedia3Bridge(private val ctx: Context, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private val dir = File(ctx.cacheDir, "armamus_exo").apply { mkdirs() }
    private val keepFiles = 12
    private val player: ExoPlayer = ArmamusPlayer.get(ctx)
    private val locks = ConcurrentHashMap<String, Any>()
    private val indexes = ConcurrentHashMap<String, RawAudioIndex>()

    @Volatile
    private var alive = true

    // ---- state of the track that ExoPlayer plays (main thread only, except the two keys) ----
    private var current: MediaItem? = null
    private var queued: MediaItem? = null
    private var rawIdx: RawAudioIndex? = null
    private var baseMs = 0L          // raw files after a seek: time of the frame where playback was restarted
    private var seekReload = false   // a seek is reloading the player: do not tell the page about pause / play
    private var lastMirror = 0L

    @Volatile
    private var curKey = ""

    @Volatile
    private var nextKey = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (!alive) return
            if (player.isPlaying) {
                if (ArmamusHost.visible) js("time")
                if (SystemClock.uptimeMillis() - lastMirror > 4000L) pushMirror()
            }
            main.postDelayed(this, if (ArmamusHost.visible) 250L else 1000L)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (seekReload) {
                        seekReload = false
                        js(if (player.isPlaying) "play" else "pause")
                    }
                    js("ready")
                }
                Player.STATE_ENDED -> js("ended")
            }
            pushMirror()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!seekReload) js(if (isPlaying) "play" else "pause")
            pushMirror()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pushMirror()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // the player moved to the queued track on its own
            if (mediaItem != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                current = mediaItem
                queued = null
                rawIdx = indexes[mediaItem.mediaId]
                baseMs = 0L
                ArmamusPlayer.seekUri = null
                ArmamusPlayer.seekOffset = 0L
                curKey = mediaItem.mediaId
                nextKey = ""
                js("advance", mediaItem.mediaId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            js("error")
        }
    }

    init {
        player.addListener(listener)
        ArmamusPlayer.seekExo = { ms -> doSeek(ms) }
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

    // ---------- time and state ----------

    private fun curPos(): Long = baseMs + player.currentPosition.coerceAtLeast(0L)

    private fun curDur(): Long {
        val r = rawIdx
        if (r != null) return r.durationMs
        return if (player.duration == C.TIME_UNSET) -1L else player.duration
    }

    // must be called on the main thread
    private fun js(type: String, extra: String = "") {
        val pos = curPos()
        val dur = curDur()
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

    /** While Dolby plays, the notification follows ExoPlayer directly: it does not depend on the page (which Android may slow down). */
    private fun pushMirror() {
        if (!ArmamusPlayer.exoOwns) return
        val st = player.playbackState
        val playing = player.playWhenReady && st != Player.STATE_ENDED && st != Player.STATE_IDLE
        ArmamusMirror.player.setState(playing, curPos(), curDur())
        lastMirror = SystemClock.uptimeMillis()
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun partOf(key: String) = File(dir, safe(key) + ".part")
    private fun doneOf(key: String) = File(dir, safe(key) + ".done")
    private fun finalOf(key: String, ext: String) = File(dir, safe(key) + "." + safe(ext).ifEmpty { "bin" })

    // ---------- getting the file (fast copy, or the page sends it in chunks) ----------

    @JavascriptInterface
    fun cached(key: String, ext: String): Boolean {
        val f = finalOf(key, ext)
        return doneOf(key).exists() && f.exists() && f.length() > 0L
    }

    /** Fast path: Android copies the file the person picked straight into the cache. false = address unknown, the page must send the file. */
    @JavascriptInterface
    fun importUri(key: String, ext: String, name: String, size: Long): Boolean {
        if (cached(key, ext)) return true
        val uri = ArmamusFiles.find(name, size) ?: return false
        return copyToCache(key, ext, uri)
    }

    /** the same, but in the background (does not make the page wait) */
    @JavascriptInterface
    fun prefetchUri(key: String, ext: String, name: String, size: Long) {
        Thread { importUri(key, ext, name, size) }.start()
    }

    private fun copyToCache(key: String, ext: String, uri: Uri): Boolean {
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            if (cached(key, ext)) return true
            val part = partOf(key)
            part.delete()
            doneOf(key).delete()
            try {
                val input = ctx.contentResolver.openInputStream(uri) ?: return false
                input.use { i -> FileOutputStream(part).use { o -> i.copyTo(o, 256 * 1024) } }
            } catch (e: Exception) {
                part.delete()
                return false
            }
            val target = finalOf(key, ext)
            target.delete()
            if (part.renameTo(target) && target.length() > 0L) {
                doneOf(key).writeText("1")
                trim()
                return true
            }
            return false
        }
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

    // ---------- sharing a file that was sent in chunks ----------

    @JavascriptInterface
    fun shareCached(key: String, ext: String, name: String, mime: String, title: String): Boolean {
        val f = finalOf(key, ext)
        if (!f.exists()) return false
        Thread { ArmamusShare.sendFile(ctx, f, name, mime, title) }.start()
        return true
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

    /** raw .ac3 / .eac3 / .ec3 files cannot be sought by ExoPlayer: build a frame index for them */
    private fun indexFor(key: String, ext: String, file: File): RawAudioIndex? {
        val e = ext.lowercase()
        if (e != "ac3" && e != "eac3" && e != "ec3") return null
        indexes[key]?.let { return it }
        val built = RawAudioIndex.build(file) ?: return null
        indexes[key] = built
        return built
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
        val idx = indexFor(key, ext, file)
        val item = mediaItemOf(key, file, title, artist, art)
        curKey = key
        nextKey = ""
        startService()
        main.post {
            ArmamusPlayer.exoOwns = true
            ArmamusPlayer.seekUri = null
            ArmamusPlayer.seekOffset = 0L
            current = item
            queued = null
            rawIdx = idx
            baseMs = 0L
            seekReload = false
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = autoplay
            pushMirror()
        }
    }

    /** The next track (already in the cache): the player switches to it by itself when the current one ends. */
    @JavascriptInterface
    fun queueNext(key: String, ext: String, title: String, artist: String, art: String) {
        val file = finalOf(key, ext)
        if (!file.exists()) return
        file.setLastModified(System.currentTimeMillis())
        val idx = indexFor(key, ext, file)
        val item = mediaItemOf(key, file, title, artist, art)
        nextKey = key
        main.post {
            if (idx != null) indexes[key] = idx
            queued = item
            val count = player.mediaItemCount
            val index = player.currentMediaItemIndex
            if (count > 0) {
                if (count > index + 1) player.removeMediaItems(index + 1, count)
                player.addMediaItem(item)
            }
        }
    }

    /** The queue changed (shuffle): forget the track that was queued */
    @JavascriptInterface
    fun clearNext() {
        nextKey = ""
        main.post {
            queued = null
            val count = player.mediaItemCount
            val index = player.currentMediaItemIndex
            if (count > index + 1) player.removeMediaItems(index + 1, count)
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
        main.post { doSeek(ms) }
    }

    // main thread
    private fun doSeek(ms: Long) {
        val idx = rawIdx
        val item = current
        if (idx == null || item == null) {
            player.seekTo(ms)
            return
        }
        // raw AC-3 / E-AC-3: restart playback from the frame that matches the time
        val k = idx.indexFor(ms)
        val uri = item.localConfiguration?.uri
        ArmamusPlayer.seekUri = uri
        ArmamusPlayer.seekOffset = idx.offsetOf(k)
        baseMs = idx.timeMs(k)
        val wasPlaying = player.playWhenReady
        seekReload = true
        player.setMediaItem(item)
        val next = queued
        if (next != null) player.addMediaItem(next)
        player.prepare()
        player.playWhenReady = wasPlaying
        // safety: never stay "reloading" forever
        main.postDelayed({ seekReload = false }, 4000L)
        pushMirror()
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
        // only the ExoPlayer is released; the service stays, it also shows the notification for normal tracks
        main.post {
            ArmamusPlayer.exoOwns = false
            ArmamusPlayer.seekUri = null
            ArmamusPlayer.seekOffset = 0L
            player.stop()
            player.clearMediaItems()
            current = null
            queued = null
            rawIdx = null
            baseMs = 0L
            seekReload = false
            curKey = ""
            nextKey = ""
        }
    }

    // ---------- notification for every track (the page tells what is playing) ----------

    @JavascriptInterface
    fun mirrorMeta(title: String, artist: String, art: String) {
        val bytes: ByteArray? = if (art.isEmpty()) {
            null
        } else {
            try {
                Base64.decode(art, Base64.DEFAULT)
            } catch (e: Exception) {
                null
            }
        }
        main.post { ArmamusMirror.player.setMeta(title, artist, bytes) }
    }

    @JavascriptInterface
    fun mirrorState(playing: Boolean, posMs: Long, durMs: Long) {
        if (playing) startService()
        main.post {
            // while Dolby plays, ExoPlayer itself is the source of truth
            if (!ArmamusPlayer.exoOwns) ArmamusMirror.player.setState(playing, posMs, durMs)
        }
    }

    private fun trim() {
        val protect = setOf(safe(curKey), safe(nextKey))
        val files = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".done") && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keepFiles).forEach { f ->
            val base = f.name.substringBeforeLast('.')
            if (base in protect) return@forEach
            f.delete()
            File(dir, "$base.done").delete()
        }
    }

    /** The WebView is being destroyed: stop talking to the page. */
    fun detach() {
        alive = false
        main.removeCallbacks(ticker)
        player.removeListener(listener)
        ArmamusPlayer.remote = null
        ArmamusPlayer.seekExo = null
        main.post { ArmamusMirror.player.clear() }
    }
}
