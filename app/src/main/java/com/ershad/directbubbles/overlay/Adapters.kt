package com.ershad.directbubbles.overlay

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.AppIcons
import com.ershad.directbubbles.core.Avatars
import com.ershad.directbubbles.core.ActivityItem
import com.ershad.directbubbles.core.ChatMessage
import com.ershad.directbubbles.core.ChatTheme
import com.ershad.directbubbles.core.Conversation
import com.ershad.directbubbles.core.Themes
import com.ershad.directbubbles.core.TimeFmt

/** Inbox rows: [avatar + app badge] | name | latest message · time | unread count. Newest on top. */
class InboxAdapter(private val ctx: Context) : BaseAdapter() {
    private var items: List<Conversation> = emptyList()
    /** Per-app theme: the inbox page uses the same colors as the chosen chat theme. */
    var theme: ChatTheme? = null

    fun setData(list: List<Conversation>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Conversation = items[position]
    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_inbox, parent, false)
        val c = items[position]
        val unread = c.unread > 0
        v.findViewById<ImageView>(R.id.row_avatar).setImageBitmap(Avatars.forConv(c))
        v.findViewById<ImageView>(R.id.row_badge).setImageBitmap(AppIcons.badge(ctx, c.pkg))
        val name = v.findViewById<TextView>(R.id.row_name)
        name.text = c.title
        name.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
        val preview = v.findViewById<TextView>(R.id.row_preview)
        preview.text = c.preview()
        preview.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
        val t = theme
        if (t != null) {
            name.setTextColor(Themes.onColor(t.chatBg))
            preview.setTextColor(if (unread) Themes.onColor(t.chatBg) else Themes.subtleOn(t.chatBg))
            v.findViewById<TextView>(R.id.row_time).setTextColor(Themes.subtleOn(t.chatBg))
        } else {
            preview.setTextColor(ctx.getColor(if (unread) R.color.text_primary else R.color.text_secondary))
        }
        v.findViewById<TextView>(R.id.row_time).text = " · " + TimeFmt.short(ctx, c.lastTime)
        val badge = v.findViewById<TextView>(R.id.row_unread)
        badge.visibility = if (unread) View.VISIBLE else View.GONE
        badge.text = if (c.unread > 99) "99+" else c.unread.toString()
        return v
    }
}

/** One person's messages, one below another: theirs on the left, mine on the right. */
class ThreadAdapter(private val ctx: Context) : BaseAdapter() {
    private var conv: Conversation? = null
    /** Per-app chat theme (colors, bubble style). */
    var theme: ChatTheme? = null
    private var items: List<ChatMessage> = emptyList()

    fun setData(c: Conversation) {
        conv = c
        items = ArrayList(c.messages)
        notifyDataSetChanged()
    }

    /** Only the last few of my replies can show "Not confirmed yet". */
    private fun isRecentOwn(position: Int): Boolean = position >= items.size - 5

    companion object {
        const val UNCONFIRMED_AFTER_MS = 25_000L

        /** Can this outgoing message be sent again by tapping it? */
        fun canRetry(m: ChatMessage): Boolean {
            if (!m.outgoing) return false
            if (m.status == ChatMessage.STATUS_FAILED) return true
            val age = System.currentTimeMillis() - m.time
            return m.status == ChatMessage.STATUS_OK && age in UNCONFIRMED_AFTER_MS..(10 * 60_000L)
        }
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ChatMessage = items[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (items[position].outgoing) 1 else 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val m = items[position]
        val v = convertView ?: LayoutInflater.from(ctx).inflate(
            if (m.outgoing) R.layout.item_msg_out else R.layout.item_msg_in, parent, false
        )
        val prev = items.getOrNull(position - 1)
        val next = items.getOrNull(position + 1)

        // Date chip ("Today", "07 Nov") when the day changes.
        val header = v.findViewById<TextView>(R.id.msg_time_header)
        if (prev == null || !TimeFmt.sameDay(prev.time, m.time)) {
            header.visibility = View.VISIBLE
            header.text = TimeFmt.day(m.time)
        } else {
            header.visibility = View.GONE
        }
        val msgText = v.findViewById<TextView>(R.id.msg_text)
        val isCall = m.kind == ChatMessage.KIND_CALL
        msgText.text = if (isCall) {
            (if (m.text.contains("video", true)) "📹  " else "📞  ") + m.text
        } else m.text
        // Exact time inside every bubble: "06:11 pm" (or 18:11 with 24-hour time).
        val timeTv = v.findViewById<TextView>(R.id.msg_time)
        // ✓ = handed to Instagram/WhatsApp, ✓✓ = the app confirmed it.
        timeTv.text = TimeFmt.time(ctx, m.time) + when {
            !m.outgoing -> ""
            m.status == ChatMessage.STATUS_CONFIRMED -> " ✓✓"
            m.status == ChatMessage.STATUS_OK -> " ✓"
            m.status == ChatMessage.STATUS_SENDING -> " ⏳"
            else -> ""
        }
        val bubble = v.findViewById<View>(R.id.msg_bubble)
        val t = theme
        if (t != null) {
            Themes.styleBubble(bubble, msgText, timeTv, t, m.outgoing)
            header.setTextColor(Themes.subtleOn(t.chatBg))
            if (!m.outgoing) v.findViewById<TextView>(R.id.msg_sender).setTextColor(Themes.subtleOn(t.chatBg))
        }
        if (isCall && m.text.startsWith("Missed")) msgText.setTextColor(ctx.getColor(R.color.unread_red))

        if (m.outgoing) {
            val status = v.findViewById<TextView>(R.id.msg_status)
            val age = System.currentTimeMillis() - m.time
            when {
                m.status == ChatMessage.STATUS_FAILED -> {
                    status.visibility = View.VISIBLE
                    status.setTextColor(ctx.getColor(R.color.unread_red))
                    status.text = "Not sent · tap to retry"
                }
                // Sent, but the app hasn't confirmed it yet after a while (only for my newest replies).
                m.status == ChatMessage.STATUS_OK && age in UNCONFIRMED_AFTER_MS..(10 * 60_000L) && isRecentOwn(position) -> {
                    status.visibility = View.VISIBLE
                    status.setTextColor(ctx.getColor(R.color.text_secondary))
                    status.text = "Not confirmed yet · tap to send again"
                }
                m.status == ChatMessage.STATUS_SENDING && age > 3_000L -> {
                    status.visibility = View.VISIBLE
                    status.setTextColor(ctx.getColor(R.color.text_secondary))
                    status.text = "Waiting to send…"
                }
                else -> status.visibility = View.GONE
            }
        } else {
            val c = conv
            val group = c?.isGroup == true
            val lastOfRun = next == null || next.outgoing || next.sender != m.sender
            val avatar = v.findViewById<ImageView>(R.id.msg_avatar)
            avatar.visibility = if (lastOfRun) View.VISIBLE else View.INVISIBLE
            if (lastOfRun) {
                val s = m.sender
                avatar.setImageBitmap(if (group && s != null) Avatars.letterFor(s) else if (c != null) Avatars.forConv(c) else Avatars.letterFor("?"))
            }
            val senderTv = v.findViewById<TextView>(R.id.msg_sender)
            val firstOfRun = prev == null || prev.outgoing || prev.sender != m.sender
            if (group && m.sender != null && firstOfRun) {
                senderTv.visibility = View.VISIBLE
                senderTv.text = m.sender
            } else {
                senderTv.visibility = View.GONE
            }
        }
        return v
    }
}

/** Instagram Activity tab: new instants, stories, follows, follow requests... */
class ActivityAdapter(private val ctx: Context) : BaseAdapter() {
    private var items: List<ActivityItem> = emptyList()

    fun setData(list: List<ActivityItem>) {
        items = list
        notifyDataSetChanged()
    }

    var theme: ChatTheme? = null

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ActivityItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_activity, parent, false)
        val a = items[position]
        v.findViewById<ImageView>(R.id.act_avatar).setImageBitmap(a.avatar ?: AppIcons.big(ctx, a.pkg))
        val title = v.findViewById<TextView>(R.id.act_title)
        title.text = a.title
        title.setTypeface(null, if (a.unread) Typeface.BOLD else Typeface.NORMAL)
        v.findViewById<TextView>(R.id.act_text).text = a.text
        v.findViewById<TextView>(R.id.act_time).text = TimeFmt.short(ctx, a.time)
        theme?.let { t ->
            title.setTextColor(Themes.onColor(t.chatBg))
            v.findViewById<TextView>(R.id.act_text).setTextColor(Themes.subtleOn(t.chatBg))
            v.findViewById<TextView>(R.id.act_time).setTextColor(Themes.subtleOn(t.chatBg))
        }
        v.findViewById<View>(R.id.act_dot).visibility = if (a.unread) View.VISIBLE else View.GONE
        return v
    }
}
