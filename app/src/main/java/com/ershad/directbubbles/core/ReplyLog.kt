package com.ershad.directbubbles.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Remembers the last replies sent from the popup so "Reply Speed" can show how long each one took. */
object ReplyLog {
    class Entry(val pkg: String, val title: String, val m: ChatMessage, val attempt: Int)

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun record(pkg: String, title: String, m: ChatMessage, attempt: Int) {
        entries.removeAll { it.m === m }
        entries.add(Entry(pkg, title, m, attempt))
        while (entries.size > 40) entries.removeAt(0)
    }

    @Synchronized
    fun all(): List<Entry> = entries.toList().reversed()

    @Synchronized
    fun clear() = entries.clear()

    private fun secs(ms: Long) = String.format(Locale.US, "%.1fs", ms / 1000.0)

    /** Seconds from the first time the reply was handed to the app until it confirmed. */
    private fun total(m: ChatMessage): Long = m.confirmedAt - (if (m.firstSentAt > 0) m.firstSentAt else m.sentAt)

    fun line(ctx: android.content.Context, e: Entry): String {
        val m = e.m
        val first = if (m.firstSentAt > 0) m.firstSentAt else m.sentAt
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(if (first > 0) first else m.time))
        val app = AppIcons.label(ctx, e.pkg)
        val queued = if (first > 0 && first - m.time > 800) " (typed ${secs(first - m.time)} before first send)" else ""
        val result = when {
            m.confirmedAt > 0 && first > 0 -> "confirmed ${secs(total(m))} after first send"
            m.status == ChatMessage.STATUS_FAILED -> "NOT SENT"
            m.status == ChatMessage.STATUS_SENDING -> "waiting to send"
            first > 0 -> "not confirmed after ${secs(System.currentTimeMillis() - first)}"
            else -> "?"
        }
        val retry = if (m.autoResends > 0) " resent ${m.autoResends}x" else ""
        val woke = if (m.woke) " woke-app" else ""
        return "$clock  $app · ${e.title.take(18)}  route=${m.route.ifEmpty { "?" }}$retry$woke  →  $result$queued"
    }

    fun summary(): String {
        val list = all()
        val done = list.filter { it.m.confirmedAt > 0 && (it.m.firstSentAt > 0 || it.m.sentAt > 0) }.map { total(it.m) }.sorted()
        if (list.isEmpty()) return "No replies sent yet. Send a few replies from a bubble, then come back."
        if (done.isEmpty()) return "${list.size} replies sent, none confirmed yet."
        val median = done[done.size / 2]
        val avg = done.average().toLong()
        return "${list.size} replies · ${done.size} confirmed (${done.size * 100 / list.size}%)\nmedian ${secs(median)} · average ${secs(avg)} · slowest ${secs(done.last())}"
    }

    fun report(ctx: android.content.Context): String {
        val sb = StringBuilder("Direct Bubbles reply speed\n")
        sb.append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append(" · Android ")
            .append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        sb.append(summary()).append("\n\n")
        for (e in all()) sb.append(line(ctx, e)).append('\n')
        return sb.toString()
    }
}
