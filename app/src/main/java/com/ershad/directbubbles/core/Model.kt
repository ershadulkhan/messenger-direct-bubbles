package com.ershad.directbubbles.core

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap

class ChatMessage(
    val text: String,
    val time: Long,
    val outgoing: Boolean,
    /** Who wrote it (only used in group chats). */
    val sender: String? = null,
    var status: Int = STATUS_OK,
    /** KIND_TEXT or KIND_CALL ("Audio call", "Missed video call"...). */
    val kind: Int = KIND_TEXT,
) {
    companion object {
        const val KIND_TEXT = 0
        const val KIND_CALL = 1
        const val STATUS_OK = 0
        const val STATUS_FAILED = 1
        const val STATUS_SENDING = 2
    }
}

class Conversation(val key: String, val pkg: String, var title: String) {
    val messages = ArrayList<ChatMessage>()
    /** Signatures of notification messages already stored (prevents duplicates when apps re-post). */
    val seen = LinkedHashSet<String>()
    var unread = 0
    var lastTime = 0L
    var isGroup = false
    var simulated = false
    /** Active = has a bubble on screen (cleared when the user drags the bubble to the X). */
    var active = false
    var avatar: Bitmap? = null

    /** Cached in memory so several replies can be sent even after the system notification is gone. */
    var replyAction: Notification.Action? = null
    var contentIntent: PendingIntent? = null
    /** The app's own "Mark as read" button (used by Auto Mark as Read). */
    var markReadAction: Notification.Action? = null

    /** Keys of this chat's live notifications (to clear them / match removals). In memory only. */
    val notifKeys = LinkedHashSet<String>()
    /** Needs saving to disk. */
    @Transient var dirty = true

    val last: ChatMessage? get() = messages.lastOrNull()

    fun preview(): String {
        val m = last ?: return ""
        val t = (if (m.kind == ChatMessage.KIND_CALL) "📞 " else "") + m.text.replace('\n', ' ')
        return when {
            m.outgoing -> "You: $t"
            isGroup && m.sender != null -> "${m.sender}: $t"
            else -> t
        }
    }
}

/** A non-DM notification shown in an app's Activity tab (Instagram: new instants, follows, requests...). */
class ActivityItem(
    val pkg: String,
    val title: String,
    val text: String,
    val time: Long,
) {
    var avatar: android.graphics.Bitmap? = null
    var contentIntent: PendingIntent? = null
    var unread = true
}
