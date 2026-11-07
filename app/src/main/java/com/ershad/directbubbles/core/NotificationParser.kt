package com.ershad.directbubbles.core

import android.app.Notification
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.ershad.directbubbles.sim.Simulator

/** Turns a messaging-app notification into chat messages (or rejects it as noise). */
object NotificationParser {

    private val SKIP_CATEGORIES = setOf(
        "call", "missed_call", "progress", "service", "transport", "sys", "status", "alarm",
        "reminder", "event", "promo", "recommendation", "navigation", "stopwatch",
        "location_sharing", "workout", "err"
    )

    /** System / status texts that are not real messages. */
    private val NOISE = listOf(
        Regex("^\\d+ new messages?$", RegexOption.IGNORE_CASE),
        Regex("\\d+ messages? from \\d+ chats?", RegexOption.IGNORE_CASE),
        Regex("^checking for (new )?messages", RegexOption.IGNORE_CASE),
        Regex("whatsapp web is (currently )?active", RegexOption.IGNORE_CASE),
        Regex("^(backup|backing up|restoring|downloading|uploading|syncing)", RegexOption.IGNORE_CASE),
        Regex("^sending (a )?(photo|video|message|file)", RegexOption.IGNORE_CASE),
        Regex("^(incoming|ongoing|missed) (voice |video )?call", RegexOption.IGNORE_CASE),
        Regex("^(calling|ringing)\\b", RegexOption.IGNORE_CASE),
        Regex("^this message was deleted$", RegexOption.IGNORE_CASE),
    )

    /** "Family (5 messages)" -> "Family" */
    private val COUNT_SUFFIX = Regex("\\s*\\(\\d+\\s+[^)]*\\)$")

    fun isNoise(s: String): Boolean {
        val t = s.trim()
        return t.isEmpty() || NOISE.any { it.containsMatchIn(t) }
    }

    @Suppress("DEPRECATION")
    fun parse(ctx: Context, sbn: StatusBarNotification): ChatStore.Batch? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val own = sbn.packageName == ctx.packageName
        val simPkg = extras.getString(Simulator.EXTRA_SIM_PKG)
        if (own && simPkg == null) return null          // our own status notification
        val pkg = if (own && simPkg != null) simPkg else sbn.packageName

        // Filter out noise: group summaries, ongoing/system notifications.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        val cat = n.category
        if (cat != null && cat in SKIP_CATEGORIES) return null

        val reply = findReplyAction(n)
        val items = ArrayList<ChatStore.Item>()
        var isGroup = false
        var title: String? = null
        var personIcon: Icon? = null

        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val styled = msgs != null && msgs.isNotEmpty()
        if (msgs != null && styled) {
            // MessagingStyle: exact sender + timestamp for each message.
            isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            val me = extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)
            val meName = me?.name?.toString() ?: extras.getCharSequence("android.selfDisplayName")?.toString()
            val meKey = me?.key
            var firstSender: String? = null
            for (p in msgs) {
                val b = p as? Bundle ?: continue
                var text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    if (b.getString("type") != null) text = "📎 Attachment" else continue
                }
                val time = b.getLong("time", sbn.postTime)
                val person = b.getParcelable<Person>("sender_person")
                val senderName = (person?.name ?: b.getCharSequence("sender"))?.toString()
                val outgoing = senderName == null ||
                    (meKey != null && person?.key == meKey) ||
                    (meName != null && senderName == meName)
                if (!outgoing) {
                    if (isNoise(text)) continue
                    if (firstSender == null) firstSender = senderName
                    if (person?.icon != null) personIcon = person.icon
                }
                items.add(
                    ChatStore.Item(
                        "$time|${senderName ?: "me"}|${text.hashCode()}", text, time, outgoing,
                        if (isGroup && !outgoing) senderName else null, false
                    )
                )
            }
            val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val plainTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            title = (if (isGroup) convTitle else null) ?: firstSender ?: convTitle ?: plainTitle
        } else {
            // Plain notification: title = person, text = message. Only trusted if it has a Reply action.
            if (reply == null && !own) return null
            val t = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
            if (t.isEmpty() || isNoise(body)) return null
            val time = if (n.`when` > 0) n.`when` else sbn.postTime
            items.add(ChatStore.Item("$time|$t|${body.hashCode()}", body, time, false, null, true))
            title = t
        }

        if (items.isEmpty() || items.none { !it.outgoing }) return null
        val cleanTitle = title?.replace(COUNT_SUFFIX, "")?.trim()
        if (cleanTitle.isNullOrEmpty()) return null

        val idPart = n.shortcutId?.takeIf { it.isNotBlank() } ?: cleanTitle
        val key = "$pkg|$idPart"

        // Profile picture: the sender's Person icon, else the notification's large icon.
        val icon: Icon? = if (isGroup) (n.getLargeIcon() ?: personIcon) else (personIcon ?: n.getLargeIcon())
        var bmp: Bitmap? = icon?.let { loadIcon(ctx, it) }
        if (bmp == null) bmp = extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap
        val avatar = bmp?.let { Avatars.circle(it) }

        return ChatStore.Batch(key, pkg, cleanTitle, isGroup, own, avatar, items, reply, n.contentIntent, findMarkReadAction(n))
    }

    private fun loadIcon(ctx: Context, icon: Icon): Bitmap? = try {
        icon.loadDrawable(ctx)?.let { Avatars.fromDrawable(it) }
    } catch (e: Exception) {
        null
    }

    private val MARK_READ_TITLE = Regex("(?i)(mark.*read|^read$|als gelesen|marcar como le)")

    /** Finds the app's "Mark as read" action (WhatsApp, Messenger, Telegram, Signal...). */
    fun findMarkReadAction(n: Notification): Notification.Action? {
        val all = ArrayList<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        try {
            all.addAll(Notification.WearableExtender(n).actions)
        } catch (e: Exception) {
        }
        return all.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }
            ?: all.firstOrNull { a ->
                a.remoteInputs.isNullOrEmpty() && a.title?.toString()?.let { MARK_READ_TITLE.containsMatchIn(it.trim()) } == true
            }
    }

    /** Finds the inline-reply action, checking both normal actions and Wear OS actions. */
    fun findReplyAction(n: Notification): Notification.Action? {
        val all = ArrayList<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        try {
            all.addAll(Notification.WearableExtender(n).actions)
        } catch (e: Exception) {
            // ignore malformed wearable extras
        }
        fun Notification.Action.canType(): Boolean =
            remoteInputs?.any { it.allowFreeFormInput } == true
        return all.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && it.canType() }
            ?: all.firstOrNull { it.canType() }
    }
}
