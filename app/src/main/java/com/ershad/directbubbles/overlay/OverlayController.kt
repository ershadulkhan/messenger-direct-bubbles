package com.ershad.directbubbles.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.AppIcons
import com.ershad.directbubbles.core.Avatars
import com.ershad.directbubbles.core.ChatStore
import com.ershad.directbubbles.core.Notifications
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.service.BubbleKeepAliveService
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns every overlay window: the floating chat head, the drag-to-dismiss "X" target and the popup.
 * Windows exist only while a chat is active; when everything is dismissed they are all removed.
 */
@SuppressLint("StaticFieldLeak")
object OverlayController {
    private const val TAG = "Overlay"
    private const val TARGET_SIZE_DP = 64
    private const val TARGET_BOTTOM_DP = 64

    private var app: Context? = null
    private lateinit var ui: Context
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dismissTarget: View? = null
    private var popup: PopupUi? = null
    private var focusKey: String? = null
    private var snapAnimator: ValueAnimator? = null
    private val refreshRunnable = Runnable { refreshNow() }

    fun init(c: Context) {
        if (app != null) return
        val a = c.applicationContext
        app = a
        ui = ContextThemeWrapper(a, R.style.AppTheme)
        wm = a.getSystemService(WindowManager::class.java)
        ChatStore.init(a)
        ChatStore.addListener {
            main.removeCallbacks(refreshRunnable)
            main.post(refreshRunnable)
        }
    }

    private fun canDraw(): Boolean = app?.let { Settings.canDrawOverlays(it) } == true

    /** Called when new incoming messages arrive for [key]. */
    fun onNewMessages(key: String) {
        val a = app ?: return
        if (!Prefs.bubblesOn(a) || !canDraw()) return
        focusKey = key
        val p = popup
        if (p != null && p.attached) {
            p.refresh()
            BubbleKeepAliveService.start(a)
            return
        }
        showBubble(true)
    }

    /** Opens the Messenger-style popup (a person's thread if [key] is given, otherwise the inbox). */
    fun openPopup(key: String?) {
        val a = app ?: return
        if (!canDraw()) {
            Toast.makeText(a, "Allow \"Display over other apps\" first", Toast.LENGTH_SHORT).show()
            return
        }
        removeBubble()
        val p = popup ?: PopupUi(ui, wm) { collapsePopup() }.also { popup = it }
        try {
            p.attach(key)
        } catch (e: Exception) {
            Log.w(TAG, "popup failed", e)
            popup = null
            return
        }
        BubbleKeepAliveService.start(a)
    }

    /** Popup -> back to the floating chat head (or nothing if no chat is active). */
    fun collapsePopup() {
        val p = popup ?: return
        p.detach {
            val a = app ?: return@detach
            if (ChatStore.activeOnes().isNotEmpty() && Prefs.bubblesOn(a) && canDraw()) showBubble(false)
            else BubbleKeepAliveService.stop(a)
        }
    }

    /** Removes every bubble and stops the keep-alive service (chat history is kept). */
    fun dismissAll() {
        val a = app ?: return
        ChatStore.dismissAll()
        popup?.let { if (it.attached) it.detach {} }
        snapAnimator?.cancel()
        dismissTarget?.let { safeRemove(it) }
        dismissTarget = null
        val b = bubble
        bubble = null
        bubbleParams = null
        if (b != null) {
            b.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(170).withEndAction { safeRemove(b) }.start()
        }
        BubbleKeepAliveService.stop(a)
    }

    private fun focusConvKey(): String? {
        val f = ChatStore.get(focusKey)
        if (f != null && f.active) return f.key
        return ChatStore.activeOnes().firstOrNull()?.key
    }

    // ---------------- chat head ----------------

    private fun showBubble(animate: Boolean) {
        val a = app ?: return
        if (focusConvKey() == null) return
        var v = bubble
        if (v == null) {
            ensureDismissTarget()   // added first so the chat head always stays above the X
            v = LayoutInflater.from(ui).inflate(R.layout.bubble_head, null)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            val screen = screenSize()
            val size = dp(76)
            p.x = if (Prefs.bubbleOnLeft(a)) -dp(6) else screen.x - size + dp(6)
            p.y = Prefs.bubbleY(a, screen.y / 4).coerceIn(dp(48), max(dp(48), screen.y - size - dp(96)))
            v.setOnTouchListener(BubbleTouch(v, p))
            try {
                wm.addView(v, p)
            } catch (e: Exception) {
                Log.w(TAG, "add bubble failed", e)
                return
            }
            bubble = v
            bubbleParams = p
            v.startAnimation(AnimationUtils.loadAnimation(ui, R.anim.bounce))
        } else if (animate) {
            v.startAnimation(AnimationUtils.loadAnimation(ui, R.anim.bounce))
        }
        bindBubble()
        // The overlay is now visible, so Android allows starting the foreground service.
        BubbleKeepAliveService.start(a)
    }

    private fun bindBubble() {
        val v = bubble ?: return
        val conv = ChatStore.get(focusConvKey())
        if (conv == null) {
            removeBubble()
            return
        }
        v.findViewById<ImageView>(R.id.bubble_avatar).setImageBitmap(Avatars.forConv(conv))
        v.findViewById<ImageView>(R.id.bubble_badge).setImageBitmap(AppIcons.badge(ui, conv.pkg))
        val unread = ChatStore.totalUnread()
        val tv = v.findViewById<TextView>(R.id.bubble_unread)
        tv.visibility = if (unread > 0) View.VISIBLE else View.GONE
        tv.text = if (unread > 99) "99+" else unread.toString()
        v.contentDescription = "Chat with ${conv.title}"
    }

    private fun removeBubble() {
        snapAnimator?.cancel()
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleParams = null
        dismissTarget?.let { safeRemove(it) }
        dismissTarget = null
    }

    private fun refreshNow() {
        if (bubble != null) bindBubble()
        popup?.let { if (it.attached) it.refresh() }
        app?.let { Notifications.refresh(it) }
    }

    // ---------------- drag-to-dismiss target ----------------

    private fun ensureDismissTarget() {
        if (dismissTarget != null) return
        val v = LayoutInflater.from(ui).inflate(R.layout.dismiss_target, null)
        v.visibility = View.GONE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.BOTTOM
        // <= 0.8 so Android 12+ never blocks touches to the app underneath.
        p.alpha = 0.8f
        try {
            wm.addView(v, p)
            dismissTarget = v
        } catch (e: Exception) {
            Log.w(TAG, "add dismiss target failed", e)
        }
    }

    private fun showTarget() {
        val t = dismissTarget ?: return
        t.visibility = View.VISIBLE
        t.findViewById<View>(R.id.dismiss_x).startAnimation(AnimationUtils.loadAnimation(ui, R.anim.slideup))
    }

    private fun hideTarget() {
        val t = dismissTarget ?: return
        val x = t.findViewById<View>(R.id.dismiss_x)
        x.animate().cancel()
        x.scaleX = 1f
        x.scaleY = 1f
        t.visibility = View.GONE
    }

    private fun targetHover(on: Boolean) {
        val x = dismissTarget?.findViewById<View>(R.id.dismiss_x) ?: return
        val s = if (on) 1.3f else 1f
        x.animate().scaleX(s).scaleY(s).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private class BubbleTouch(private val view: View, private val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var inTarget = false
        private val slop = ViewConfiguration.get(view.context).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    inTarget = false
                    snapAnimator?.cancel()
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        showTarget()
                    }
                    if (dragging) {
                        val screen = screenSize()
                        val size = if (view.width > 0) view.width else dp(76)
                        var nx = startX + dx.toInt()
                        var ny = startY + dy.toInt()
                        val tcx = screen.x / 2
                        val tcy = screen.y - dp(TARGET_BOTTOM_DP) - dp(TARGET_SIZE_DP) / 2
                        val dist = hypot((nx + size / 2 - tcx).toFloat(), (ny + size / 2 - tcy).toFloat())
                        if (dist < dp(110)) {
                            // Magnet: the head snaps into the X.
                            nx = tcx - size / 2
                            ny = tcy - size / 2
                            if (!inTarget) {
                                inTarget = true
                                targetHover(true)
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            }
                        } else if (inTarget) {
                            inTarget = false
                            targetHover(false)
                        }
                        p.x = nx
                        p.y = ny
                        updateBubble()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (dragging) {
                        hideTarget()
                        if (inTarget) dismissAll() else snapToEdge()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        openPopup(focusConvKey())
                    }
                    dragging = false
                    inTarget = false
                }
            }
            return true
        }
    }

    /** Spring-like snap to the nearest screen edge. */
    private fun snapToEdge() {
        val v = bubble ?: return
        val p = bubbleParams ?: return
        val a = app ?: return
        val screen = screenSize()
        val size = if (v.width > 0) v.width else dp(76)
        val left = p.x + size / 2 < screen.x / 2
        val tx = if (left) -dp(6) else screen.x - size + dp(6)
        val ty = p.y.coerceIn(dp(48), max(dp(48), screen.y - size - dp(96)))
        val sx = p.x
        val sy = p.y
        snapAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 420
        anim.interpolator = OvershootInterpolator(1.4f)
        anim.addUpdateListener { va ->
            val f = va.animatedValue as Float
            p.x = (sx + (tx - sx) * f).roundToInt()
            p.y = (sy + (ty - sy) * f).roundToInt()
            updateBubble()
        }
        anim.start()
        snapAnimator = anim
        Prefs.saveBubblePos(a, left, ty)
    }

    private fun updateBubble() {
        val v = bubble ?: return
        val p = bubbleParams ?: return
        try {
            wm.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    // ---------------- helpers ----------------

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun dp(v: Int): Int = (v * ui.resources.displayMetrics.density).roundToInt()

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val pt = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(pt)
            pt
        }
    }
}
