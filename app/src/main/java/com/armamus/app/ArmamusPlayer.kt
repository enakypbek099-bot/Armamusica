package com.armamus.app

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * One ExoPlayer for the whole process. The bridge (WebView <-> native) and the
 * foreground playback service share it, so Dolby playback keeps running when the
 * app is in the background.
 */
object ArmamusPlayer {
    @Volatile
    private var exo: ExoPlayer? = null

    /** Called when the notification / headset asks for "next" or "previous". The bridge forwards it to the page. */
    @Volatile
    var remote: ((String) -> Unit)? = null

    fun get(ctx: Context): ExoPlayer {
        val cached = exo
        if (cached != null) return cached
        return synchronized(this) {
            val again = exo
            if (again != null) {
                again
            } else {
                val built = ExoPlayer.Builder(ctx.applicationContext)
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
