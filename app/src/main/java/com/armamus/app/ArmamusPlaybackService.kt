package com.armamus.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground media service: keeps the process alive while the app is in the background
 * and shows the media notification (cover, title, seek bar, shuffle / prev / play / next / close)
 * for EVERY track, both the ones played by the web page and the Dolby ones played by ExoPlayer.
 */
class ArmamusPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    private val shuffleCmd = SessionCommand(CMD_SHUFFLE, Bundle.EMPTY)
    private val closeCmd = SessionCommand(CMD_CLOSE, Bundle.EMPTY)

    private fun layout(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder()
            .setDisplayName("Shuffle")
            .setIconResId(R.drawable.ic_shuffle)
            .setSessionCommand(shuffleCmd)
            .build(),
        CommandButton.Builder()
            .setDisplayName("Close")
            .setIconResId(R.drawable.ic_close)
            .setSessionCommand(closeCmd)
            .build()
    )

    private val callback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(shuffleCmd)
                .add(closeCmd)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(layout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SHUFFLE -> ArmamusPlayer.remote?.invoke("shuffle")
                CMD_CLOSE -> closeAll()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val s = MediaSession.Builder(this, ArmamusMirror.player)
            .setCallback(callback)
            .setCustomLayout(layout())
            .setSessionActivity(pending)
            .build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** the X button: stop the music, remove the notification, free everything */
    private fun closeAll() {
        ArmamusPlayer.stopAll()
        ArmamusPlayer.remote?.invoke("pause")
        ArmamusMirror.player.clear()
        ArmamusHost.destroyIfDetached()
        stopSelf()
    }

    // App swiped away from recents: the music KEEPS playing (the service and the page stay alive).
    // Only when nothing is playing, everything is released.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ArmamusPlayer.isPlayingAny()) return
        closeAll()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val CMD_SHUFFLE = "armamus.shuffle"
        const val CMD_CLOSE = "armamus.close"
    }
}
