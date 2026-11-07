package com.ershad.directbubbles.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * WhatsApp-style message bubble: child 0 = message text, child 1 = time ("06:11 pm ✓").
 * The time sits at the end of the text's last line when there is room, otherwise on its own
 * line below — so it is never squeezed or cut off ("06…"), however long the message is.
 */
class MessageBubbleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private val gap = (8 * density).toInt()
    /** Bubbles never get wider than this (or the space available). */
    private val maxBubble = (280 * density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val text = getChildAt(0) as TextView
        val time = getChildAt(1)
        val padH = paddingLeft + paddingRight
        val padV = paddingTop + paddingBottom
        val specW = MeasureSpec.getSize(widthMeasureSpec)
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maxBubble else min(specW, maxBubble)
        val inner = max(0, available - padH)

        time.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val timeW = time.measuredWidth
        val timeH = time.measuredHeight

        text.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val textW = text.measuredWidth
        val textH = text.measuredHeight
        val layout = text.layout
        val lines = layout?.lineCount ?: 1
        val lastLineW = if (layout != null && lines > 0) {
            kotlin.math.ceil(layout.getLineWidth(lines - 1).toDouble()).toInt() + text.paddingLeft + text.paddingRight
        } else textW

        val w: Int
        val h: Int
        when {
            // One short line: text and time side by side.
            lines <= 1 && textW + gap + timeW <= inner -> {
                w = textW + gap + timeW
                h = max(textH, timeH)
            }
            // Time fits at the end of the last line.
            lines > 1 && lastLineW + gap + timeW <= inner -> {
                w = max(textW, lastLineW + gap + timeW)
                h = textH
            }
            // Time on its own line under the text.
            else -> {
                w = max(textW, timeW)
                h = textH + timeH
            }
        }
        setMeasuredDimension(w + padH, h + padV)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val text = getChildAt(0)
        val time = getChildAt(1)
        text.layout(paddingLeft, paddingTop, paddingLeft + text.measuredWidth, paddingTop + text.measuredHeight)
        val w = r - l
        val h = b - t
        val tx = w - paddingRight - time.measuredWidth
        val ty = h - paddingBottom - time.measuredHeight
        time.layout(tx, ty, tx + time.measuredWidth, ty + time.measuredHeight)
    }

    override fun shouldDelayChildPressedState(): Boolean = false
}
