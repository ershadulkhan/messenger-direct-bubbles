package com.ershad.directbubbles.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.AppIcons
import com.ershad.directbubbles.core.Avatars
import com.ershad.directbubbles.core.ChatTheme
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.core.Themes
import com.ershad.directbubbles.sim.Simulator

/** Per-app chat window theme: presets, every colour, bubble style and input style, with a live preview. */
class ThemeActivity : Activity() {

    private lateinit var pkg: String
    private lateinit var t: ChatTheme

    private lateinit var bar: LinearLayout
    private lateinit var pvHeader: LinearLayout
    private lateinit var pvName: TextView
    private lateinit var pvSub: TextView
    private lateinit var pvBody: LinearLayout
    private lateinit var pvIn1: TextView
    private lateinit var pvOut: TextView
    private lateinit var pvIn2: TextView
    private lateinit var pvInputBar: LinearLayout
    private lateinit var pvInput: EditText
    private lateinit var pvSend: ImageView
    private lateinit var gradientSwitch: Switch
    private lateinit var bubbleSub: TextView
    private lateinit var inputSub: TextView

    private class ColorField(
        val title: String,
        val get: (ChatTheme) -> Int,
        val set: (ChatTheme, Int) -> ChatTheme,
    ) {
        lateinit var swatch: View
        lateinit var sub: TextView
    }

    private var inbox = false

    companion object { const val EXTRA_INBOX = "inbox" }

    private val fields = listOf(
        ColorField("Chat Title Bar Color", { it.titleBar }, { th, c -> th.copy(titleBar = c) }),
        ColorField("Chat Background Color", { it.chatBg }, { th, c -> th.copy(chatBg = c) }),
        ColorField("Incoming Conversation Color", { it.inBubble }, { th, c -> th.copy(inBubble = c) }),
        ColorField("Outgoing Conversation Color", { it.outBubble }, { th, c -> th.copy(outBubble = c) }),
        ColorField("Incoming Text Color", { it.inText }, { th, c -> th.copy(inText = c) }),
        ColorField("Outgoing Text Color", { it.outText }, { th, c -> th.copy(outText = c) }),
        ColorField("Send Button Color", { it.send }, { th, c -> th.copy(send = c) }),
        ColorField("Input Field Background", { it.inputBg }, { th, c -> th.copy(inputBg = c) }),
        ColorField("Input Text Color", { it.inputText }, { th, c -> th.copy(inputText = c) }),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(AppSettingsActivity.EXTRA_PKG) ?: run { finish(); return }
        inbox = intent.getBooleanExtra(EXTRA_INBOX, false)
        t = if (inbox) Themes.forInbox(this, pkg) else Themes.forPkg(this, pkg)
        build()
        render()
    }

    private fun dp(v: Int) = SettingsUi.dp(this, v)

    private fun build() {
        val label = AppIcons.label(this, pkg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getColor(R.color.window_bg))

        bar = SettingsUi.topBar(this, if (inbox) "$label Inbox Theme" else "$label Theme", t.titleBar, "Test") { runTest() }
        root.addView(bar)
        root.addView(buildPreview())

        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Presets
        list.addView(SettingsUi.section(this, "Presets"))
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(this)
        chips.setPadding(dp(16), dp(4), dp(16), dp(8))
        for ((name, preset) in Themes.PRESETS) {
            val chip = TextView(this)
            chip.text = name
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            chip.setTextColor(Themes.onColor(preset.titleBar))
            chip.setPadding(dp(16), dp(10), dp(16), dp(10))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(20).toFloat()
            bg.setColor(preset.titleBar)
            bg.setStroke(dp(2), preset.outGradientEnd ?: preset.send)
            chip.background = bg
            chip.setOnClickListener {
                t = preset
                save()
                Toast.makeText(this, "$name applied", Toast.LENGTH_SHORT).show()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            chips.addView(chip, lp)
        }
        hs.addView(chips)
        list.addView(hs)

        // Colours
        list.addView(SettingsUi.section(this, "Colors"))
        for (f in fields) {
            val sw = SettingsUi.swatch(this, f.get(t))
            val (row, sub) = SettingsUi.row(this, f.title, Themes.hex(f.get(t)), sw) {
                pickColor(f.title, f.get(t)) { c ->
                    t = f.set(t, c)
                    save()
                }
            }
            f.swatch = sw
            f.sub = sub
            list.addView(row)
            list.addView(SettingsUi.divider(this))
        }
        gradientSwitch = Switch(this)
        gradientSwitch.isClickable = false
        val (gradRow, _) = SettingsUi.row(
            this, "Gradient Outgoing Bubbles", "Messenger-style colour blend on your messages", gradientSwitch
        ) {
            t = if (t.outGradientEnd != null) t.copy(outGradientEnd = null)
            else t.copy(outGradientEnd = Color.parseColor("#A334FA"))
            save()
        }
        list.addView(gradRow)

        // Styles
        list.addView(SettingsUi.section(this, "Style"))
        val (bRow, bSub) = SettingsUi.row(this, "Conversation Bubble Style", Themes.BUBBLE_STYLES[t.bubbleStyle]) {
            AlertDialog.Builder(this)
                .setTitle("Conversation Bubble Style")
                .setSingleChoiceItems(Themes.BUBBLE_STYLES, t.bubbleStyle) { d, which ->
                    t = t.copy(bubbleStyle = which)
                    save()
                    d.dismiss()
                }
                .show()
        }
        bubbleSub = bSub
        list.addView(bRow)
        val (iRow, iSub) = SettingsUi.row(this, "Input Text Style", Themes.INPUT_STYLES[t.inputStyle]) {
            AlertDialog.Builder(this)
                .setTitle("Input Text Style")
                .setSingleChoiceItems(Themes.INPUT_STYLES, t.inputStyle) { d, which ->
                    t = t.copy(inputStyle = which)
                    save()
                    d.dismiss()
                }
                .show()
        }
        inputSub = iSub
        list.addView(iRow)

        val (resetRow, _) = SettingsUi.row(this, "Use Default Colors", "Reset to Messenger style (AMOLED black in dark mode)") {
            if (inbox) Prefs.setInboxThemeJson(this, pkg, null) else Prefs.setThemeJson(this, pkg, null)
            t = if (inbox) Themes.forInbox(this, pkg) else Themes.forPkg(this, pkg)
            render()
            Toast.makeText(this, "Default colors restored", Toast.LENGTH_SHORT).show()
        }
        list.addView(resetRow)
        val spacer = View(this)
        list.addView(spacer, LinearLayout.LayoutParams(1, dp(32)))

        setContentView(root)
        SettingsUi.fitBars(root, bar)
    }

    private fun buildPreview(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        pvHeader = LinearLayout(this)
        pvHeader.orientation = LinearLayout.HORIZONTAL
        pvHeader.gravity = Gravity.CENTER_VERTICAL
        pvHeader.setPadding(dp(14), dp(8), dp(14), dp(8))
        val av = ImageView(this)
        av.setImageBitmap(Avatars.letter("Ayesha Rahman"))
        pvHeader.addView(av, LinearLayout.LayoutParams(dp(34), dp(34)))
        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        names.setPadding(dp(10), 0, 0, 0)
        pvName = TextView(this)
        pvName.text = "Ayesha Rahman"
        pvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        pvName.paint.isFakeBoldText = true
        pvSub = TextView(this)
        pvSub.text = "${AppIcons.label(this, pkg)} · preview"
        pvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        names.addView(pvName)
        names.addView(pvSub)
        pvHeader.addView(names)
        box.addView(pvHeader)

        pvBody = LinearLayout(this)
        pvBody.orientation = LinearLayout.VERTICAL
        pvBody.setPadding(dp(12), dp(10), dp(12), dp(10))
        pvIn1 = previewBubble("Hello! How's you?", false)
        pvOut = previewBubble("Hey! 👋", true)
        pvIn2 = previewBubble("Did you see the new theme?", false)
        pvBody.addView(pvIn1)
        pvBody.addView(pvOut)
        pvBody.addView(pvIn2)
        box.addView(pvBody)

        pvInputBar = LinearLayout(this)
        pvInputBar.orientation = LinearLayout.HORIZONTAL
        pvInputBar.gravity = Gravity.CENTER_VERTICAL
        pvInputBar.setPadding(dp(10), dp(6), dp(6), dp(10))
        pvInput = EditText(this)
        pvInput.setText("I'm Good!")
        pvInput.isFocusable = false
        pvInput.isCursorVisible = false
        pvInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        pvInput.setPadding(dp(16), dp(10), dp(16), dp(10))
        pvInputBar.addView(pvInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pvSend = ImageView(this)
        pvSend.setImageResource(R.drawable.ic_send)
        val sp = LinearLayout.LayoutParams(dp(46), dp(46))
        sp.marginStart = dp(6)
        pvInputBar.addView(pvSend, sp)
        box.addView(pvInputBar)

        val divider = View(this)
        divider.setBackgroundColor(Color.parseColor("#33888888"))
        box.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return box
    }

    private fun previewBubble(text: String, outgoing: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setPadding(dp(13), dp(8), dp(13), dp(8))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (outgoing) Gravity.END else Gravity.START
        lp.topMargin = dp(4)
        tv.layoutParams = lp
        return tv
    }

    private fun save() {
        if (inbox) Prefs.setInboxThemeJson(this, pkg, t.toJson()) else Prefs.setThemeJson(this, pkg, t.toJson())
        render()
    }

    private fun render() {
        // Top bar follows the title bar colour.
        bar.setBackgroundColor(t.titleBar)
        val fg = Themes.onColor(t.titleBar)
        for (i in 0 until bar.childCount) {
            when (val v = bar.getChildAt(i)) {
                is ImageView -> v.imageTintList = ColorStateList.valueOf(fg)
                is TextView -> v.setTextColor(fg)
            }
        }
        window.statusBarColor = Color.TRANSPARENT

        Themes.styleHeader(pvHeader, t, pvName, pvSub, emptyList())
        pvBody.setBackgroundColor(t.chatBg)
        Themes.styleBubble(pvIn1, t, false)
        Themes.styleBubble(pvOut, t, true)
        Themes.styleBubble(pvIn2, t, false)
        Themes.styleInput(pvInputBar, pvInput, pvSend, t)

        for (f in fields) {
            SettingsUi.setSwatch(f.swatch, f.get(t))
            f.sub.text = Themes.hex(f.get(t))
        }
        gradientSwitch.isChecked = t.outGradientEnd != null
        bubbleSub.text = Themes.BUBBLE_STYLES[t.bubbleStyle.coerceIn(0, Themes.BUBBLE_STYLES.size - 1)]
        inputSub.text = Themes.INPUT_STYLES[t.inputStyle.coerceIn(0, Themes.INPUT_STYLES.size - 1)]
    }

    private fun runTest() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" first", Toast.LENGTH_LONG).show()
            return
        }
        Simulator.testFor(this, pkg)
        Toast.makeText(this, "A test message is arriving — tap the chat head to see this theme", Toast.LENGTH_LONG).show()
    }

    // ---------------- colour picker ----------------

    private val palette = intArrayOf(
        0xFF000000.toInt(), 0xFF121212.toInt(), 0xFF1C1C1E.toInt(), 0xFF212121.toInt(), 0xFF262626.toInt(), 0xFF3A3B3C.toInt(),
        0xFFFFFFFF.toInt(), 0xFFF0F2F5.toInt(), 0xFFF0F0F0.toInt(), 0xFFEAE6DD.toInt(), 0xFFB0B3B8.toInt(), 0xFF65676B.toInt(),
        0xFF075E55.toInt(), 0xFF008C77.toInt(), 0xFF00A884.toInt(), 0xFF25D366.toInt(), 0xFFE2FDC8.toInt(), 0xFF005C4B.toInt(),
        0xFF0A7CFF.toInt(), 0xFF3BBEFF.toInt(), 0xFF1F2C34.toInt(), 0xFF0B141A.toInt(), 0xFF7B3FF2.toInt(), 0xFFA334FA.toInt(),
        0xFFD62976.toInt(), 0xFFFF5280.toInt(), 0xFFFA383E.toInt(), 0xFFF7931E.toInt(), 0xFFFFC107.toInt(), 0xFF8BC34A.toInt(),
        0xFF26A69A.toInt(), 0xFF00BCD4.toInt(), 0xFF3F51B5.toInt(), 0xFF7E57C2.toInt(), 0xFF795548.toInt(), 0xFF607D8B.toInt(),
    )

    private fun pickColor(title: String, current: Int, onPick: (Int) -> Unit) {
        var chosen = current
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(12), dp(20), 0)

        val preview = SettingsUi.swatch(this, chosen, 40)
        val hexEdit = EditText(this)
        hexEdit.setText(Themes.hex(chosen))
        hexEdit.isSingleLine = true
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(preview)
        val ep = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        ep.marginStart = dp(12)
        top.addView(hexEdit, ep)
        box.addView(top)

        hexEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString()?.trim().orEmpty()
                try {
                    val c = Color.parseColor(if (txt.startsWith("#")) txt else "#$txt")
                    chosen = c or 0xFF000000.toInt()
                    SettingsUi.setSwatch(preview, chosen)
                } catch (_: Exception) {
                }
            }
        })

        val grid = GridLayout(this)
        grid.columnCount = 6
        grid.setPadding(0, dp(12), 0, 0)
        for (c in palette) {
            val sw = SettingsUi.swatch(this, c, 38)
            val lp = GridLayout.LayoutParams()
            lp.width = dp(38)
            lp.height = dp(38)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            sw.layoutParams = lp
            sw.setOnClickListener { hexEdit.setText(Themes.hex(c)) }
            grid.addView(sw)
        }
        box.addView(grid)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Set") { _, _ -> onPick(chosen) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
