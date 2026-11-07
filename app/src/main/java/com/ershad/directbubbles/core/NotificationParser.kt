package com.ershad.directbubbles.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import com.ershad.directbubbles.sim.Simulator

/** What one notification turned out to be. */
sealed class Parsed {
    /** Chat messages for one conversation. */
    class Chat(val batch: ChatStore.Batch) : Parsed()

    /** An audio/video call (incoming, ongoing or missed). */
    class Call(
        val pkg: String,
        val person: String,
        val label: String,
        val time: Long,
        val shortcutId: String?,
        val sbnKey: String,
        val content: PendingIntent?,
    ) : Parsed()

    /** A non-DM app notification (Instagram: new instants, follows, follow requests...). */
    class Activity(val item: ActivityItem) : Parsed()
}

/** Turns a messaging-app notification into chat messages, a call entry or an activity item. */
object NotificationParser {

    private val SKIP_CATEGORIES = setOf(
        "progress", "service", "transport", "sys", "status", "alarm",
        "reminder", "event", "promo", "recommendation", "navigation", "stopwatch",
        "location_sharing", "workout", "err"
    )

    val ACTIVITY_APPS = setOf("com.instagram.android", "com.instagram.lite")

    /** System / status texts that are not real messages. */
    private val NOISE = listOf(
        Regex("^\\d+ new messages?$", RegexOption.IGNORE_CASE),
        Regex("\\d+ messages? from \\d+ chats?", RegexOption.IGNORE_CASE),
        Regex("^checking for (new )?messages", RegexOption.IGNORE_CASE),
        Regex("whatsapp web is (currently )?active", RegexOption.IGNORE_CASE),
        Regex("^(backup|backing up|restoring|downloading|uploading|syncing|posting|sharing)\\b", RegexOption.IGNORE_CASE),
        Regex("^sending (a )?(photo|video|message|file)", RegexOption.IGNORE_CASE),
        Regex("^this message was deleted$", RegexOption.IGNORE_CASE),
    )

    /** Texts that describe a call, e.g. "Incoming voice call", "Missed video call", "2 missed calls", "Calling…". */
    private val CALL_TEXT = Regex(
        "^(\\d+\\s+)?(incoming|ongoing|missed|outgoing|unanswered)?\\s*(group\\s+)?(voice|video|audio|whatsapp|instagram)?\\s*calls?(\\s+from\\b.*)?\\s*[.…]*$" +
            "|^(calling|ringing)\\s*[.…]*$" +
            "|\\bis calling you\\b" +
            "|^(voice|video|audio) calling\\s*[.…]*$",
        RegexOption.IGNORE_CASE
    )
    private val CALLING_YOU = Regex("^(.*?)\\s+is calling you", RegexOption.IGNORE_CASE)

    /** "Family (5 messages)" -> "Family" */
    private val COUNT_SUFFIX = Regex("\\s*\\(\\d+[^)]*\\)$")

    /** Avatars are re-read at most every 30 min per chat (saves CPU on busy group chats). */
    private val avatarCheckedAt = HashMap<String, Long>()
    private const val AVATAR_REFRESH_MS = 30 * 60_000L

    fun isNoise(s: String): Boolean {
        val t = s.trim()
        return t.isEmpty() || NOISE.any { it.containsMatchIn(t) }
    }

    @Suppress("DEPRECATION")
    fun parse(ctx: Context, sbn: StatusBarNotification): Parsed? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val own = sbn.packageName == ctx.packageName
        val simPkg = extras.getString(Simulator.EXTRA_SIM_PKG)
        if (own && simPkg == null) return null          // our own notifications
        val pkg = if (own && simPkg != null) simPkg else sbn.packageName

        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val plainTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val plainText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val cat = n.category

        // ---- Calls (checked before the "ongoing" filter: ringing/ongoing call notifications are ongoing) ----
        // Chat notifications (MessagingStyle) are never treated as calls, so a text like "Video call?" stays a message.
        val hasMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.isNotEmpty() == true
        val callish = cat == Notification.CATEGORY_CALL || cat == "missed_call" ||
            (!hasMessages && (CALL_TEXT.containsMatchIn(plainText) || CALL_TEXT.containsMatchIn(plainTitle)))
        if (callish && !own) {
            return parseCall(pkg, plainTitle, plainText, sbn, n)
        }

        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        if (cat != null && cat in SKIP_CATEGORIES) return null

        val reply = findReplyAction(n)
        val items = ArrayList<ChatStore.Item>()
        var isGroup = false
        val title: String?
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
            title = (if (isGroup) convTitle else null) ?: firstSender ?: convTitle ?: plainTitle
        } else {
            if (reply == null && !own) {
                // Not a DM. Instagram's other notifications go to its Activity tab.
                if (pkg in ACTIVITY_APPS && plainTitle.isNotEmpty() && !isNoise(plainText)) {
                    val item = ActivityItem(pkg, plainTitle, plainText, if (n.`when` > 0) n.`when` else sbn.postTime)
                    item.contentIntent = n.contentIntent
                    val ic = n.getLargeIcon()
                    if (ic != null) item.avatar = loadIcon(ctx, ic)?.let { Avatars.circle(it, 96) }
                    return Parsed.Activity(item)
                }
                return null
            }
            if (plainTitle.isEmpty() || isNoise(plainText)) return null
            val time = if (n.`when` > 0) n.`when` else sbn.postTime
            items.add(ChatStore.Item("$time|$plainTitle|${plainText.hashCode()}", plainText, time, false, null, true))
            title = plainTitle
        }

        if (items.isEmpty() || items.none { !it.outgoing }) {
            // Only my own messages (the app echoing a reply): still refresh the live reply button.
            if (items.isEmpty()) return null
        }
        val cleanTitle = title?.replace(COUNT_SUFFIX, "")?.trim()
        if (cleanTitle.isNullOrEmpty()) return null

        val idPart = n.shortcutId?.takeIf { it.isNotBlank() } ?: cleanTitle
        val key = "$pkg|$idPart"

        // Profile picture: re-read only when missing or every 30 minutes.
        var avatar: Bitmap? = null
        val now = System.currentTimeMillis()
        val haveAvatar = ChatStore.get(key)?.avatar != null
        val last = avatarCheckedAt[key] ?: 0L
        if (!haveAvatar || now - last > AVATAR_REFRESH_MS) {
            avatarCheckedAt[key] = now
            val icon: Icon? = if (isGroup) (n.getLargeIcon() ?: personIcon) else (personIcon ?: n.getLargeIcon())
            var bmp: Bitmap? = icon?.let { loadIcon(ctx, it) }
            if (bmp == null) bmp = extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap
            avatar = bmp?.let { Avatars.circle(it) }
        }

        return Parsed.Chat(
            ChatStore.Batch(
                key, pkg, cleanTitle, isGroup, own, avatar, items, reply, n.contentIntent,
                findMarkReadAction(n), sbn.key
            )
        )
    }

    private fun parseCall(pkg: String, title: String, text: String, sbn: StatusBarNotification, n: Notification): Parsed? {
        val titleIsCall = CALL_TEXT.containsMatchIn(title)
        val callText: String
        var person: String
        if (titleIsCall && !CALL_TEXT.containsMatchIn(text)) {
            callText = title
            person = text
        } else {
            callText = text
            person = title
        }
        CALLING_YOU.find(callText)?.let { person = it.groupValues[1] }
        person = person.replace(COUNT_SUFFIX, "").trim()
        if (person.isEmpty() || person.equals("WhatsApp", true) || person.equals("Instagram", true)) return null
        val lower = (callText + " " + title).lowercase()
        val video = lower.contains("video")
        val missed = lower.contains("missed") || lower.contains("unanswered") || n.category == "missed_call"
        val label = when {
            missed && video -> "Missed video call"
            missed -> "Missed voice call"
            video -> "Video call"
            else -> "Audio call"
        }
        return Parsed.Call(pkg, person, label, sbn.postTime, n.shortcutId, sbn.key, n.contentIntent)
    }

    private fun loadIcon(ctx: Context, icon: Icon): Bitmap? = try {
        icon.loadDrawable(ctx)?.let { Avatars.fromDrawable(it) }
    } catch (e: Exception) {
        null
    }

    /** All actions: visible buttons, Wear OS actions and hidden "invisible" (car/auto) actions. */
    private fun allActions(n: Notification): List<Notification.Action> {
        val all = ArrayList<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        try {
            all.addAll(Notification.WearableExtender(n).actions)
        } catch (e: Exception) {
        }
        all.addAll(invisibleActions(n))
        return all.filter { it.actionIntent != null }
    }

    /** NotificationCompat stores "invisible" actions (used by Android Auto) in the car extras bundle. */
    @Suppress("DEPRECATION")
    private fun invisibleActions(n: Notification): List<Notification.Action> {
        val out = ArrayList<Notification.Action>()
        try {
            val car = n.extras?.getBundle("android.car.EXTENSIONS") ?: return out
            val inv = car.getBundle("invisible_actions") ?: return out
            for (k in inv.keySet()) {
                val b = inv.getBundle(k) ?: continue
                val pi = b.getParcelable<PendingIntent>("actionIntent") ?: continue
                val title = b.getCharSequence("title") ?: ""
                val builder = Notification.Action.Builder(null as Icon?, title, pi)
                val ris = b.getParcelableArray("remoteInputs")
                if (ris != null) {
                    for (p: Parcelable in ris) {
                        val rb = p as? Bundle ?: continue
                        val key = rb.getString("resultKey") ?: continue
                        val ri = RemoteInput.Builder(key)
                            .setLabel(rb.getCharSequence("label"))
                            .setChoices(rb.getCharSequenceArray("choices"))
                            .setAllowFreeFormInput(rb.getBoolean("allowFreeFormInput", true))
                        rb.getBundle("extras")?.let { ri.addExtras(it) }
                        builder.addRemoteInput(ri.build())
                    }
                }
                builder.setSemanticAction(b.getInt("semanticAction", Notification.Action.SEMANTIC_ACTION_NONE))
                out.add(builder.build())
            }
        } catch (e: Exception) {
            // malformed extras: ignore
        }
        return out
    }

    private val MARK_READ_TITLE = Regex("(?i)(mark.*read|^read$|als gelesen|marcar como le)")

    /** Finds the app's "Mark as read" action (WhatsApp, Messenger, Telegram, Signal...). */
    fun findMarkReadAction(n: Notification): Notification.Action? {
        val all = allActions(n)
        return all.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }
            ?: all.firstOrNull { a ->
                a.remoteInputs.isNullOrEmpty() && a.title?.toString()?.let { MARK_READ_TITLE.containsMatchIn(it.trim()) } == true
            }
    }

    /** Finds the inline-reply action (with a free-text RemoteInput and a live PendingIntent). */
    /** Every action that can take typed text (standard, Wear and car variants), best first. */
    fun allReplyActions(n: Notification): List<Notification.Action> {
        val list = allActions(n).filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true && a.actionIntent != null }
        return list.sortedBy { if (it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY) 0 else 1 }
    }

    fun findReplyAction(n: Notification): Notification.Action? {
        val all = allActions(n)
        fun Notification.Action.canType(): Boolean =
            remoteInputs?.any { it.allowFreeFormInput } == true
        return all.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && it.canType() }
            ?: all.firstOrNull { it.canType() }
    }
}
