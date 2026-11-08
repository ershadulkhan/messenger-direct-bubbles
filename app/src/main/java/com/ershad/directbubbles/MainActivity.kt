package com.ershad.directbubbles

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ershad.directbubbles.core.ChatStore
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.overlay.OverlayController
import com.ershad.directbubbles.service.BubbleNotificationListener
import com.ershad.directbubbles.settings.AppsActivity
import com.ershad.directbubbles.settings.PermissionsActivity
import com.ershad.directbubbles.settings.PreferencesActivity
import com.ershad.directbubbles.sim.Simulator

/** Home: hero "Test ChatHead" + colourful tiles (Settings, Apps, Permissions, Inbox, Clear History, About). */
class MainActivity : Activity() {

    private lateinit var statusChip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ChatStore.init(this)
        OverlayController.init(this)

        val heroTitle = findViewById<View>(R.id.hero_title)
        val baseTop = (heroTitle.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin
        findViewById<View>(R.id.main_root).setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = barInsets(insets)
            // The gradient runs under the status bar; only the title moves down.
            (heroTitle.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = baseTop + top
            heroTitle.requestLayout()
            v.setPadding(0, 0, 0, bottom)
            insets
        }

        statusChip = findViewById(R.id.status_chip)
        statusChip.setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
        findViewById<View>(R.id.btn_test).setOnClickListener { runTest() }
        findViewById<TextView>(R.id.version_text).text = "Direct Bubbles ${BuildConfig.VERSION_NAME} · by Md Ershadul Haque Khan"
        buildTiles()
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

    private class Tile(val label: String, val icon: Int, val color1: Int, val color2: Int, val onClick: () -> Unit)

    private fun buildTiles() {
        val grid = findViewById<GridLayout>(R.id.tiles)
        grid.removeAllViews()
        val tiles = listOf(
            Tile("Settings", R.drawable.ic_settings, 0xFFE2452D.toInt(), 0xFFF7931E.toInt()) {
                startActivity(Intent(this, PreferencesActivity::class.java))
            },
            Tile("Apps", R.drawable.ic_apps, 0xFFD62976.toInt(), 0xFFFF5280.toInt()) {
                startActivity(Intent(this, AppsActivity::class.java))
            },
            Tile("Permissions", R.drawable.ic_shield, 0xFF8E2DE2.toInt(), 0xFFA334FA.toInt()) {
                startActivity(Intent(this, PermissionsActivity::class.java))
            },
            Tile("Inbox", R.drawable.ic_inbox, 0xFF0A7CFF.toInt(), 0xFF3BBEFF.toInt()) { openInbox() },
            Tile("Clear History", R.drawable.ic_delete, 0xFFF7931E.toInt(), 0xFFFFC107.toInt()) { confirmClear() },
            Tile("Reply Speed", R.drawable.ic_tune, 0xFF00A884.toInt(), 0xFF25D366.toInt()) {
                startActivity(Intent(this, com.ershad.directbubbles.settings.ReplySpeedActivity::class.java))
            },
            Tile("About", R.drawable.ic_info, 0xFF5B2BD9.toInt(), 0xFF7B3FF2.toInt()) { showAbout() },
        )
        val d = resources.displayMetrics.density
        val size = (92 * d).toInt()
        for (t in tiles) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            val pad = (10 * d).toInt()
            box.setPadding(pad, pad, pad, pad)
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            if (tv.resourceId != 0) box.setBackgroundResource(tv.resourceId)

            val icon = ImageView(this)
            val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.color1, t.color2))
            bg.cornerRadius = 26 * d
            icon.background = bg
            icon.elevation = 4 * d
            icon.setImageResource(t.icon)
            val ip = (24 * d).toInt()
            icon.setPadding(ip, ip, ip, ip)
            icon.contentDescription = t.label
            box.addView(icon, LinearLayout.LayoutParams(size, size))

            val label = TextView(this)
            label.text = t.label
            label.setTextColor(getColor(R.color.text_primary))
            label.textSize = 14f
            label.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(size + (16 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * d).toInt()
            box.addView(label, lp)

            box.setOnClickListener { t.onClick() }
            grid.addView(box)
        }
    }

    private fun notificationAccessGranted(): Boolean =
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, BubbleNotificationListener::class.java))

    private fun refreshStatus() {
        val notifOk = notificationAccessGranted()
        val overlayOk = Settings.canDrawOverlays(this)
        if (notifOk && !BubbleNotificationListener.connected) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, BubbleNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }
        val missing = listOf(notifOk, overlayOk).count { !it }
        statusChip.text = if (missing == 0) "✅  Ready — chat heads are on" else "⚠️  $missing permission${if (missing > 1) "s" else ""} needed — tap to fix"
        statusChip.setTextColor(if (missing == 0) getColor(R.color.text_primary) else Color.parseColor("#FFE2452D"))
    }

    private fun runTest() {
        if (!Settings.canDrawOverlays(this)) {
            toast("First allow \"Display over other apps\"")
            startActivity(Intent(this, PermissionsActivity::class.java))
            return
        }
        val real = Simulator.run(this)
        toast(
            if (real) "3 test chats are arriving now. Reply to one — it will answer back!"
            else "3 test chats arriving (directly — turn on Notification access to test the full notification path)"
        )
    }

    private fun openInbox() {
        if (Settings.canDrawOverlays(this)) OverlayController.openPopup(null)
        else {
            toast("Allow \"Display over other apps\" first")
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage("Deletes all saved chats, calls and Instagram activity from Direct Bubbles. Nothing is deleted in Instagram or WhatsApp.")
            .setPositiveButton("Clear") { _, _ ->
                OverlayController.dismissAll()
                ChatStore.clearAll()
                toast("History cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Direct Bubbles ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "Messenger-style chat heads for Instagram & WhatsApp.\n\n" +
                    "• Replies go through the app's own notification Reply button, so they work with the app closed or locked.\n" +
                    "• No internet permission. Messages never leave your phone.\n" +
                    "• No background service: Android wakes the app only when a notification arrives.\n\n" +
                    "Made by Md Ershadul Haque Khan."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
