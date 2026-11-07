package com.ershad.directbubbles.sim

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Receives replies sent to the test chats (exactly like WhatsApp/Instagram receive them). */
class TestReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Simulator.KEY_REPLY)?.toString() ?: return
        val name = intent.getStringExtra(Simulator.EXTRA_SIM_NAME) ?: return
        val app = context.applicationContext
        Simulator.profiles.firstOrNull { it.name == name }?.let {
            app.getSystemService(NotificationManager::class.java).cancel(it.id)
        }
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                Simulator.autoReply(app, name, text)
            } finally {
                pending.finish()
            }
        }, 1800)
    }
}
