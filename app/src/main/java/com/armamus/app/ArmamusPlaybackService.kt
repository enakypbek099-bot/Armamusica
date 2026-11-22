package com.armamus.app

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media service: keeps the process (and the Dolby ExoPlayer) alive while the app is in the
 * background and shows the media notification with prev / play / next.
 */
class ArmamusPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val s = MediaSession.Builder(this, SessionPlayer(ArmamusPlayer.get(this)))
            .setSessionActivity(pending)
            .build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // App swiped away from recents: stop playback and the service (the page state is gone anyway)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = ArmamusPlayer.get(this)
        p.pause()
        p.stop()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
