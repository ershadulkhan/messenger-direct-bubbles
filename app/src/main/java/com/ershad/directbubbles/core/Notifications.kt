package com.ershad.directbubbles.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import com.ershad.directbubbles.R
import com.ershad.directbubbles.overlay.OpenPopupActivity
import com.ershad.directbubbles.service.BubbleKeepAliveService

object Notifications {
    const val CH_BUBBLES = "bubbles"
    const val CH_SIM = "test_chats"
    const val ID_ONGOING = 1001

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val bubbles = NotificationChannel(CH_BUBBLES, "Active chat bubbles", NotificationManager.IMPORTANCE_LOW)
        bubbles.description = "Silent entry shown only while a chat bubble is on screen. Tap it to open the inbox."
        bubbles.setShowBadge(false)
        val sim = NotificationChannel(CH_SIM, "Test chats", NotificationManager.IMPORTANCE_LOW)
        sim.description = "Simulated messages from the Test button"
        sim.setShowBadge(false)
        nm.createNotificationChannel(bubbles)
        nm.createNotificationChannel(sim)
    }

    fun buildOngoing(c: Context): Notification {
        val convs = ChatStore.activeOnes()
        val open = PendingIntent.getActivity(
            c, 10, OpenPopupActivity.intent(c, convs.firstOrNull()?.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openInbox = PendingIntent.getActivity(
            c, 12, OpenPopupActivity.intent(c, null),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hide = PendingIntent.getService(
            c, 11, Intent(c, BubbleKeepAliveService::class.java).setAction(BubbleKeepAliveService.ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val unread = convs.sumOf { it.unread }
        val title = when (convs.size) {
            0 -> "Chat bubbles"
            1 -> convs[0].title
            else -> "${convs.size} chats" + if (unread > 0) " · $unread unread" else ""
        }
        val text = convs.firstOrNull()?.preview() ?: "Tap to open your inbox"

        val b = Notification.Builder(c, CH_BUBBLES)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(c.getColor(R.color.brand_blue))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_inbox), "Open inbox", openInbox).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_close), "Hide bubbles", hide).build())
        convs.firstOrNull()?.let { b.setLargeIcon(Avatars.forConv(it)) }
        if (convs.size > 1) {
            val style = Notification.InboxStyle()
            for (cv in convs.take(6)) {
                val line = SpannableStringBuilder(cv.title)
                line.setSpan(StyleSpan(Typeface.BOLD), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                line.append("  ").append(cv.preview())
                style.addLine(line)
            }
            b.setStyle(style)
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    fun refresh(c: Context) {
        if (!BubbleKeepAliveService.running) return
        try {
            c.getSystemService(NotificationManager::class.java).notify(ID_ONGOING, buildOngoing(c))
        } catch (e: Exception) {
            // notifications blocked: nothing to update
        }
    }

    fun cancel(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(ID_ONGOING)
    }
}
