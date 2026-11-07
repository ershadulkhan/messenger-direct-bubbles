package com.ershad.directbubbles.settings

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ershad.directbubbles.R
import com.ershad.directbubbles.service.BubbleNotificationListener

/** Permissions & battery status, each row opens the right system screen. */
class PermissionsActivity : Activity() {

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun status(ok: Boolean) = TextView(this).apply {
        text = if (ok) "✅" else "⚠️"
        textSize = 20f
    }

    private fun build() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getColor(R.color.window_bg))
        val bar = SettingsUi.topBar(this, "Permissions & Battery", 0xFF8E2DE2.toInt())
        root.addView(bar)
        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val notifOk = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, BubbleNotificationListener::class.java))
        val overlayOk = Settings.canDrawOverlays(this)

        list.addView(SettingsUi.section(this, "Required"))
        list.addView(SettingsUi.row(
            this, "Notification access",
            if (notifOk) "Granted — DMs become chat heads and replies can be sent"
            else "Needed to read DMs and send replies. Turn on \"Direct Bubbles\" in the list.",
            status(notifOk)
        ) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.first)
        list.addView(SettingsUi.row(
            this, "Allow restricted settings",
            "Notification access switch greyed out (Android 13+)? Open App info → ⋮ (top-right) → Allow restricted settings.",
            null
        ) { openAppInfo() }.first)
        list.addView(SettingsUi.row(
            this, "Display over other apps",
            if (overlayOk) "Allowed — chat heads can float over any app" else "Needed for the floating chat heads",
            status(overlayOk)
        ) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }.first)

        if (Build.VERSION.SDK_INT >= 33) {
            val postOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            list.addView(SettingsUi.section(this, "Optional"))
            list.addView(SettingsUi.row(
                this, "Notifications (Test button only)",
                "Lets the Test ChatHead send realistic test messages. Direct Bubbles never shows its own notification.",
                status(postOk)
            ) {
                if (!postOk) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }.first)
        }

        list.addView(SettingsUi.section(this, "Battery"))
        list.addView(SettingsUi.row(
            this, "Background activity",
            "Vivo / OriginOS: App info → Battery → allow background activity, so chat heads appear reliably. " +
                "Direct Bubbles runs no service and no timers: Android wakes it only when a notification arrives.",
            null
        ) { openAppInfo() }.first)
        list.addView(SettingsUi.row(
            this, "Battery optimization list",
            "Optional: exclude Direct Bubbles if chat heads stop appearing after a while.",
            null
        ) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.first)

        setContentView(root)
        SettingsUi.fitBars(root, bar)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
    }

    private fun openAppInfo() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open settings on this phone", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
