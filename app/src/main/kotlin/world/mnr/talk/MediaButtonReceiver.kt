package world.mnr.talk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent

class MediaButtonReceiver : BroadcastReceiver() {
    companion object {
        private var controller: MediaControllerCompat? = null

        fun setToken(token: MediaSessionCompat.Token, context: Context) {
            controller = MediaControllerCompat(context, token)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_MEDIA_BUTTON) {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                val ctrl = controller ?: return
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> ctrl.transportControls.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> ctrl.transportControls.pause()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> ctrl.transportControls.skipToNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ctrl.transportControls.skipToPrevious()
                }
            }
        }
    }
}
