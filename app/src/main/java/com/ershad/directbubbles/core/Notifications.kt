package com.ershad.directbubbles.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The app shows NO notification of its own: the chat bubble holds everything.
 * The only channel left is for the "Test" button's simulated messages.
 */
object Notifications {
    const val CH_SIM = "test_chats"
    private const val OLD_CH_BUBBLES = "bubbles"
    private const val OLD_ID_ONGOING = 1001

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        // Clean up the "Chat bubbles active" entry from older versions.
        try {
            nm.cancel(OLD_ID_ONGOING)
            nm.deleteNotificationChannel(OLD_CH_BUBBLES)
        } catch (_: Exception) {
        }
        val sim = NotificationChannel(CH_SIM, "Test chats", NotificationManager.IMPORTANCE_LOW)
        sim.description = "Simulated messages from the Test button only"
        sim.setShowBadge(false)
        nm.createNotificationChannel(sim)
    }
}
