package com.ershad.directbubbles.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.AppIcons
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.core.Themes
import com.ershad.directbubbles.overlay.OverlayController

/** Apps: master on/off, the apps that get chat heads, and each app's settings (theme, filters...). */
class AppsActivity : Activity() {

    private val knownMessengers = setOf(
        "com.instagram.android", "com.whatsapp", "com.whatsapp.w4b", "com.instagram.lite",
        "com.facebook.orca", "com.facebook.mlite", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.viber.voip", "com.imo.android.imoim", "com.google.android.apps.messaging", "com.snapchat.android",
        "com.discord", "com.Slack", "jp.naver.line.android", "com.microsoft.teams", "com.samsung.android.messaging",
        "com.skype.raider", "com.twitter.android", "com.linkedin.android",
    )

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getColor(R.color.window_bg))
        val bar = SettingsUi.topBar(this, "Apps", 0xFFD62976.toInt())
        root.addView(bar)
        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val sw = Switch(this)
        sw.isChecked = Prefs.bubblesOn(this)
        sw.isClickable = false
        val (masterRow, _) = SettingsUi.row(this, "Show floating chat heads", "Turn chat heads on or off for all apps", sw) {
            sw.isChecked = !sw.isChecked
            Prefs.setBubblesOn(this, sw.isChecked)
            if (!sw.isChecked) OverlayController.dismissAll()
        }
        list.addView(masterRow)

        list.addView(SettingsUi.section(this, "Apps with chat heads"))
        val apps = Prefs.enabledApps(this).sortedBy { AppIcons.label(this, it).lowercase() }
        for (pkg in apps) {
            val dot = android.view.View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(Themes.forPkg(this, pkg).titleBar)
            g.setStroke(SettingsUi.dp(this, 1), 0x55888888)
            dot.background = g
            dot.layoutParams = LinearLayout.LayoutParams(SettingsUi.dp(this, 20), SettingsUi.dp(this, 20))
            val (row, _) = SettingsUi.row(
                this, AppIcons.label(this, pkg), "Theme · Auto Mark as Read · Bubble only · Filters", dot
            ) { startActivity(AppSettingsActivity.intent(this, pkg)) }
            val icon = ImageView(this)
            icon.setImageBitmap(AppIcons.big(this, pkg))
            val ip = LinearLayout.LayoutParams(SettingsUi.dp(this, 40), SettingsUi.dp(this, 40))
            ip.marginEnd = SettingsUi.dp(this, 14)
            row.addView(icon, 0, ip)
            list.addView(row)
        }
        if (apps.isEmpty()) {
            list.addView(SettingsUi.row(this, "No apps selected", "Add Instagram, WhatsApp or any other app below", null).first)
        }
        list.addView(SettingsUi.divider(this))
        list.addView(SettingsUi.row(this, "Add or remove apps", "Choose which installed apps get chat heads", null) { chooseApps() }.first)

        setContentView(root)
        SettingsUi.fitBars(root, bar)
    }

    /** App picker: Instagram & WhatsApp are on by default; any installed app can be added. */
    private fun chooseApps() {
        Toast.makeText(this, "Loading apps…", Toast.LENGTH_SHORT).show()
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val resolved = pm.queryIntentActivities(launcher, 0)
            val enabled = Prefs.enabledApps(this)
            val apps = resolved
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .map { p -> p to AppIcons.label(this, p) }
                .sortedWith(
                    compareByDescending<Pair<String, String>> { it.first in enabled }
                        .thenByDescending { it.first in knownMessengers }
                        .thenBy { it.second.lowercase() }
                )
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val labels = apps.map { (pkg, label) -> if (pkg in knownMessengers) "💬 $label" else label }.toTypedArray()
                val checked = BooleanArray(apps.size) { apps[it].first in enabled }
                AlertDialog.Builder(this)
                    .setTitle("Apps that get chat heads")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("Save") { _, _ ->
                        val chosen = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                        Prefs.setEnabledApps(this, chosen)
                        build()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }
}
