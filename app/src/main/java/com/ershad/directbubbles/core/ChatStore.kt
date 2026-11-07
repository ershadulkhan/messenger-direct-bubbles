package com.ershad.directbubbles.core

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Lightweight local chat cache. Lives in memory, saved as one small JSON file
 * (plus one tiny PNG per profile picture). Every method must be called on the main thread.
 */
object ChatStore {
    private const val TAG = "ChatStore"
    private const val MAX_MESSAGES = 300
    private const val MAX_CONVERSATIONS = 60
    private const val MAX_SEEN = 400

    /** One message pulled out of a notification. */
    class Item(
        val sig: String,
        val text: String,
        val time: Long,
        val outgoing: Boolean,
        val sender: String?,
        /** True when the app gave no exact timestamp (plain notifications): use looser duplicate checks. */
        val loose: Boolean,
    )

    /** Everything one notification tells us about one conversation. */
    class Batch(
        val key: String,
        val pkg: String,
        val title: String,
        val isGroup: Boolean,
        val simulated: Boolean,
        val avatar: Bitmap?,
        val items: List<Item>,
        val reply: Notification.Action?,
        val content: PendingIntent?,
    )

    private val convs = HashMap<String, Conversation>()
    private val listeners = ArrayList<() -> Unit>()
    private var app: Context? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        load()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun all(): List<Conversation> = convs.values.sortedByDescending { it.lastTime }

    fun activeOnes(): List<Conversation> = all().filter { it.active }

    fun get(key: String?): Conversation? = if (key == null) null else convs[key]

    fun totalUnread(): Int = convs.values.filter { it.active }.sumOf { it.unread }

    /** Adds what a notification contains. Returns how many NEW incoming messages were added. */
    fun ingest(b: Batch): Int {
        val c = convs.getOrPut(b.key) { Conversation(b.key, b.pkg, b.title) }
        if (b.title.isNotBlank()) c.title = b.title
        c.isGroup = b.isGroup
        c.simulated = b.simulated
        if (b.reply != null) c.replyAction = b.reply
        if (b.content != null) c.contentIntent = b.content
        val newAvatar = b.avatar
        if (newAvatar != null) {
            val old = c.avatar
            if (old == null || !old.sameAs(newAvatar)) {
                c.avatar = newAvatar
                saveAvatar(c.key, newAvatar)
            }
        }

        var added = 0
        var changed = false
        for (it in b.items.sortedBy { item -> item.time }) {
            if (!c.seen.add(it.sig)) continue
            changed = true
            val recent = c.messages.takeLast(20)
            if (it.outgoing) {
                // The app echoes back replies we sent from the popup: don't show them twice.
                val echo = recent.any { m -> m.outgoing && m.text == it.text && abs(m.time - it.time) < 10 * 60_000L }
                if (echo) continue
                c.messages.add(ChatMessage(it.text, it.time, true))
            } else {
                val window = if (it.loose) 10 * 60_000L else 1_500L
                val dup = recent.any { m -> !m.outgoing && m.text == it.text && m.sender == it.sender && abs(m.time - it.time) < window }
                if (dup) continue
                c.messages.add(ChatMessage(it.text, it.time, false, it.sender))
                c.unread++
                added++
            }
            if (it.time > c.lastTime) c.lastTime = it.time
        }
        // Keep chronological order (apps sometimes deliver older messages later).
        if (changed) c.messages.sortBy { it.time }
        while (c.messages.size > MAX_MESSAGES) c.messages.removeAt(0)
        while (c.seen.size > MAX_SEEN) c.seen.remove(c.seen.first())
        if (added > 0) c.active = true
        trimConversations()
        notifyChanged()
        return added
    }

    fun addOutgoing(c: Conversation, text: String): ChatMessage {
        val m = ChatMessage(text, System.currentTimeMillis(), true, null, ChatMessage.STATUS_SENDING)
        c.messages.add(m)
        c.lastTime = m.time
        notifyChanged()
        return m
    }

    fun markRead(c: Conversation) {
        if (c.unread > 0) {
            c.unread = 0
            notifyChanged()
        }
    }

    fun dismissAll() {
        var any = false
        for (c in convs.values) if (c.active) {
            c.active = false
            any = true
        }
        if (any) notifyChanged()
    }

    fun delete(key: String) {
        if (convs.remove(key) != null) {
            deleteAvatar(key)
            notifyChanged()
        }
    }

    fun notifyChanged() {
        for (l in ArrayList(listeners)) {
            try {
                l()
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed", t)
            }
        }
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 800)
    }

    private fun trimConversations() {
        if (convs.size <= MAX_CONVERSATIONS) return
        val extra = convs.values.sortedBy { it.lastTime }.take(convs.size - MAX_CONVERSATIONS)
        for (c in extra) {
            convs.remove(c.key)
            deleteAvatar(c.key)
        }
    }

    // ---------------- persistence ----------------

    private fun dir(): File = File(app!!.filesDir, "chats").apply { mkdirs() }

    private fun dataFile() = AtomicFile(File(dir(), "chats.json"))

    private fun avatarFile(key: String) = File(dir(), "a_" + Integer.toHexString(key.hashCode()) + ".png")

    private fun saveAvatar(key: String, bmp: Bitmap) {
        if (app == null) return
        val f = avatarFile(key)
        io.execute {
            try {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) {
                Log.w(TAG, "avatar save failed", e)
            }
        }
    }

    private fun deleteAvatar(key: String) {
        if (app == null) return
        val f = avatarFile(key)
        io.execute { f.delete() }
    }

    private fun load() {
        try {
            val f = dataFile()
            if (!f.baseFile.exists()) return
            val json = JSONObject(String(f.readFully(), Charsets.UTF_8))
            val arr = json.optJSONArray("convs") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = Conversation(o.getString("key"), o.getString("pkg"), o.optString("title"))
                c.isGroup = o.optBoolean("group")
                c.simulated = o.optBoolean("sim")
                c.unread = o.optInt("unread")
                c.lastTime = o.optLong("last")
                c.active = o.optBoolean("active")
                o.optJSONArray("msgs")?.let { ms ->
                    for (j in 0 until ms.length()) {
                        val m = ms.getJSONObject(j)
                        val st = m.optInt("st")
                        c.messages.add(
                            ChatMessage(
                                m.getString("t"), m.getLong("ts"), m.optBoolean("o"),
                                if (m.has("s")) m.getString("s") else null,
                                if (st == ChatMessage.STATUS_FAILED) st else ChatMessage.STATUS_OK
                            )
                        )
                    }
                }
                o.optJSONArray("seen")?.let { s -> for (j in 0 until s.length()) c.seen.add(s.getString(j)) }
                val af = avatarFile(c.key)
                if (af.exists()) c.avatar = BitmapFactory.decodeFile(af.path)
                convs[c.key] = c
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
        }
    }

    private fun saveNow() {
        if (app == null) return
        val arr = JSONArray()
        for (c in convs.values) {
            val ms = JSONArray()
            for (m in c.messages) {
                val o = JSONObject().put("t", m.text).put("ts", m.time).put("o", m.outgoing).put("st", m.status)
                if (m.sender != null) o.put("s", m.sender)
                ms.put(o)
            }
            arr.put(
                JSONObject()
                    .put("key", c.key).put("pkg", c.pkg).put("title", c.title)
                    .put("group", c.isGroup).put("sim", c.simulated)
                    .put("unread", c.unread).put("last", c.lastTime).put("active", c.active)
                    .put("msgs", ms)
                    .put("seen", JSONArray(c.seen.toList()))
            )
        }
        val text = JSONObject().put("convs", arr).toString()
        val f = dataFile()
        io.execute {
            var out: FileOutputStream? = null
            try {
                val stream = f.startWrite()
                out = stream
                stream.write(text.toByteArray(Charsets.UTF_8))
                f.finishWrite(stream)
            } catch (e: Exception) {
                if (out != null) f.failWrite(out)
                Log.w(TAG, "save failed", e)
            }
        }
    }
}
