package com.ershad.directbubbles.settings

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.overlay.OverlayController

/** Preference screen (every option is saved in SharedPreferences and used by the app). */
class PreferencesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getColor(R.color.window_bg))
        val bar = SettingsUi.topBar(this, "Preference", ACCENT)
        root.addView(bar)
        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        list.addView(SettingsUi.section(this, "Notification"))
        check(list, "Clear notifications on appear", "Clears the app's notification when its ChatHead appears",
            Prefs.clearOnAppear(this)) { Prefs.setClearOnAppear(this, it) }
        check(list, "Clear notifications on removed", "Clears the app's notification when its ChatHead is closed",
            Prefs.clearOnRemoved(this)) { Prefs.setClearOnRemoved(this, it) }
        check(list, "Clear unread badge with notification", "Resets the unread counter when the notification is cleared (e.g. you read it in the app)",
            Prefs.clearUnreadWithNotif(this)) { Prefs.setClearUnreadWithNotif(this, it) }

        list.addView(SettingsUi.section(this, "General"))
        check(list, "Message Preview", "Shows a small preview of the incoming message next to the ChatHead",
            Prefs.messagePreview(this)) { Prefs.setMessagePreview(this, it) }
        slider(list, "Preview Timeout", "Seconds the message preview stays on screen", 1, 10, Prefs.previewTimeout(this), "") {
            Prefs.setPreviewTimeout(this, it)
        }
        check(list, "Instant Reply", "Tap on the Message Preview to open the chat with the keyboard ready",
            Prefs.instantReply(this)) { Prefs.setInstantReply(this, it) }
        slider(list, "Bubble Size", "Size of the floating ChatHead", 40, 80, Prefs.bubbleSize(this), " dp") {
            Prefs.setBubbleSize(this, it)
            OverlayController.onBubbleSizeChanged()
        }
        check(list, "Remember last position", "Place the new ChatHead at the position it was last closed",
            Prefs.rememberPosition(this)) { Prefs.setRememberPosition(this, it) }
        check(list, "Close ChatHeads on app launch", "Closes a person's ChatHead when you open their chat in Instagram/WhatsApp (from the bubble or the notification)",
            Prefs.closeOnAppLaunch(this)) { Prefs.setCloseOnAppLaunch(this, it) }
        check(list, "Use 24 hours time", "Show message times as 18:12 instead of 06:12 pm",
            Prefs.use24h(this)) { Prefs.setUse24h(this, it) }

        val spacer = View(this)
        list.addView(spacer, LinearLayout.LayoutParams(1, SettingsUi.dp(this, 32)))

        setContentView(root)
        SettingsUi.fitBars(root, bar)
    }

    private fun check(list: LinearLayout, title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val cb = CheckBox(this)
        cb.isChecked = value
        cb.isClickable = false
        cb.buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        val (row, _) = SettingsUi.row(this, title, sub, cb) {
            cb.isChecked = !cb.isChecked
            onChange(cb.isChecked)
        }
        list.addView(row)
    }

    private fun slider(
        list: LinearLayout, title: String, sub: String, min: Int, max: Int, value: Int, unit: String,
        onChange: (Int) -> Unit,
    ) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(SettingsUi.dp(this, 20), SettingsUi.dp(this, 14), SettingsUi.dp(this, 16), SettingsUi.dp(this, 6))
        val t = TextView(this)
        t.text = title
        t.setTextColor(getColor(R.color.text_primary))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        box.addView(t)
        val s = TextView(this)
        s.text = sub
        s.setTextColor(getColor(R.color.text_secondary))
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        box.addView(s)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val seek = SeekBar(this)
        seek.min = min
        seek.max = max
        seek.progress = value
        seek.progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        val v = TextView(this)
        v.text = "$value$unit"
        v.setTextColor(getColor(R.color.text_secondary))
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        v.minWidth = SettingsUi.dp(this, 52)
        v.gravity = Gravity.END
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                v.text = "$p$unit"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                onChange(sb?.progress ?: value)
            }
        })
        row.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(v)
        box.addView(row)
        list.addView(box)
    }

    companion object {
        val ACCENT = 0xFFC2255C.toInt()

        fun dp(c: Context, v: Int) = SettingsUi.dp(c, v)
    }
}
