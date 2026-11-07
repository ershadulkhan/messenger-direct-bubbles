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
import com.ershad.directbubbles.core.ChatMessage
import com.ershad.directbubbles.core.Conversation
import com.ershad.directbubbles.core.TimeFmt

/** Inbox rows: [avatar + app badge] | name | latest message · time | unread count. Newest on top. */
class InboxAdapter(private val ctx: Context) : BaseAdapter() {
    private var items: List<Conversation> = emptyList()

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
        preview.setTextColor(ctx.getColor(if (unread) R.color.text_primary else R.color.text_secondary))
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
    private var items: List<ChatMessage> = emptyList()

    fun setData(c: Conversation) {
        conv = c
        items = ArrayList(c.messages)
        notifyDataSetChanged()
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

        val header = v.findViewById<TextView>(R.id.msg_time_header)
        if (prev == null || m.time - prev.time > 10 * 60_000L) {
            header.visibility = View.VISIBLE
            header.text = TimeFmt.full(ctx, m.time)
        } else {
            header.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.msg_text).text = m.text

        if (m.outgoing) {
            val status = v.findViewById<TextView>(R.id.msg_status)
            when (m.status) {
                ChatMessage.STATUS_FAILED -> {
                    status.visibility = View.VISIBLE
                    status.text = "Not sent"
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
