package com.ershad.directbubbles.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ershad.directbubbles.core.ChatStore
import com.ershad.directbubbles.core.NotificationParser
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.overlay.OverlayController

/**
 * Event-driven: Android binds this service and calls it ONLY when a notification is posted.
 * No polling, no timers, no wake locks -> zero CPU while idle.
 */
class BubbleNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        ChatStore.init(this)
        OverlayController.init(this)
    }

    override fun onListenerDisconnected() {
        connected = false
        try {
            requestRebind(ComponentName(this, BubbleNotificationListener::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "rebind failed", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val pkg = sbn.packageName
            if (pkg != packageName && pkg !in Prefs.enabledApps(this)) return
            val batch = NotificationParser.parse(this, sbn) ?: return
            if (Prefs.isFiltered(this, batch.pkg, batch.title)) return   // per-app keyword filters
            ChatStore.init(this)
            val added = ChatStore.ingest(batch)
            if (added > 0) {
                OverlayController.init(this)
                OverlayController.onNewMessages(batch.key)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to handle notification", t)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally nothing: the reply action stays cached so you can keep replying.
    }

    companion object {
        private const val TAG = "BubbleListener"
        @Volatile
        var connected = false
    }
}
