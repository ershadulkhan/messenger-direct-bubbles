package com.ershad.directbubbles.core

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    /** Compact time for the inbox: now, 5m, 2h, 3:45 PM, Mon, 12 Oct. */
    fun short(c: Context, t: Long): String {
        val d = System.currentTimeMillis() - t
        return when {
            d < 60_000L -> "now"
            d < 3_600_000L -> "${d / 60_000L}m"
            DateUtils.isToday(t) -> DateFormat.getTimeFormat(c).format(Date(t))
            d < 6L * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
        }
    }

    /** Separator inside a chat thread. */
    fun full(c: Context, t: Long): String =
        if (DateUtils.isToday(t)) DateFormat.getTimeFormat(c).format(Date(t))
        else DateUtils.formatDateTime(
            c, t,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
        )
}
