package com.ershad.directbubbles.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    /** Instagram and WhatsApp (plus WhatsApp Business) are on by default. */
    val DEFAULT_APPS: Set<String> = setOf("com.instagram.android", "com.whatsapp", "com.whatsapp.w4b")

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun enabledApps(c: Context): Set<String> =
        sp(c).getStringSet("apps", null)?.toSet() ?: DEFAULT_APPS

    fun setEnabledApps(c: Context, apps: Set<String>) {
        sp(c).edit().putStringSet("apps", HashSet(apps)).apply()
    }

    fun bubblesOn(c: Context): Boolean = sp(c).getBoolean("bubbles_on", true)

    fun setBubblesOn(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("bubbles_on", on).apply()
    }

    fun bubbleOnLeft(c: Context): Boolean = sp(c).getBoolean("bubble_left", false)

    fun bubbleY(c: Context, default: Int): Int = sp(c).getInt("bubble_y", default)

    fun saveBubblePos(c: Context, left: Boolean, y: Int) {
        sp(c).edit().putBoolean("bubble_left", left).putInt("bubble_y", y).apply()
    }

    // ---------- Preferences screen (global) ----------

    private fun b(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    private fun setB(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    /** Clear the app's notification as soon as its ChatHead appears. */
    fun clearOnAppear(c: Context) = b(c, "clear_on_appear", false)
    fun setClearOnAppear(c: Context, v: Boolean) = setB(c, "clear_on_appear", v)

    /** Clear the app's notification when its ChatHead is closed. */
    fun clearOnRemoved(c: Context) = b(c, "clear_on_removed", false)
    fun setClearOnRemoved(c: Context, v: Boolean) = setB(c, "clear_on_removed", v)

    /** When the app's notification disappears (read elsewhere), reset that chat's unread counter. */
    fun clearUnreadWithNotif(c: Context) = b(c, "clear_unread_with_notif", false)
    fun setClearUnreadWithNotif(c: Context, v: Boolean) = setB(c, "clear_unread_with_notif", v)

    fun messagePreview(c: Context) = b(c, "msg_preview", true)
    fun setMessagePreview(c: Context, v: Boolean) = setB(c, "msg_preview", v)

    /** Seconds, 1..10 */
    fun previewTimeout(c: Context): Int = sp(c).getInt("preview_timeout", 3).coerceIn(1, 10)
    fun setPreviewTimeout(c: Context, v: Int) = sp(c).edit().putInt("preview_timeout", v.coerceIn(1, 10)).apply()

    fun instantReply(c: Context) = b(c, "instant_reply", true)
    fun setInstantReply(c: Context, v: Boolean) = setB(c, "instant_reply", v)

    /** dp, 40..80 */
    fun bubbleSize(c: Context): Int = sp(c).getInt("bubble_size", 64).coerceIn(40, 80)
    fun setBubbleSize(c: Context, v: Int) = sp(c).edit().putInt("bubble_size", v.coerceIn(40, 80)).apply()

    fun rememberPosition(c: Context) = b(c, "remember_pos", true)
    fun setRememberPosition(c: Context, v: Boolean) = setB(c, "remember_pos", v)

    fun closeOnAppLaunch(c: Context) = b(c, "close_on_launch", false)
    fun setCloseOnAppLaunch(c: Context, v: Boolean) = setB(c, "close_on_launch", v)

    fun use24h(c: Context) = b(c, "use_24h", false)
    fun setUse24h(c: Context, v: Boolean) = setB(c, "use_24h", v)

    // ---------- per-app settings ----------

    fun autoMarkRead(c: Context, pkg: String): Boolean = sp(c).getBoolean("mark_read_$pkg", false)

    fun setAutoMarkRead(c: Context, pkg: String, on: Boolean) {
        sp(c).edit().putBoolean("mark_read_$pkg", on).apply()
    }

    /** "Bubble only": remove the app's own notification once it's in a chat bubble. */
    fun hideOriginal(c: Context, pkg: String): Boolean = sp(c).getBoolean("hide_orig_$pkg", false)

    fun setHideOriginal(c: Context, pkg: String, on: Boolean) {
        sp(c).edit().putBoolean("hide_orig_$pkg", on).apply()
    }

    /** Re-send a stuck reply automatically as soon as the app shows it is awake again. */
    fun autoResend(c: Context, pkg: String): Boolean = sp(c).getBoolean("auto_resend_$pkg", true)

    fun setAutoResend(c: Context, pkg: String, on: Boolean) {
        sp(c).edit().putBoolean("auto_resend_$pkg", on).apply()
    }

    /** Wake the app (open its chat underneath the bubble) when a reply gets stuck. Off unless you turn it on. */
    fun wakeApp(c: Context, pkg: String): Boolean = sp(c).getBoolean("wake_app_$pkg", false)

    fun setWakeApp(c: Context, pkg: String, on: Boolean) {
        sp(c).edit().putBoolean("wake_app_$pkg", on).apply()
    }

    fun themeJson(c: Context, pkg: String): String? = sp(c).getString("theme_$pkg", null)

    fun setThemeJson(c: Context, pkg: String, json: String?) {
        val e = sp(c).edit()
        if (json == null) e.remove("theme_$pkg") else e.putString("theme_$pkg", json)
        e.apply()
    }

    /** Separate theme for the main inbox + Activity page of an app (falls back to the chat theme). */
    fun inboxThemeJson(c: Context, pkg: String): String? = sp(c).getString("inbox_theme_$pkg", null)

    fun setInboxThemeJson(c: Context, pkg: String, json: String?) {
        val e = sp(c).edit()
        if (json == null) e.remove("inbox_theme_$pkg") else e.putString("inbox_theme_$pkg", json)
        e.apply()
    }

    const val FILTER_CONTAINS = "contains"
    const val FILTER_STARTS = "starts"
    const val FILTER_ENDS = "ends"

    /** Comma-separated keywords. */
    fun filter(c: Context, pkg: String, kind: String): String = sp(c).getString("filter_${kind}_$pkg", "") ?: ""

    fun setFilter(c: Context, pkg: String, kind: String, value: String) {
        sp(c).edit().putString("filter_${kind}_$pkg", value.trim()).apply()
    }

    private fun keywords(s: String): List<String> =
        s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** True if this chat title should NOT get a chat head. */
    fun isFiltered(c: Context, pkg: String, title: String): Boolean {
        val t = title.trim().lowercase()
        if (keywords(filter(c, pkg, FILTER_CONTAINS)).any { t.contains(it) }) return true
        if (keywords(filter(c, pkg, FILTER_STARTS)).any { t.startsWith(it) }) return true
        if (keywords(filter(c, pkg, FILTER_ENDS)).any { t.endsWith(it) }) return true
        return false
    }
}
