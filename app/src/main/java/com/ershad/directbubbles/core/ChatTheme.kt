package com.ershad.directbubbles.core

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

/** Colors and styles of one app's chat window (thread view). */
data class ChatTheme(
    val titleBar: Int,
    val chatBg: Int,
    val inBubble: Int,
    val outBubble: Int,
    /** When set, outgoing bubbles use a gradient from [outBubble] to this color. */
    val outGradientEnd: Int?,
    val inText: Int,
    val outText: Int,
    val send: Int,
    val inputBg: Int,
    val inputText: Int,
    /** 0 = rounded (Messenger), 1 = classic with tail (WhatsApp), 2 = square */
    val bubbleStyle: Int,
    /** 0 = rounded pill, 1 = pill + round send button, 2 = flat bar */
    val inputStyle: Int,
) {
    fun toJson(): String = JSONObject()
        .put("titleBar", titleBar).put("chatBg", chatBg)
        .put("inBubble", inBubble).put("outBubble", outBubble)
        .put("outGradientEnd", outGradientEnd ?: 0).put("hasGradient", outGradientEnd != null)
        .put("inText", inText).put("outText", outText).put("send", send)
        .put("inputBg", inputBg).put("inputText", inputText)
        .put("bubbleStyle", bubbleStyle).put("inputStyle", inputStyle)
        .toString()

    companion object {
        fun fromJson(s: String, fallback: ChatTheme): ChatTheme = try {
            val o = JSONObject(s)
            ChatTheme(
                o.optInt("titleBar", fallback.titleBar),
                o.optInt("chatBg", fallback.chatBg),
                o.optInt("inBubble", fallback.inBubble),
                o.optInt("outBubble", fallback.outBubble),
                if (o.optBoolean("hasGradient")) o.optInt("outGradientEnd") else null,
                o.optInt("inText", fallback.inText),
                o.optInt("outText", fallback.outText),
                o.optInt("send", fallback.send),
                o.optInt("inputBg", fallback.inputBg),
                o.optInt("inputText", fallback.inputText),
                o.optInt("bubbleStyle", fallback.bubbleStyle),
                o.optInt("inputStyle", fallback.inputStyle),
            )
        } catch (e: Exception) {
            fallback
        }
    }
}

object Themes {
    private fun c(hex: String) = Color.parseColor(hex)

    val MESSENGER_AMOLED = ChatTheme(
        c("#000000"), c("#000000"), c("#262626"), c("#0A7CFF"), c("#A334FA"),
        c("#FFFFFF"), c("#FFFFFF"), c("#0A7CFF"), c("#1C1C1E"), c("#FFFFFF"), 0, 0
    )
    val MESSENGER_LIGHT = ChatTheme(
        c("#FFFFFF"), c("#FFFFFF"), c("#F0F0F0"), c("#0A7CFF"), c("#A334FA"),
        c("#050505"), c("#FFFFFF"), c("#0A7CFF"), c("#F0F2F5"), c("#050505"), 0, 0
    )
    val WHATSAPP_LIGHT = ChatTheme(
        c("#075E55"), c("#EAE6DD"), c("#FFFFFF"), c("#E2FDC8"), null,
        c("#161616"), c("#1B1B1B"), c("#008C77"), c("#FFFFFF"), c("#000000"), 1, 1
    )
    val WHATSAPP_DARK = ChatTheme(
        c("#1F2C34"), c("#0B141A"), c("#1F2C34"), c("#005C4B"), null,
        c("#E9EDEF"), c("#E9EDEF"), c("#00A884"), c("#1F2C34"), c("#E9EDEF"), 1, 1
    )
    val AMOLED_DARK = ChatTheme(
        c("#212121"), c("#000000"), c("#212121"), c("#212121"), null,
        c("#FFFFFF"), c("#FFFFFF"), c("#3BBEFF"), c("#212121"), c("#FFFFFF"), 0, 2
    )
    val INSTAGRAM_DARK = ChatTheme(
        c("#000000"), c("#000000"), c("#262626"), c("#7B3FF2"), c("#D62976"),
        c("#FFFFFF"), c("#FFFFFF"), c("#7B3FF2"), c("#1A1A1A"), c("#FFFFFF"), 0, 0
    )

    val PRESETS: List<Pair<String, ChatTheme>> = listOf(
        "Messenger AMOLED" to MESSENGER_AMOLED,
        "Messenger Light" to MESSENGER_LIGHT,
        "WhatsApp Light" to WHATSAPP_LIGHT,
        "WhatsApp Dark" to WHATSAPP_DARK,
        "AMOLED Dark" to AMOLED_DARK,
        "Instagram Dark" to INSTAGRAM_DARK,
    )

    val BUBBLE_STYLES = arrayOf("Rounded (Messenger)", "Classic with tail (WhatsApp)", "Square")
    val INPUT_STYLES = arrayOf("Rounded pill", "Pill + round send button", "Flat bar")

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Used when the app has no custom theme: follows the phone's light/dark mode (dark = AMOLED black). */
    fun defaultTheme(ctx: Context): ChatTheme = if (isNight(ctx)) MESSENGER_AMOLED else MESSENGER_LIGHT

    fun forPkg(ctx: Context, pkg: String): ChatTheme {
        val json = Prefs.themeJson(ctx, pkg) ?: return defaultTheme(ctx)
        return ChatTheme.fromJson(json, defaultTheme(ctx))
    }

    fun isDark(color: Int): Boolean = Color.luminance(color) < 0.45f

    fun onColor(bg: Int): Int = if (isDark(bg)) Color.WHITE else Color.parseColor("#050505")

    fun subtleOn(bg: Int): Int = if (isDark(bg)) Color.parseColor("#B3FFFFFF") else Color.parseColor("#99000000")

    fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun bubbleDrawable(ctx: Context, t: ChatTheme, outgoing: Boolean): GradientDrawable {
        val d = GradientDrawable()
        val end = t.outGradientEnd
        if (outgoing && end != null) {
            d.orientation = GradientDrawable.Orientation.TL_BR
            d.colors = intArrayOf(t.outBubble, end)
        } else {
            d.setColor(if (outgoing) t.outBubble else t.inBubble)
        }
        val big: Float
        val tail: Float
        when (t.bubbleStyle) {
            1 -> { big = dp(ctx, 10f); tail = dp(ctx, 2f) }
            2 -> { big = dp(ctx, 4f); tail = dp(ctx, 4f) }
            else -> { big = dp(ctx, 18f); tail = dp(ctx, 18f) }
        }
        val tl = if (!outgoing) tail else big
        val tr = if (outgoing) tail else big
        d.cornerRadii = floatArrayOf(tl, tl, tr, tr, big, big, big, big)
        return d
    }

    fun styleBubble(tv: TextView, t: ChatTheme, outgoing: Boolean) {
        tv.background = bubbleDrawable(tv.context, t, outgoing)
        tv.setTextColor(if (outgoing) t.outText else t.inText)
    }

    /** Header (title bar) of a chat. */
    fun styleHeader(header: View, t: ChatTheme, title: TextView, sub: TextView?, icons: List<ImageView>) {
        header.setBackgroundColor(t.titleBar)
        title.setTextColor(onColor(t.titleBar))
        sub?.setTextColor(subtleOn(t.titleBar))
        val tint = ColorStateList.valueOf(onColor(t.titleBar))
        for (i in icons) i.imageTintList = tint
    }

    /** Input bar: text field + send button. */
    fun styleInput(bar: View, edit: EditText, send: ImageView, t: ChatTheme) {
        val ctx = edit.context
        val field = GradientDrawable()
        field.setColor(t.inputBg)
        when (t.inputStyle) {
            2 -> {
                bar.setBackgroundColor(t.inputBg)
                field.cornerRadius = dp(ctx, 4f)
            }
            else -> {
                bar.setBackgroundColor(t.chatBg)
                field.cornerRadius = dp(ctx, 24f)
            }
        }
        edit.background = field
        edit.setTextColor(t.inputText)
        edit.setHintTextColor(if (isDark(t.inputBg)) Color.parseColor("#80FFFFFF") else Color.parseColor("#80000000"))
        if (t.inputStyle == 1) {
            val circle = GradientDrawable()
            circle.shape = GradientDrawable.OVAL
            circle.setColor(t.send)
            send.background = circle
            send.imageTintList = ColorStateList.valueOf(onColor(t.send))
            val p = dp(ctx, 12f).toInt()
            send.setPadding(p, p, p, p)
        } else {
            send.background = null
            send.imageTintList = ColorStateList.valueOf(t.send)
            val p = dp(ctx, 10f).toInt()
            send.setPadding(p, p, p, p)
        }
    }
}
