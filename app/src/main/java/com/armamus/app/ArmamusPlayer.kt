package com.armamus.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * One ExoPlayer for the whole process. The bridge (WebView <-> native) and the
 * foreground playback service share it, so Dolby playback keeps running when the
 * app is in the background.
 */
object ArmamusPlayer {
    @Volatile
    private var exo: ExoPlayer? = null

    /** Called when the notification / headset asks for "next", "previous", "shuffle" ... The bridge forwards it to the page. */
    @Volatile
    var remote: ((String) -> Unit)? = null

    /** true while ExoPlayer (Dolby) is the active player: then the notification state comes from ExoPlayer itself, not from the page */
    @Volatile
    var exoOwns = false

    /** seeking in raw AC-3 / E-AC-3 files: the next read of [seekUri] starts at byte [seekOffset] */
    @Volatile
    var seekUri: Uri? = null

    @Volatile
    var seekOffset = 0L

    /** set by the bridge: seek ExoPlayer from the notification without waking the page */
    @Volatile
    var seekExo: ((Long) -> Unit)? = null

    private val resolver = ResolvingDataSource.Resolver { spec: DataSpec ->
        val u = seekUri
        if (u != null && seekOffset > 0L && spec.uri == u) spec.subrange(seekOffset) else spec
    }

    fun get(ctx: Context): ExoPlayer {
        val cached = exo
        if (cached != null) return cached
        return synchronized(this) {
            val again = exo
            if (again != null) {
                again
            } else {
                val app = ctx.applicationContext
                val dataFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(app), resolver)
                val built = ExoPlayer.Builder(app)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataFactory))
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        /* handleAudioFocus = */ true
                    )
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_LOCAL)
                    .build()
                exo = built
                built
            }
        }
    }

    /** main thread only */
    fun controlExo(play: Boolean) {
        val e = exo ?: return
        if (play) e.play() else e.pause()
    }

    /** main thread only: is any music playing right now (Dolby or the page's own player)? */
    fun isPlayingAny(): Boolean {
        val e = exo
        if (e != null && e.isPlaying) return true
        return ArmamusMirror.player.playWhenReady
    }

    /** main thread only: stop everything (the X button of the notification) */
    fun stopAll() {
        exoOwns = false
        seekUri = null
        seekOffset = 0L
        val e = exo
        if (e != null) {
            e.pause()
            e.stop()
            e.clearMediaItems()
        }
    }
}

/**
 * Wrapper used only by the media session: shows prev / next in the notification and on the lock screen.
 * The real queue lives in the web page, so these two buttons are forwarded to it.
 */
class SessionPlayer(base: Player) : ForwardingPlayer(base) {

    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return getAvailableCommands().contains(command)
    }

    override fun hasNextMediaItem(): Boolean = true

    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() {
        ArmamusPlayer.remote?.invoke("next")
    }

    override fun seekToNextMediaItem() {
        ArmamusPlayer.remote?.invoke("next")
    }

    override fun seekToPrevious() {
        ArmamusPlayer.remote?.invoke("prev")
    }

    override fun seekToPreviousMediaItem() {
        ArmamusPlayer.remote?.invoke("prev")
    }
}
