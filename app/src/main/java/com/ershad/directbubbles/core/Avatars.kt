package com.ershad.directbubbles.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.min

/** Circular profile pictures and letter avatars, drawn with plain Canvas (no libraries). */
object Avatars {
    private val PALETTE = intArrayOf(
        0xFF0A7CFF.toInt(), 0xFFA334FA.toInt(), 0xFFFF5280.toInt(), 0xFF00A884.toInt(),
        0xFFF7931E.toInt(), 0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFEC407A.toInt()
    )
    private val letterCache = HashMap<String, Bitmap>()

    fun circle(src: Bitmap, size: Int = 144): Bitmap {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val w = soft.width.coerceAtLeast(1)
        val h = soft.height.coerceAtLeast(1)
        val scale = size.toFloat() / min(w, h)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((size - w * scale) / 2f, (size - h * scale) / 2f)
        val shader = BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(m)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = shader
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    fun fromDrawable(d: Drawable, size: Int = 144): Bitmap {
        if (d is BitmapDrawable) {
            val b = d.bitmap
            if (b != null) return b
        }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    fun letter(name: String, color: Int? = null, size: Int = 144): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = color ?: PALETTE[Math.floorMod(name.hashCode(), PALETTE.size)]
        cv.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val initials = name.trim()
            .split(Regex("[\\s._\\-]+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { String(Character.toChars(it.codePointAt(0))).uppercase() }
            .ifEmpty { "?" }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.color = Color.WHITE
        tp.textSize = size * 0.4f
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textAlign = Paint.Align.CENTER
        val y = size / 2f - (tp.descent() + tp.ascent()) / 2f
        cv.drawText(initials, size / 2f, y, tp)
        return b
    }

    fun forConv(c: Conversation): Bitmap = c.avatar ?: letterFor(c.title)

    fun letterFor(name: String): Bitmap = letterCache.getOrPut(name) { letter(name) }
}

/** Small app icons used as the corner badge (Instagram / WhatsApp / ...). */
object AppIcons {
    private val KNOWN_LABELS = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.instagram.android" to "Instagram",
        "com.instagram.lite" to "Instagram Lite",
        "com.facebook.orca" to "Messenger",
        "org.telegram.messenger" to "Telegram",
    )
    private val KNOWN_COLORS = mapOf(
        "com.whatsapp" to 0xFF25D366.toInt(),
        "com.whatsapp.w4b" to 0xFF25D366.toInt(),
        "com.instagram.android" to 0xFFD62976.toInt(),
    )
    private val cache = HashMap<String, Bitmap>()

    fun badge(c: Context, pkg: String): Bitmap = cache.getOrPut(pkg) {
        val d: Drawable? = try {
            c.packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }
        if (d != null) Avatars.circle(Avatars.fromDrawable(d, 72), 72)
        else Avatars.letter(label(c, pkg), KNOWN_COLORS[pkg], 72)
    }

    fun label(c: Context, pkg: String): String {
        KNOWN_LABELS[pkg]?.let { return it }
        return try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }
    }
}
