package com.ershad.directbubbles.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.AppIcons
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.core.Themes

/** Per-app settings: Auto Mark as Read, Themes, and keyword filters. */
class AppSettingsActivity : Activity() {

    private lateinit var pkg: String
    private val filterSubs = HashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
    }

    override fun onResume() {
        super.onResume()
        if (::pkg.isInitialized) build()   // theme colour may have changed
    }

    private fun build() {
        val label = AppIcons.label(this, pkg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getColor(R.color.window_bg))

        val bar = SettingsUi.topBar(this, "$label Settings", Themes.forPkg(this, pkg).titleBar)
        root.addView(bar)

        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ----- General -----
        list.addView(SettingsUi.section(this, "General"))
        val check = CheckBox(this)
        check.isChecked = Prefs.autoMarkRead(this, pkg)
        check.isClickable = false
        val (markRow, _) = SettingsUi.row(
            this, "Auto Mark as Read",
            "Incoming messages are automatically marked as read when you view them in the chat window (the sender sees them as read).",
            check
        ) {
            val on = !check.isChecked
            check.isChecked = on
            Prefs.setAutoMarkRead(this, pkg, on)
        }
        list.addView(markRow)

        val hideCheck = CheckBox(this)
        hideCheck.isChecked = Prefs.hideOriginal(this, pkg)
        hideCheck.isClickable = false
        val (hideRow, _) = SettingsUi.row(
            this, "Bubble only (hide $label notifications)",
            "After a message reaches its chat bubble, remove $label's own notification from the notification panel. Replies still work.",
            hideCheck
        ) {
            val on = !hideCheck.isChecked
            hideCheck.isChecked = on
            Prefs.setHideOriginal(this, pkg, on)
        }
        list.addView(hideRow)

        val (themeRow, _) = SettingsUi.row(this, "Themes", "Set up the colours and style of the $label chat window") {
            startActivity(Intent(this, ThemeActivity::class.java).putExtra(EXTRA_PKG, pkg))
        }
        list.addView(themeRow)

        val (inboxThemeRow, _) = SettingsUi.row(this, "Inbox & Activity Theme", "Separate colours for the main $label inbox page and its Activity list") {
            startActivity(Intent(this, ThemeActivity::class.java).putExtra(EXTRA_PKG, pkg).putExtra(ThemeActivity.EXTRA_INBOX, true))
        }
        list.addView(inboxThemeRow)

        // ----- Filter chats -----
        list.addView(SettingsUi.section(this, "Filter Chats"))
        addFilterRow(list, Prefs.FILTER_CONTAINS, "Filter Chats containing Keyword", "Don't show chat heads for titles containing a specific keyword")
        addFilterRow(list, Prefs.FILTER_STARTS, "Filter Chats starting with Keyword", "Don't show chat heads for titles starting with a specific keyword")
        addFilterRow(list, Prefs.FILTER_ENDS, "Filter Chats ending with Keyword", "Don't show chat heads for titles ending with a specific keyword")

        val note = TextView(this)
        note.text = "Tip: separate several keywords with commas, e.g.  Family, Office, +880. Matching ignores upper/lower case."
        note.setTextColor(getColor(R.color.text_secondary))
        note.textSize = 13f
        note.setPadding(SettingsUi.dp(this, 20), SettingsUi.dp(this, 12), SettingsUi.dp(this, 20), SettingsUi.dp(this, 24))
        list.addView(note)

        setContentView(root)
        SettingsUi.fitBars(root, bar)
    }

    private fun addFilterRow(list: LinearLayout, kind: String, title: String, desc: String) {
        val (row, sub) = SettingsUi.row(this, title, summary(kind, desc)) { editFilter(kind, title, desc) }
        filterSubs[kind] = sub
        list.addView(row)
    }

    private fun summary(kind: String, desc: String): String {
        val v = Prefs.filter(this, pkg, kind)
        return if (v.isBlank()) desc else "Hiding: $v"
    }

    private fun editFilter(kind: String, title: String, desc: String) {
        val edit = EditText(this)
        edit.setText(Prefs.filter(this, pkg, kind))
        edit.hint = "Keywords, separated by commas"
        edit.inputType = InputType.TYPE_CLASS_TEXT
        val pad = SettingsUi.dp(this, 20)
        val box = LinearLayout(this)
        box.setPadding(pad, SettingsUi.dp(this, 8), pad, 0)
        box.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setFilter(this, pkg, kind, edit.text.toString())
                filterSubs[kind]?.text = summary(kind, desc)
            }
            .setNeutralButton("Clear") { _, _ ->
                Prefs.setFilter(this, pkg, kind, "")
                filterSubs[kind]?.text = summary(kind, desc)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_PKG = "pkg"

        fun intent(c: Context, pkg: String): Intent =
            Intent(c, AppSettingsActivity::class.java).putExtra(EXTRA_PKG, pkg)
    }
}
