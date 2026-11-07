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
}
