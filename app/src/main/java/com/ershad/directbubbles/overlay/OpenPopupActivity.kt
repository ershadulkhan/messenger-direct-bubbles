package com.ershad.directbubbles.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.ershad.directbubbles.MainActivity

/** Invisible activity: tapping the notification closes the shade and opens the popup chat window. */
class OpenPopupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayController.init(this)
        if (Settings.canDrawOverlays(this)) {
            OverlayController.openPopup(intent?.getStringExtra(EXTRA_KEY))
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_KEY = "key"

        fun intent(c: Context, key: String?): Intent {
            val i = Intent(c, OpenPopupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (key != null) i.putExtra(EXTRA_KEY, key)
            return i
        }
    }
}
