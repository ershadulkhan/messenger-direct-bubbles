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

    fun themeJson(c: Context, pkg: String): String? = sp(c).getString("theme_$pkg", null)

    fun setThemeJson(c: Context, pkg: String, json: String?) {
        val e = sp(c).edit()
        if (json == null) e.remove("theme_$pkg") else e.putString("theme_$pkg", json)
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
