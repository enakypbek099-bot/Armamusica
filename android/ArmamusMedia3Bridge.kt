package com.armamus.app // <- change to your package name

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
 * Usage (inside an Activity):
 *   webView.settings.javaScriptEnabled = true
 *   webView.addJavascriptInterface(ArmamusMedia3Bridge(this, webView), "ArmamusNative")
 * in onDestroy(): bridge.release()
 */
class ArmamusMedia3Bridge(private val ctx: Context, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private val dir = File(ctx.cacheDir, "armamus_exo").apply { mkdirs() }
    private val keepFiles = 6

    private val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val ticker = object : Runnable {
        override fun run() {
            if (player.isPlaying) js("time")
            main.postDelayed(this, 250)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> js("ready")
                    Player.STATE_ENDED -> js("ended")
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                js(if (isPlaying) "play" else "pause")
            }
            override fun onPlayerError(error: PlaybackException) {
                js("error")
            }
        })
        main.post(ticker)
    }

    private fun js(type: String) {
        val pos = player.currentPosition.coerceAtLeast(0)
        val dur = if (player.duration == C.TIME_UNSET) -1 else player.duration
        web.post { web.evaluateJavascript("window.__exoEvent&&window.__exoEvent('$type',$pos,$dur)", null) }
    }

    private fun safe(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun fileOf(key: String) = File(dir, safe(key))
    private fun doneOf(key: String) = File(dir, safe(key) + ".done")

    // ---------- receiving the file (JS sends it in chunks) ----------

    @JavascriptInterface
    fun has(key: String): Boolean = doneOf(key).exists() && fileOf(key).length() > 0

    @JavascriptInterface
    fun begin(key: String) {
        doneOf(key).delete()
        fileOf(key).delete()
    }

    @JavascriptInterface
    fun chunk(key: String, base64: String) {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        FileOutputStream(fileOf(key), true).use { it.write(bytes) }
    }

    // ---------- playback ----------

    /** ext - file extension (eac3, ec3, ac3, ac4, m4a, mp4 ...) */
    @JavascriptInterface
    fun open(key: String, ext: String, autoplay: Boolean) {
        val src = fileOf(key)
        if (!src.exists()) { js("error"); return }
        // ExoPlayer sniffs content, but a proper file extension helps raw .ec3/.ac3 files
        val named = File(dir, safe(key) + "." + ext.ifEmpty { "bin" })
        if (!named.exists()) src.copyTo(named, overwrite = true)
        doneOf(key).writeText("1")
        trim()
        main.post {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(named)))
            player.prepare()
            player.playWhenReady = autoplay
        }
    }

    @JavascriptInterface fun play() { main.post { player.play() } }
    @JavascriptInterface fun pause() { main.post { player.pause() } }
    @JavascriptInterface fun seek(ms: Long) { main.post { player.seekTo(ms) } }
    @JavascriptInterface fun setRate(rate: Float) { main.post { player.playbackParameters = PlaybackParameters(rate) } }
    @JavascriptInterface fun setVolume(v: Float) { main.post { player.volume = v.coerceIn(0f, 1f) } }
    @JavascriptInterface fun stop() { main.post { player.stop() } }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".done") }
            ?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keepFiles * 2).forEach { f ->
            f.delete(); File(dir, f.nameWithoutExtension + ".done").delete()
        }
    }

    fun release() {
        main.removeCallbacks(ticker)
        player.release()
    }
}
