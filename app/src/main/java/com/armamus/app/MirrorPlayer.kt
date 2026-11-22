package com.armamus.app

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.State
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * One shared instance for the whole process.
 * The page (WebView) tells it what is playing; the media session shows it in the notification
 * and on the lock screen, and forwards the buttons back to the page.
 */
object ArmamusMirror {
    val player: MirrorPlayer by lazy { MirrorPlayer() }
}

/**
 * A "player" that plays nothing itself. It only mirrors the state of the web page
 * (title, artist, cover, play / pause, position) so Android can draw the media notification.
 * All calls must be made on the main thread.
 */
class MirrorPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    private var hasItem = false
    private var title = ""
    private var artist = ""
    private var art: ByteArray? = null
    private var playing = false
    private var posMs = 0L
    private var durMs = C.TIME_UNSET

    fun setMeta(newTitle: String, newArtist: String, newArt: ByteArray?) {
        hasItem = true
        title = newTitle
        artist = newArtist
        art = newArt
        invalidateState()
    }

    fun setState(isPlaying: Boolean, position: Long, duration: Long) {
        playing = isPlaying
        posMs = position.coerceAtLeast(0L)
        durMs = if (duration > 0L) duration else C.TIME_UNSET
        invalidateState()
    }

    fun clear() {
        hasItem = false
        playing = false
        posMs = 0L
        durMs = C.TIME_UNSET
        art = null
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE
            )
            .build()
        val builder = State.Builder().setAvailableCommands(commands)
        if (!hasItem) {
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }

        val metaBuilder = MediaMetadata.Builder()
            .setTitle(if (title.isEmpty()) "Armamus" else title)
            .setArtist(artist)
        val cover = art
        if (cover != null) {
            metaBuilder.setArtworkData(cover, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val meta = metaBuilder.build()

        val current = MediaItemData.Builder("current")
            .setMediaItem(MediaItem.Builder().setMediaId("current").setMediaMetadata(meta).build())
            .setMediaMetadata(meta)
            .setDurationUs(if (durMs == C.TIME_UNSET) C.TIME_UNSET else durMs * 1000L)
            .setIsSeekable(true)
            .build()

        // previous and next are empty placeholders: they only make the prev / next buttons available
        return builder
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("previous").build(),
                    current,
                    MediaItemData.Builder("next").build()
                )
            )
            .setCurrentMediaItemIndex(1)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(posMs)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        ArmamusPlayer.remote?.invoke(if (playWhenReady) "play" else "pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playing = false
        ArmamusPlayer.remote?.invoke("pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> ArmamusPlayer.remote?.invoke("next")

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> ArmamusPlayer.remote?.invoke("prev")

            else -> if (positionMs != C.TIME_UNSET) {
                posMs = positionMs
                ArmamusPlayer.remote?.invoke("seek:$positionMs")
            }
        }
        return Futures.immediateVoidFuture()
    }
}
