package com.ershad.directbubbles.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.ReplyLog

/** How long each recent reply took to be accepted by Instagram / WhatsApp. Screenshot it or copy the report. */
class ReplySpeedActivity : Activity() {

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getColor(R.color.window_bg))
        val bar = SettingsUi.topBar(this, "Reply Speed", 0xFF0A7CFF.toInt(), "Refresh") { build() }
        root.addView(bar)
        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val d = SettingsUi.dp(this, 16)
        val sum = TextView(this)
        sum.text = ReplyLog.summary()
        sum.textSize = 17f
        sum.setTextColor(getColor(R.color.text_primary))
        sum.setPadding(d, d, d, d / 2)
        list.addView(sum)
        val how = TextView(this)
        how.text = "Send replies from an Instagram bubble, then open this screen. \"confirmed in 2.0s\" means Instagram accepted it " +
            "(its notification updated). Long times or \"not confirmed\" mean Instagram or the phone delayed it. " +
            "route = how the reply reached the app."
        how.textSize = 13f
        how.setTextColor(getColor(R.color.text_secondary))
        how.setPadding(d, 0, d, d)
        list.addView(how)

        val buttons = LinearLayout(this)
        buttons.setPadding(d, 0, d, d)
        val copy = TextView(this).apply {
            text = "Copy report"; textSize = 15f; setPadding(d, d / 2, d, d / 2)
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF0A7CFF.toInt())
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Reply speed", ReplyLog.report(this@ReplySpeedActivity)))
                Toast.makeText(this@ReplySpeedActivity, "Copied — paste it to Claude", Toast.LENGTH_SHORT).show()
            }
        }
        val clear = TextView(this).apply {
            text = "Clear"; textSize = 15f; setPadding(d, d / 2, d, d / 2)
            setTextColor(getColor(R.color.text_primary))
            setOnClickListener { ReplyLog.clear(); build() }
        }
        buttons.addView(copy)
        buttons.addView(clear)
        list.addView(buttons)

        for (e in ReplyLog.all()) {
            val tv = TextView(this)
            tv.text = ReplyLog.line(this, e)
            tv.textSize = 13f
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.setTextColor(getColor(R.color.text_primary))
            tv.setPadding(d, d / 3, d, d / 3)
            list.addView(tv)
        }
        setContentView(root)
        SettingsUi.fitBars(root, bar)
    }
}
