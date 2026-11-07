package com.ershad.directbubbles.core

import android.content.Context
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFmt {
    /** "06:11 pm" or "18:11" (Preferences → Use 24 hours time). */
    fun time(c: Context, t: Long): String {
        val f = if (Prefs.use24h(c)) "HH:mm" else "hh:mm a"
        return SimpleDateFormat(f, Locale.ENGLISH).format(Date(t)).lowercase(Locale.ENGLISH)
    }

    /** Compact time for the inbox: now, 5m, 2h, 06:11 pm, Mon, 12 Oct. */
    fun short(c: Context, t: Long): String {
        val d = System.currentTimeMillis() - t
        return when {
            d < 60_000L -> "now"
            d < 3_600_000L -> "${d / 60_000L}m"
            DateUtils.isToday(t) -> time(c, t)
            d < 6L * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(t))
        }
    }

    /** Date chip inside a chat: "Today", "Yesterday", "07 Nov". */
    fun day(t: Long): String = when {
        DateUtils.isToday(t) -> "Today"
        DateUtils.isToday(t + 86_400_000L) -> "Yesterday"
        else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(t))
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
