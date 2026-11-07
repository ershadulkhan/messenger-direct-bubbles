package com.ershad.directbubbles.service

import android.app.Notification
import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ershad.directbubbles.core.ChatStore
import com.ershad.directbubbles.core.Conversation
import com.ershad.directbubbles.core.NotificationParser
import com.ershad.directbubbles.core.Parsed
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.overlay.OverlayController

/**
 * Event-driven: Android binds this service and calls it ONLY when a notification is posted/removed.
 * No polling, no timers, no wake locks, no foreground service -> ~0% CPU while idle.
 */
class BubbleNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        instance = this
        ChatStore.init(this)
        OverlayController.init(this)
        // After the phone killed our app, re-attach the live Reply buttons of chats that still
        // have a notification, so replies keep working without waiting for a new message.
        try {
            for (sbn in activeNotifications ?: emptyArray()) {
                if (!isWanted(sbn.packageName)) continue
                val p = NotificationParser.parse(this, sbn) as? Parsed.Chat ?: continue
                ChatStore.attachActions(p.batch)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reattach failed", t)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        if (instance === this) instance = null
        // Ask Android to reconnect, but never more than once a minute (a rebind loop would drain battery).
        val now = SystemClock.elapsedRealtime()
        if (now - lastRebind > 60_000L) {
            lastRebind = now
            try {
                requestRebind(ComponentName(this, BubbleNotificationListener::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "rebind failed", e)
            }
        }
    }

    private fun isWanted(pkg: String): Boolean = pkg == packageName || pkg in Prefs.enabledApps(this)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val pkg = sbn.packageName
            if (!isWanted(pkg)) return            // ignore every other app immediately (cheap)
            when (val p = NotificationParser.parse(this, sbn) ?: return) {
                is Parsed.Chat -> onChat(sbn, p)
                is Parsed.Call -> onCall(p)
                is Parsed.Activity -> {
                    ChatStore.init(this)
                    ChatStore.addActivity(p.item)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to handle notification", t)
        }
    }

    private fun onChat(sbn: StatusBarNotification, p: Parsed.Chat) {
        val batch = p.batch
        if (Prefs.isFiltered(this, batch.pkg, batch.title)) return   // per-app keyword filters
        ChatStore.init(this)
        val added = ChatStore.ingest(batch)
        // Replies typed while this chat had no reply button go out now.
        if (batch.reply != null) com.ershad.directbubbles.overlay.PopupUi.flushQueued(this, batch.key)
        if (added > 0) {
            OverlayController.init(this)
            OverlayController.onNewMessages(batch.key)
            // "Clear notifications on appear" (global) or per-app "Bubble only".
            if (sbn.packageName != packageName &&
                (Prefs.clearOnAppear(this) || Prefs.hideOriginal(this, batch.pkg))
            ) {
                cancelQuietly(sbn.key)
            }
        }
    }

    private fun onCall(c: Parsed.Call) {
        if (Prefs.isFiltered(this, c.pkg, c.person)) return
        ChatStore.init(this)
        // Put the call into the right person's chat (match by name, then shortcut, then new chat).
        val key = ChatStore.findByTitle(c.pkg, c.person)?.key
            ?: c.shortcutId?.takeIf { it.isNotBlank() }?.let { "${c.pkg}|$it" }?.takeIf { ChatStore.get(it) != null }
            ?: "${c.pkg}|${c.person}"
        val item = ChatStore.Item(
            "call|${c.label}|${c.time / 60_000L}", c.label, c.time, false, null, false,
            com.ershad.directbubbles.core.ChatMessage.KIND_CALL
        )
        ChatStore.ingest(
            ChatStore.Batch(key, c.pkg, c.person, false, false, null, listOf(item), null, c.content, null, null)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        if (sbn == null) return
        try {
            val c: Conversation = ChatStore.onNotificationGone(sbn.key) ?: return
            // The app cleared its notification right after my reply = the reply went through.
            if (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) ChatStore.confirmRecentReplies(c)
            if (c.notifKeys.isNotEmpty()) return   // the chat still has another live notification
            // "Clear unread badge with notification": read it elsewhere -> reset our counter.
            if (Prefs.clearUnreadWithNotif(this)) ChatStore.markRead(c)
            // "Close ChatHeads on app launch": the app cleared its own notification (you opened it)
            // or you tapped the notification -> close that person's chat head.
            val openedApp = reason == REASON_CLICK || reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL
            if (Prefs.closeOnAppLaunch(this) && openedApp) {
                OverlayController.closeChat(c.key, clearNotifications = false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "removal handling failed", t)
        }
    }

    private fun cancelQuietly(key: String) {
        try {
            cancelNotification(key)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "BubbleListener"

        @Volatile
        var connected = false

        @Volatile
        private var instance: BubbleNotificationListener? = null
        private var lastRebind = 0L

        /** Reconnects to Android if the listener was unbound (needed for reliable replies). */
        fun ensureConnected(c: android.content.Context) {
            if (connected) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRebind < 15_000L) return
            lastRebind = now
            try {
                requestRebind(ComponentName(c, BubbleNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }

        /** Clears the given system notifications (Preferences → Clear notifications on removed). */
        fun cancel(keys: Collection<String>) {
            val l = instance ?: return
            for (k in keys) l.cancelQuietly(k)
        }

        /**
         * Looks through the notifications currently in the shade for a fresh Reply action for [conv].
         * Used when the cached action has expired, so a reply can still go out.
         */
        /** All live reply buttons for this chat from the notification shade (several variants may exist). */
        fun freshRepliesFor(conv: Conversation): List<Notification.Action> {
            val l = instance ?: return emptyList()
            return try {
                val wanted = ChatStore.normalize(conv.title)
                val out = ArrayList<Notification.Action>()
                for (sbn in l.activeNotifications ?: emptyArray()) {
                    if (sbn.packageName != conv.pkg) continue
                    val p = NotificationParser.parse(l, sbn) as? Parsed.Chat ?: continue
                    val b = p.batch
                    if (b.key == conv.key || ChatStore.normalize(b.title) == wanted) {
                        ChatStore.attachActions(b)
                        out.addAll(NotificationParser.allReplyActions(sbn.notification))
                    }
                }
                out
            } catch (t: Throwable) {
                emptyList()
            }
        }

        fun freshReplyFor(conv: Conversation): Notification.Action? {
            val l = instance ?: return null
            return try {
                val wanted = ChatStore.normalize(conv.title)
                for (sbn in l.activeNotifications ?: emptyArray()) {
                    if (sbn.packageName != conv.pkg) continue
                    val p = NotificationParser.parse(l, sbn) as? Parsed.Chat ?: continue
                    val b = p.batch
                    if (b.reply != null && (b.key == conv.key || ChatStore.normalize(b.title) == wanted)) {
                        ChatStore.attachActions(b)
                        return b.reply
                    }
                }
                null
            } catch (t: Throwable) {
                null
            }
        }
    }
}
