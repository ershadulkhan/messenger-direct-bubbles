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
 * Lightweight local chat cache. Lives in memory; each chat is saved to its own small JSON file,
 * and only chats that actually changed are written (keeps CPU + battery use minimal).
 * Every method must be called on the main thread.
 */
object ChatStore {
    private const val TAG = "ChatStore"
    private const val MAX_MESSAGES = 250
    private const val MAX_CONVERSATIONS = 60
    private const val MAX_SEEN = 300
    private const val MAX_ACTIVITY = 80
    private const val SAVE_DELAY_MS = 2500L

    /** One message pulled out of a notification. */
    class Item(
        val sig: String,
        val text: String,
        val time: Long,
        val outgoing: Boolean,
        val sender: String?,
        /** True when the app gave no exact timestamp (plain notifications): use looser duplicate checks. */
        val loose: Boolean,
        val kind: Int = ChatMessage.KIND_TEXT,
    )

    /** Everything one notification tells us about one conversation. */
    class Batch(
        /** Rewritten by [ingest]/[attachActions] to the key of the chat this person already has. */
        var key: String,
        val pkg: String,
        val title: String,
        val isGroup: Boolean,
        val simulated: Boolean,
        val avatar: Bitmap?,
        val items: List<Item>,
        val reply: Notification.Action?,
        val content: PendingIntent?,
        val markRead: Notification.Action? = null,
        /** StatusBarNotification.key of the source notification. */
        val sbnKey: String? = null,
    ) {
        fun withItems(list: List<Item>) = Batch(key, pkg, title, isGroup, simulated, avatar, list, reply, content, markRead, sbnKey)
    }

    private val convs = HashMap<String, Conversation>()
    private val activity = ArrayList<ActivityItem>()
    private var activityDirty = false
    /** notification key -> conversation key */
    private val notifIndex = HashMap<String, String>()
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

    /** Finds an existing chat of [pkg] whose name matches [title] (used for calls). */
    fun findByTitle(pkg: String, title: String): Conversation? {
        val t = normalize(title)
        return convs.values.filter { it.pkg == pkg && normalize(it.title) == t }.maxByOrNull { it.lastTime }
    }

    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")
    private val SPACES = Regex("\\s+")

    /** Name used to recognise the same person: ignores case, spacing, invisible marks and fancy fonts (𝙄𝙧𝙨𝙝𝙖𝙙 = Irshad). */
    fun normalize(s: String): String =
        INVISIBLE.replace(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC), "")
            .trim().lowercase().replace(SPACES, " ")

    fun convKeyForNotification(sbnKey: String): String? = notifIndex[sbnKey]

    private fun touch(c: Conversation) {
        c.dirty = true
    }

    /** Adds what a notification contains. Returns how many NEW incoming text messages were added. */
    fun ingest(b0: Batch): Int {
        // A chat you deleted starts fresh: anything the app re-sends from before the deletion is ignored.
        val cut = deletedAt(b0)
        val b = if (cut > 0L) b0.withItems(b0.items.filter { it.time > cut }) else b0
        if (cut > 0L && b.items.isEmpty() && resolve(b) == null) {
            b0.key = b.key
            return 0
        }
        val c = resolve(b) ?: Conversation(b.key, b.pkg, b.title).also {
            it.ids.add(b.key)
            convs[b.key] = it
        }
        b0.key = c.key
        if (b.title.isNotBlank() && c.title != b.title) {
            c.title = b.title
            touch(c)
            mergeSameName(c)   // the new name may be a person we already have under another key
        }
        c.isGroup = b.isGroup
        c.simulated = b.simulated
        attach(c, b)
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
                // The app echoes back replies we sent from the popup (Instagram sometimes as
                // "username: text"): don't show them twice — mark ours as delivered (✓✓) instead.
                val bare = stripSenderPrefix(it.text)
                // A reply the phone held back can echo hours later: match it to my still-unconfirmed reply first.
                val echo = recent.lastOrNull { m ->
                    m.outgoing && (m.text == it.text || m.text == bare) && m.confirmedAt == 0L &&
                        (m.status == ChatMessage.STATUS_OK || m.status == ChatMessage.STATUS_SENDING) &&
                        it.time >= m.time - 60_000L && it.time - m.time < 24 * 3_600_000L
                } ?: recent.lastOrNull { m ->
                    m.outgoing && (m.text == it.text || m.text == bare) && abs(m.time - it.time) < 10 * 60_000L
                }
                if (echo != null) {
                    if (echo.status == ChatMessage.STATUS_OK || echo.status == ChatMessage.STATUS_SENDING) {
                        echo.status = ChatMessage.STATUS_CONFIRMED
                        if (echo.confirmedAt == 0L) echo.confirmedAt = System.currentTimeMillis()
                    }
                    continue
                }
                c.messages.add(ChatMessage(bare, it.time, true))
            } else if (it.kind == ChatMessage.KIND_CALL) {
                // One call entry per call (call notifications are re-posted while ringing).
                val dup = recent.any { m -> m.kind == ChatMessage.KIND_CALL && abs(m.time - it.time) < 3 * 60_000L }
                if (dup) {
                    // Upgrade "Audio call" -> "Missed voice call" when the call is missed.
                    val idx = c.messages.indexOfLast { m -> m.kind == ChatMessage.KIND_CALL && abs(m.time - it.time) < 3 * 60_000L }
                    if (idx >= 0 && it.text.startsWith("Missed") && !c.messages[idx].text.startsWith("Missed")) {
                        val old = c.messages[idx]
                        c.messages[idx] = ChatMessage(it.text, old.time, false, null, ChatMessage.STATUS_OK, ChatMessage.KIND_CALL)
                        c.unread++
                    }
                    continue
                }
                c.messages.add(ChatMessage(it.text, it.time, false, null, ChatMessage.STATUS_OK, ChatMessage.KIND_CALL))
                if (it.text.startsWith("Missed")) c.unread++
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
        if (changed) {
            c.messages.sortBy { it.time }
            touch(c)
        }
        while (c.messages.size > MAX_MESSAGES) c.messages.removeAt(0)
        while (c.seen.size > MAX_SEEN) c.seen.remove(c.seen.first())
        if (added > 0) c.active = true
        trimConversations()
        if (changed) notifyChanged() else notifyListenersOnly()
        return added
    }

    private val SENDER_PREFIX = Regex("^[^:\\n]{1,40}:\\s+")

    private fun stripSenderPrefix(s: String): String = s.replaceFirst(SENDER_PREFIX, "")

    /**
     * The app removed its notification right after I replied (WhatsApp does this once the reply is
     * sent) -> count my recent replies as delivered.
     */
    fun confirmRecentReplies(c: Conversation) {
        val now = System.currentTimeMillis()
        var any = false
        for (m in c.messages.takeLast(10)) {
            if (m.outgoing && m.status == ChatMessage.STATUS_OK && now - maxOf(m.time, m.sentAt) < 60_000L) {
                m.status = ChatMessage.STATUS_CONFIRMED
                if (m.confirmedAt == 0L) m.confirmedAt = now
                any = true
            }
        }
        if (any) {
            touch(c)
            notifyChanged()
        }
    }

    /** Refreshes only the live reply / open / mark-read buttons of a chat (no new messages). */
    fun attachActions(b: Batch) {
        val c = resolve(b) ?: return
        attach(c, b)
    }

    // ---------------- one chat per person ----------------

    /**
     * Finds the chat this notification belongs to and points [b] at it. Apps post the same person under
     * different keys (with and without a shortcut id, renamed...), which used to create two chat heads.
     * Matching: same key, or any id we have seen for that person, or (1-to-1 chats only) the same name.
     */
    private fun resolve(b: Batch): Conversation? {
        convs[b.key]?.let { return learn(it, b) }
        var found = convs.values.firstOrNull { it.pkg == b.pkg && b.key in it.ids }
        if (found == null && !b.isGroup) {
            val t = normalize(b.title)
            if (t.isNotEmpty()) {
                found = convs.values.filter { it.pkg == b.pkg && !it.isGroup && normalize(it.title) == t }
                    .maxByOrNull { it.lastTime }
            }
        }
        return found?.let { learn(it, b) }
    }

    private fun learn(c: Conversation, b: Batch): Conversation {
        if (c.ids.add(b.key)) touch(c)
        b.key = c.key
        return c
    }

    /** Merges every other 1-to-1 chat of the same app that has the same person name into [keep]. */
    private fun mergeSameName(keep: Conversation) {
        if (keep.isGroup || keep.simulated) return
        val t = normalize(keep.title)
        if (t.isEmpty()) return
        val others = convs.values.filter { it !== keep && it.pkg == keep.pkg && !it.isGroup && !it.simulated && normalize(it.title) == t }
        for (o in others) mergeInto(keep, o)
    }

    /** Moves all of [other] (messages, ids, unread, avatar, live buttons) into [keep] and removes [other]. */
    private fun mergeInto(keep: Conversation, other: Conversation) {
        if (keep === other) return
        for (m in other.messages) {
            val dup = keep.messages.any {
                it.outgoing == m.outgoing && it.kind == m.kind && it.text == m.text && it.sender == m.sender &&
                    abs(it.time - m.time) < 2_000L
            }
            if (!dup) keep.messages.add(m)
        }
        keep.messages.sortBy { it.time }
        while (keep.messages.size > MAX_MESSAGES) keep.messages.removeAt(0)
        keep.seen.addAll(other.seen)
        while (keep.seen.size > MAX_SEEN) keep.seen.remove(keep.seen.first())
        keep.ids.add(other.key)
        keep.ids.addAll(other.ids)
        keep.unread = maxOf(keep.unread, other.unread)
        keep.active = keep.active || other.active
        if (other.lastTime > keep.lastTime) {
            keep.lastTime = other.lastTime
            if (other.title.isNotBlank()) keep.title = other.title
        }
        if (keep.avatar == null && other.avatar != null) {
            keep.avatar = other.avatar
            saveAvatar(keep.key, other.avatar!!)
        }
        if (keep.replyAction == null) keep.replyAction = other.replyAction
        if (keep.contentIntent == null) keep.contentIntent = other.contentIntent
        if (keep.markReadAction == null) keep.markReadAction = other.markReadAction
        for (k in other.notifKeys) {
            keep.notifKeys.add(k)
            notifIndex[k] = keep.key
        }
        convs.remove(other.key)
        deleteFiles(other.key)
        touch(keep)
        notifyChanged()
    }

    /** Past conversations: joins chats of the same person that were saved separately by older versions. */
    private fun mergeDuplicates() {
        val groups = convs.values.filter { !it.isGroup && !it.simulated && normalize(it.title).isNotEmpty() }
            .groupBy { it.pkg + "\u0000" + normalize(it.title) }
        var any = false
        for (list in groups.values) {
            if (list.size < 2) continue
            val sorted = list.sortedByDescending { it.lastTime }
            for (o in sorted.drop(1)) {
                mergeInto(sorted[0], o)
                any = true
            }
        }
        if (any) main.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun attach(c: Conversation, b: Batch) {
        if (b.reply != null) c.replyAction = b.reply
        if (b.content != null) c.contentIntent = b.content
        if (b.markRead != null) c.markReadAction = b.markRead
        val k = b.sbnKey
        if (k != null) {
            c.notifKeys.add(k)
            notifIndex[k] = c.key
        }
    }

    /** The source notification disappeared. Returns the chat it belonged to. */
    fun onNotificationGone(sbnKey: String): Conversation? {
        val key = notifIndex.remove(sbnKey) ?: return null
        val c = convs[key] ?: return null
        c.notifKeys.remove(sbnKey)
        return c
    }

    fun addOutgoing(c: Conversation, text: String): ChatMessage {
        val m = ChatMessage(text, System.currentTimeMillis(), true, null, ChatMessage.STATUS_SENDING)
        c.messages.add(m)
        c.lastTime = m.time
        touch(c)
        notifyChanged()
        return m
    }

    fun removeMessage(c: Conversation, m: ChatMessage) {
        if (c.messages.remove(m)) {
            touch(c)
            notifyChanged()
        }
    }

    fun markRead(c: Conversation) {
        if (c.unread > 0) {
            c.unread = 0
            touch(c)
            notifyChanged()
        }
    }

    /** Shows or hides one person's chat head. */
    fun setActive(key: String, on: Boolean) {
        val c = convs[key] ?: return
        if (c.active != on) {
            c.active = on
            touch(c)
            notifyChanged()
        }
    }

    fun dismissAll() {
        var any = false
        for (c in convs.values) if (c.active) {
            c.active = false
            touch(c)
            any = true
        }
        if (any) notifyChanged()
    }

    // ---------------- deleted chats (start fresh) ----------------

    private class Tomb(val pkg: String, val name: String, val ids: Set<String>, val at: Long)

    private val tombs = ArrayList<Tomb>()
    private const val MAX_TOMBS = 300
    private const val TOMB_DAYS = 60L

    /** Deletes a chat and everything in it; when this person writes again, the chat starts empty. */
    fun forget(key: String) {
        val c = convs[key] ?: return
        val ids = HashSet(c.ids)
        ids.add(c.key)
        val name = if (c.isGroup) "" else normalize(c.title)
        tombs.removeAll { t -> t.pkg == c.pkg && (t.ids.any { it in ids } || (name.isNotEmpty() && t.name == name)) }
        tombs.add(Tomb(c.pkg, name, ids, System.currentTimeMillis()))
        while (tombs.size > MAX_TOMBS) tombs.removeAt(0)
        saveTombs()
        for (k in c.notifKeys) notifIndex.remove(k)
        delete(key)
    }

    /** When this person's chat was deleted (0 = never). */
    private fun deletedAt(b: Batch): Long {
        if (tombs.isEmpty()) return 0L
        val name = if (b.isGroup) "" else normalize(b.title)
        return tombs.filter { t ->
            t.pkg == b.pkg && (b.key in t.ids || (name.isNotEmpty() && t.name == name))
        }.maxOfOrNull { it.at } ?: 0L
    }

    private fun tombFile() = AtomicFile(File(dir(), "deleted.json"))

    private fun saveTombs() {
        if (app == null) return
        val arr = JSONArray()
        for (t in tombs) arr.put(JSONObject().put("pkg", t.pkg).put("name", t.name).put("at", t.at).put("ids", JSONArray(t.ids.toList())))
        val text = arr.toString()
        val f = tombFile()
        io.execute {
            var out: FileOutputStream? = null
            try {
                val st = f.startWrite()
                out = st
                st.write(text.toByteArray(Charsets.UTF_8))
                f.finishWrite(st)
            } catch (e: Exception) {
                if (out != null) f.failWrite(out)
                Log.w(TAG, "save deleted failed", e)
            }
        }
    }

    private fun loadTombs() {
        try {
            val f = tombFile()
            if (!f.baseFile.exists()) return
            val arr = JSONArray(String(f.readFully(), Charsets.UTF_8))
            val oldest = System.currentTimeMillis() - TOMB_DAYS * 24 * 3_600_000L
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val at = o.optLong("at")
                if (at < oldest) continue
                val ids = HashSet<String>()
                o.optJSONArray("ids")?.let { a -> for (j in 0 until a.length()) ids.add(a.getString(j)) }
                tombs.add(Tomb(o.getString("pkg"), o.optString("name"), ids, at))
            }
        } catch (e: Exception) {
            Log.w(TAG, "load deleted failed", e)
        }
    }

    fun delete(key: String) {
        if (convs.remove(key) != null) {
            deleteFiles(key)
            notifyChanged()
        }
    }

    /** Home → Clear History. */
    fun clearAll() {
        for (k in ArrayList(convs.keys)) deleteFiles(k)
        convs.clear()
        notifIndex.clear()
        activity.clear()
        activityDirty = true
        notifyChanged()
    }

    // ---------------- activity feed (Instagram: instants, follows, requests...) ----------------

    fun activityFor(pkg: String): List<ActivityItem> = activity.filter { it.pkg == pkg }.sortedByDescending { it.time }

    fun unreadActivity(pkg: String): Int = activity.count { it.pkg == pkg && it.unread }

    fun addActivity(item: ActivityItem): Boolean {
        val dup = activity.any {
            it.pkg == item.pkg && it.title == item.title && it.text == item.text && abs(it.time - item.time) < 30 * 60_000L
        }
        if (dup) return false
        activity.add(item)
        while (activity.size > MAX_ACTIVITY) {
            val oldest = activity.minByOrNull { it.time } ?: break
            activity.remove(oldest)
        }
        activityDirty = true
        notifyChanged()
        return true
    }

    fun markActivityRead(pkg: String) {
        var any = false
        for (a in activity) if (a.pkg == pkg && a.unread) {
            a.unread = false
            any = true
        }
        if (any) {
            activityDirty = true
            notifyChanged()
        }
    }

    fun clearActivity(pkg: String) {
        activity.removeAll { it.pkg == pkg }
        activityDirty = true
        notifyChanged()
    }

    // ---------------- change notification ----------------

    fun notifyChanged() {
        notifyListenersOnly()
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun notifyListenersOnly() {
        for (l in ArrayList(listeners)) {
            try {
                l()
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed", t)
            }
        }
    }

    private fun trimConversations() {
        if (convs.size <= MAX_CONVERSATIONS) return
        val extra = convs.values.sortedBy { it.lastTime }.take(convs.size - MAX_CONVERSATIONS)
        for (c in extra) {
            convs.remove(c.key)
            deleteFiles(c.key)
        }
    }

    // ---------------- persistence ----------------

    private fun dir(): File = File(app!!.filesDir, "chats").apply { mkdirs() }

    private fun id(key: String) = Integer.toHexString(key.hashCode())

    private fun convFile(key: String) = AtomicFile(File(dir(), "c_" + id(key) + ".json"))

    private fun avatarFile(key: String) = File(dir(), "a_" + id(key) + ".png")

    private fun activityFile() = AtomicFile(File(dir(), "activity.json"))

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

    private fun deleteFiles(key: String) {
        if (app == null) return
        val a = avatarFile(key)
        val c = convFile(key)
        io.execute {
            a.delete()
            c.delete()
        }
    }

    private fun load() {
        try {
            // Older versions kept everything in one big file: migrate it once.
            val legacy = AtomicFile(File(dir(), "chats.json"))
            if (legacy.baseFile.exists()) {
                val json = JSONObject(String(legacy.readFully(), Charsets.UTF_8))
                json.optJSONArray("convs")?.let { arr ->
                    for (i in 0 until arr.length()) readConv(arr.getJSONObject(i))?.let { convs[it.key] = it }
                }
                legacy.delete()
                for (c in convs.values) c.dirty = true
                main.postDelayed(saveRunnable, SAVE_DELAY_MS)
            }
            dir().listFiles()?.forEach { f ->
                if (f.name.startsWith("c_") && f.name.endsWith(".json")) {
                    try {
                        val o = JSONObject(String(AtomicFile(f).readFully(), Charsets.UTF_8))
                        readConv(o)?.let { c ->
                            c.dirty = false
                            convs[c.key] = c
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "bad chat file ${f.name}", e)
                    }
                }
            }
            for (c in convs.values) {
                val af = avatarFile(c.key)
                if (af.exists()) c.avatar = BitmapFactory.decodeFile(af.path)
            }
            mergeDuplicates()
            loadTombs()
            val af = activityFile()
            if (af.baseFile.exists()) {
                val arr = JSONArray(String(af.readFully(), Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val a = ActivityItem(o.getString("pkg"), o.optString("title"), o.optString("text"), o.optLong("ts"))
                    a.unread = o.optBoolean("unread")
                    activity.add(a)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
        }
    }

    private fun readConv(o: JSONObject): Conversation? {
        val key = o.optString("key")
        if (key.isEmpty()) return null
        val c = Conversation(key, o.getString("pkg"), o.optString("title"))
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
                        if (st == ChatMessage.STATUS_FAILED) st else ChatMessage.STATUS_OK,
                        m.optInt("k", ChatMessage.KIND_TEXT)
                    )
                )
            }
        }
        o.optJSONArray("seen")?.let { s -> for (j in 0 until s.length()) c.seen.add(s.getString(j)) }
        c.ids.add(key)
        o.optJSONArray("ids")?.let { a -> for (j in 0 until a.length()) c.ids.add(a.getString(j)) }
        return c
    }

    private fun convJson(c: Conversation): String {
        val ms = JSONArray()
        for (m in c.messages) {
            val o = JSONObject().put("t", m.text).put("ts", m.time).put("o", m.outgoing).put("st", m.status)
            if (m.sender != null) o.put("s", m.sender)
            if (m.kind != ChatMessage.KIND_TEXT) o.put("k", m.kind)
            ms.put(o)
        }
        return JSONObject()
            .put("key", c.key).put("pkg", c.pkg).put("title", c.title)
            .put("group", c.isGroup).put("sim", c.simulated)
            .put("unread", c.unread).put("last", c.lastTime).put("active", c.active)
            .put("msgs", ms)
            .put("seen", JSONArray(c.seen.toList()))
            .put("ids", JSONArray(c.ids.toList()))
            .toString()
    }

    /** Writes only the chats that changed since the last save. */
    private fun saveNow() {
        if (app == null) return
        val writes = ArrayList<Pair<AtomicFile, String>>()
        for (c in convs.values) if (c.dirty) {
            writes.add(convFile(c.key) to convJson(c))
            c.dirty = false
        }
        if (activityDirty) {
            val arr = JSONArray()
            for (a in activity) {
                arr.put(JSONObject().put("pkg", a.pkg).put("title", a.title).put("text", a.text).put("ts", a.time).put("unread", a.unread))
            }
            writes.add(activityFile() to arr.toString())
            activityDirty = false
        }
        if (writes.isEmpty()) return
        io.execute {
            for ((f, text) in writes) {
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
}
