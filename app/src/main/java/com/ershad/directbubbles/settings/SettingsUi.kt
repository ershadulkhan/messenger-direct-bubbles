package com.ershad.directbubbles.settings

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ershad.directbubbles.R

/** Small helpers to build simple settings screens in code (no extra libraries). */
object SettingsUi {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    /** Coloured top bar with a back arrow, a title and an optional action on the right. */
    fun topBar(a: Activity, title: String, color: Int, actionText: String? = null, onAction: (() -> Unit)? = null): LinearLayout {
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(color)
        bar.setPadding(dp(a, 4), dp(a, 8), dp(a, 12), dp(a, 8))
        val fg = if (Color.luminance(color) < 0.45f) Color.WHITE else Color.parseColor("#050505")

        val back = ImageView(a)
        back.setImageResource(R.drawable.ic_back)
        back.imageTintList = android.content.res.ColorStateList.valueOf(fg)
        back.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12))
        back.contentDescription = "Back"
        back.setOnClickListener { a.finish() }
        bar.addView(back, LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)))

        val t = TextView(a)
        t.text = title
        t.setTextColor(fg)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.isSingleLine = true
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        val tp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp.marginStart = dp(a, 8)
        bar.addView(t, tp)

        if (actionText != null && onAction != null) {
            val act = TextView(a)
            act.text = actionText
            act.setTextColor(fg)
            act.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            act.setPadding(dp(a, 12), dp(a, 10), dp(a, 8), dp(a, 10))
            act.setOnClickListener { onAction() }
            bar.addView(act)
        }
        return bar
    }

    fun section(c: Context, text: String): TextView {
        val t = TextView(c)
        t.text = text
        t.setTextColor(c.getColor(R.color.section))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(dp(c, 20), dp(c, 22), dp(c, 20), dp(c, 6))
        return t
    }

    /** A tappable row: title + subtitle, with an optional view on the right (checkbox, swatch...). */
    fun row(c: Context, title: String, subtitle: String?, right: View? = null, onClick: (() -> Unit)? = null): Pair<LinearLayout, TextView> {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14))
        val tv = TypedValue()
        c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (tv.resourceId != 0) row.setBackgroundResource(tv.resourceId)

        val texts = LinearLayout(c)
        texts.orientation = LinearLayout.VERTICAL
        val t = TextView(c)
        t.text = title
        t.setTextColor(c.getColor(R.color.text_primary))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        texts.addView(t)
        val s = TextView(c)
        s.setTextColor(c.getColor(R.color.text_secondary))
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        s.text = subtitle ?: ""
        s.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        texts.addView(s)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) {
            val rp = LinearLayout.LayoutParams(
                right.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                right.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rp.marginStart = dp(c, 16)
            row.addView(right, rp)
        }
        if (onClick != null) row.setOnClickListener { onClick() }
        return Pair(row, s)
    }

    fun divider(c: Context): View {
        val v = View(c)
        v.setBackgroundColor(c.getColor(R.color.divider))
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        return v
    }

    fun swatch(c: Context, color: Int, size: Int = 44): View {
        val v = View(c)
        val d = GradientDrawable()
        d.setColor(color)
        d.setStroke(dp(c, 1), Color.parseColor("#55888888"))
        v.background = d
        v.layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
        return v
    }

    fun setSwatch(v: View, color: Int) {
        val d = v.background as? GradientDrawable ?: return
        d.setColor(color)
    }

    /** Pads a screen so it doesn't sit under the status/navigation bars (Android 15 edge-to-edge). */
    @Suppress("DEPRECATION")
    fun fitBars(root: View, topBar: View) {
        val base = topBar.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val s = insets.getInsets(WindowInsets.Type.systemBars())
                top = s.top
                bottom = s.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            // The coloured top bar extends under the status bar, like a normal app bar.
            topBar.setPadding(topBar.paddingLeft, base + top, topBar.paddingRight, topBar.paddingBottom)
            v.setPadding(0, 0, 0, bottom)
            insets
        }
    }
}
