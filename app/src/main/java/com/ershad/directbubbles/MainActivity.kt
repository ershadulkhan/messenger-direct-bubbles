package com.ershad.directbubbles

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.WindowInsets
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.ershad.directbubbles.core.AppIcons
import com.ershad.directbubbles.core.ChatStore
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.core.Themes
import com.ershad.directbubbles.settings.AppSettingsActivity
import com.ershad.directbubbles.overlay.OverlayController
import com.ershad.directbubbles.service.BubbleNotificationListener
import com.ershad.directbubbles.sim.Simulator

class MainActivity : Activity() {

    private lateinit var statusNotif: TextView
    private lateinit var btnNotif: Button
    private lateinit var statusOverlay: TextView
    private lateinit var btnOverlay: Button
    private lateinit var cardPost: View
    private lateinit var statusPost: TextView
    private lateinit var btnPost: Button
    private lateinit var appsSummary: TextView
    private lateinit var switchBubbles: Switch

    private val knownMessengers = setOf(
        "com.instagram.android", "com.whatsapp", "com.whatsapp.w4b", "com.instagram.lite",
        "com.facebook.orca", "com.facebook.mlite", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.viber.voip", "com.imo.android.imoim", "com.google.android.apps.messaging", "com.snapchat.android",
        "com.discord", "com.Slack", "jp.naver.line.android", "com.microsoft.teams", "com.samsung.android.messaging",
        "com.skype.raider", "com.twitter.android", "com.linkedin.android",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ChatStore.init(this)
        OverlayController.init(this)

        // Edge-to-edge (Android 15+): keep content clear of the status and navigation bars.
        findViewById<View>(R.id.main_root).setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = barInsets(insets)
            v.setPadding(0, top, 0, bottom)
            insets
        }

        statusNotif = findViewById(R.id.status_notif)
        btnNotif = findViewById(R.id.btn_notif)
        statusOverlay = findViewById(R.id.status_overlay)
        btnOverlay = findViewById(R.id.btn_overlay)
        cardPost = findViewById(R.id.card_post)
        statusPost = findViewById(R.id.status_post)
        btnPost = findViewById(R.id.btn_post)
        appsSummary = findViewById(R.id.apps_summary)
        switchBubbles = findViewById(R.id.switch_bubbles)

        btnNotif.setOnClickListener { openSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        findViewById<View>(R.id.btn_app_info).setOnClickListener {
            openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        btnOverlay.setOnClickListener {
            openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnPost.setOnClickListener { askNotificationPermission() }
        findViewById<View>(R.id.btn_apps).setOnClickListener { chooseApps() }
        findViewById<View>(R.id.btn_test).setOnClickListener { runTest() }
        findViewById<View>(R.id.btn_open_inbox).setOnClickListener {
            if (Settings.canDrawOverlays(this)) OverlayController.openPopup(null)
            else toast("Allow \"Display over other apps\" first")
        }
        findViewById<TextView>(R.id.version_text).text = "Version ${BuildConfig.VERSION_NAME}"

    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    @Suppress("DEPRECATION")
    private fun barInsets(insets: WindowInsets): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val sys = insets.getInsets(WindowInsets.Type.systemBars())
            Pair(sys.top, sys.bottom)
        } else {
            Pair(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
        }
    }

    private fun notificationAccessGranted(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        return nm.isNotificationListenerAccessGranted(ComponentName(this, BubbleNotificationListener::class.java))
    }

    private fun hasPostPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val notifOk = notificationAccessGranted()
        statusNotif.text = if (notifOk) "✅ Granted — Instagram/WhatsApp DMs will become chat bubbles."
        else "Needed to read incoming DMs and send replies. Find \"Direct Bubbles\" in the list and turn it on."
        btnNotif.text = if (notifOk) "Open settings" else "Grant access"
        if (notifOk && !BubbleNotificationListener.connected) {
            // OEMs sometimes unbind listeners: ask Android to reconnect.
            try {
                NotificationListenerService.requestRebind(ComponentName(this, BubbleNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }

        val overlayOk = Settings.canDrawOverlays(this)
        statusOverlay.text = if (overlayOk) "✅ Allowed — chat heads can float over any app."
        else "Needed to show floating chat heads and the popup chat window."
        btnOverlay.text = if (overlayOk) "Open settings" else "Allow"

        if (Build.VERSION.SDK_INT >= 33) {
            cardPost.visibility = View.VISIBLE
            val postOk = hasPostPermission()
            statusPost.text = if (postOk) "✅ Allowed — only used by the Test button. Direct Bubbles never shows its own notification."
            else "Optional: lets the Test button send realistic test messages. Direct Bubbles never shows its own notification — the bubble holds everything."
            btnPost.text = if (postOk) "Open settings" else "Allow"
        } else {
            cardPost.visibility = View.GONE
        }

        val apps = Prefs.enabledApps(this).sortedBy { AppIcons.label(this, it).lowercase() }
        appsSummary.text = if (apps.isEmpty()) "No apps selected"
        else "Tap an app for Auto Mark as Read, chat Themes and Filters."
        buildAppRows(apps)

        switchBubbles.setOnCheckedChangeListener(null)
        switchBubbles.isChecked = Prefs.bubblesOn(this)
        switchBubbles.setOnCheckedChangeListener { _, on ->
            Prefs.setBubblesOn(this, on)
            if (!on) OverlayController.dismissAll()
        }
    }

    /** One row per enabled app: icon, name, current theme colour, "Settings ›". */
    private fun buildAppRows(apps: List<String>) {
        val list = findViewById<LinearLayout>(R.id.apps_list)
        list.removeAllViews()
        val d = resources.displayMetrics.density
        for (pkg in apps) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) row.setBackgroundResource(tv.resourceId)

            val icon = ImageView(this)
            icon.setImageBitmap(AppIcons.badge(this, pkg))
            row.addView(icon, LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()))

            val name = TextView(this)
            name.text = AppIcons.label(this, pkg)
            name.setTextColor(getColor(R.color.text_primary))
            name.textSize = 16f
            val np = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            np.marginStart = (12 * d).toInt()
            row.addView(name, np)

            val dot = View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(Themes.forPkg(this, pkg).titleBar)
            g.setStroke((1 * d).toInt(), 0x55888888)
            dot.background = g
            row.addView(dot, LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()))

            val go = TextView(this)
            go.text = "Settings ›"
            go.setTextColor(getColor(R.color.brand_blue))
            go.textSize = 14f
            go.setPadding((10 * d).toInt(), 0, 0, 0)
            row.addView(go)

            row.setOnClickListener { startActivity(AppSettingsActivity.intent(this, pkg)) }
            list.addView(row)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPostPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        } else {
            openSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun runTest() {
        if (!Settings.canDrawOverlays(this)) {
            toast("First allow \"Display over other apps\"")
            openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val real = Simulator.run(this)
        switchBubbles.isChecked = true
        toast(
            if (real) "3 test chats are arriving now. Reply to one — it will answer back!"
            else "3 test chats arriving (directly — turn on Notification access to test the full notification path)"
        )
    }

    /** App picker: Instagram & WhatsApp are on by default; any installed app can be added. */
    private fun chooseApps() {
        toast("Loading apps…")
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
                .map { it to (try { it.let { p -> pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } } catch (e: Exception) { it }) }
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
                    .setTitle("Apps that get chat bubbles")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("Save") { _, _ ->
                        val chosen = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                        Prefs.setEnabledApps(this, chosen)
                        refreshStatus()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun openSafely(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                toast("Couldn't open settings on this phone")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
