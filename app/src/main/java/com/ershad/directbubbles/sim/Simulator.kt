package com.ershad.directbubbles.sim

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.Avatars
import com.ershad.directbubbles.core.ChatStore
import com.ershad.directbubbles.core.Notifications
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.overlay.OpenPopupActivity
import com.ershad.directbubbles.overlay.OverlayController
import com.ershad.directbubbles.service.BubbleNotificationListener

/**
 * "Test Messenger Bubbles & Inbox": three made-up people send DMs one after another.
 * When Notification access is on, they arrive as REAL MessagingStyle notifications with a
 * RemoteInput Reply action, so the whole pipeline (listener -> bubble -> popup -> reply) is tested.
 */
object Simulator {
    const val EXTRA_SIM_PKG = "com.ershad.directbubbles.SIM_PKG"
    const val EXTRA_SIM_NAME = "com.ershad.directbubbles.SIM_NAME"
    const val KEY_REPLY = "sim_reply"
    const val ACTION_MARK_READ = "com.ershad.directbubbles.SIM_MARK_READ"

    class Profile(val name: String, val pkg: String, val color: Int, val id: Int)

    val profiles = listOf(
        Profile("Ayesha Rahman", "com.whatsapp", 0xFF00A884.toInt(), 9101),
        Profile("tanvir.codes", "com.instagram.android", 0xFFD62976.toInt(), 9102),
        Profile("Nadia Islam", "com.whatsapp", 0xFF7E57C2.toInt(), 9103),
    )

    private val script = listOf(
        0 to "Hey! Are you free this evening? 🙂",
        1 to "yo check out the reel I just posted 🔥",
        2 to "Assalamu alaikum! Did you get the documents?",
        0 to "Thinking of trying that new café in Gulshan",
        1 to "also are we still on for Friday?",
        2 to "Please send them when you can 🙏",
        0 to "Let me know!",
    )

    private val autoReplies = listOf(
        "Haha nice 😄", "Got it, thanks!", "Sounds good 👍", "Okay, talk soon!", "😂😂", "Perfect, see you then",
    )

    private val main = Handler(Looper.getMainLooper())

    fun usesRealNotifications(c: Context): Boolean =
        BubbleNotificationListener.connected &&
            c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** Returns true if real notifications are used, false if messages are injected directly. */
    fun run(c: Context): Boolean {
        val app = c.applicationContext
        Prefs.setBubblesOn(app, true)
        val real = usesRealNotifications(app)
        script.forEachIndexed { i, (who, text) ->
            main.postDelayed({ deliver(app, profiles[who], text) }, 500L + i * 1700L)
        }
        return real
    }

    /** The test person "answers" your reply, so you can see the thread grow both ways. */
    fun autoReply(c: Context, name: String, pkg: String?, yourText: String) {
        val p = profiles.firstOrNull { it.name == name }
            ?: if (pkg != null) Profile(name, pkg, 0xFF0A7CFF.toInt(), 9104) else return
        val text = autoReplies[Math.floorMod(yourText.hashCode(), autoReplies.size)]
        deliver(c.applicationContext, p, text)
    }

    fun key(p: Profile): String = "${p.pkg}|${p.name}"

    /** Theme "Test" button: one message from a test person in [pkg]'s chat window. */
    fun testFor(c: Context, pkg: String) {
        val app = c.applicationContext
        Prefs.setBubblesOn(app, true)
        val p = profiles.firstOrNull { it.pkg == pkg } ?: Profile("Test Contact", pkg, 0xFF0A7CFF.toInt(), 9104)
        main.postDelayed({ deliver(app, p, "Hello! How's the new theme looking? 🎨") }, 300L)
    }

    private fun markReadAction(app: Context, p: Profile): Notification.Action {
        val i = Intent(app, TestReplyReceiver::class.java).setAction(ACTION_MARK_READ).putExtra(EXTRA_SIM_NAME, p.name)
            .putExtra(EXTRA_SIM_PKG, p.pkg)
        val pi = PendingIntent.getBroadcast(app, p.id + 50, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(app, R.drawable.ic_notif), "Mark as read", pi)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    }

    private fun replyAction(app: Context, p: Profile): Notification.Action {
        val replyIntent = Intent(app, TestReplyReceiver::class.java).putExtra(EXTRA_SIM_NAME, p.name)
            .putExtra(EXTRA_SIM_PKG, p.pkg)
        val pi = PendingIntent.getBroadcast(
            app, p.id, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE   // mutable: RemoteInput fills it
        )
        val ri = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        return Notification.Action.Builder(Icon.createWithResource(app, R.drawable.ic_send), "Reply", pi)
            .addRemoteInput(ri)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun deliver(app: Context, p: Profile, text: String) {
        val avatar = Avatars.letter(p.name, p.color)
        val action = replyAction(app, p)
        val markRead = markReadAction(app, p)
        val now = System.currentTimeMillis()
        if (usesRealNotifications(app)) {
            val sender = Person.Builder().setName(p.name).setKey("sim:" + p.name)
                .setIcon(Icon.createWithBitmap(avatar)).build()
            val me = Person.Builder().setName("You").setKey("sim:me").build()
            val style = Notification.MessagingStyle(me)
                .addMessage(Notification.MessagingStyle.Message(text, now, sender))
            val content = PendingIntent.getActivity(
                app, p.id, OpenPopupActivity.intent(app, key(p)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val extras = Bundle()
            extras.putString(EXTRA_SIM_PKG, p.pkg)
            val n = Notification.Builder(app, Notifications.CH_SIM)
                .setSmallIcon(R.drawable.ic_notif)
                .setStyle(style)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(content)
                .setAutoCancel(true)
                .addAction(action)
                .addAction(markRead)
                .addExtras(extras)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(p.id, n)
        } else {
            ChatStore.init(app)
            OverlayController.init(app)
            val batch = ChatStore.Batch(
                key(p), p.pkg, p.name, false, true, avatar,
                listOf(ChatStore.Item("$now|${p.name}|${text.hashCode()}", text, now, false, null, false)),
                action, null, markRead
            )
            if (ChatStore.ingest(batch) > 0) OverlayController.onNewMessages(batch.key)
        }
    }
}
