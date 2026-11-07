package com.ershad.directbubbles.sim

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Receives replies / "Mark as read" for the test chats (exactly like WhatsApp/Instagram receive them). */
class TestReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val name = intent.getStringExtra(Simulator.EXTRA_SIM_NAME) ?: return
        val pkg = intent.getStringExtra(Simulator.EXTRA_SIM_PKG)
        val id = Simulator.profiles.firstOrNull { it.name == name }?.id ?: 9104
        val nm = app.getSystemService(NotificationManager::class.java)

        if (intent.action == Simulator.ACTION_MARK_READ) {
            nm.cancel(id)
            Toast.makeText(app, "✓ Test chat with $name marked as read", Toast.LENGTH_SHORT).show()
            return
        }

        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Simulator.KEY_REPLY)?.toString() ?: return
        nm.cancel(id)
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                Simulator.autoReply(app, name, pkg, text)
            } finally {
                pending.finish()
            }
        }, 1800)
    }
}
