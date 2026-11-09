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
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.service.BubbleNotificationListener
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
    /** Set when the last tapped head was an app inbox (Instagram / WhatsApp) instead of a person. */
    private var focusPkg: String? = null
    private var snapAnimator: ValueAnimator? = null

    /**
     * Other open chat heads, drawn as a stack behind the floating bubble (like DirectChat / Messenger).
     * They are separate untouchable windows that trail the bubble with a little lag when it moves.
     */
    private class Follower(val view: View, val params: WindowManager.LayoutParams, var fx: Float, var fy: Float)
    private val followers = ArrayList<Follower>()
    private var followLoopOn = false
    private const val MAX_FOLLOWERS = 0   // no hidden heads behind the bubble (they showed as a grey haze around it)
    /** How far the floating bubble tucks past the screen edge (DirectChat-style half-tucked head). */
    private const val TUCK_DP = 18
    private var preview: View? = null
    private val hidePreviewRunnable = Runnable { hidePreview() }
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
        focusPkg = null
        ChatStore.get(key)?.let { PopupUi.unhidePlatform(it.pkg) }
        val p = popup
        if (p != null && p.attached) {
            p.refresh()
            return
        }
        showBubble(true)
        if (Prefs.messagePreview(a)) showPreview(key)
    }

    /** Opens the Messenger-style popup (a person's thread if [key] is given, otherwise the inbox). */
    fun openPopup(key: String?, focusInput: Boolean = false, inboxPkg: String? = null) {
        val a = app ?: return
        if (!canDraw()) {
            Toast.makeText(a, "Allow \"Display over other apps\" first", Toast.LENGTH_SHORT).show()
            return
        }
        hidePreview()
        // Where the floating bubble is: the chat heads fly out of (and later back into) this point.
        val anchor = bubble?.let { b ->
            val bp = bubbleParams
            if (bp != null && b.width > 0) android.graphics.Point(bp.x + b.width / 2, bp.y + b.height / 2) else null
        } ?: homePoint()
        // The bubble stays on screen until the chat window has drawn its first frame (with the tapped
        // head sitting exactly on top of it); only then is it removed. No empty frame in between.
        bubbleParams?.let { restoreAt = android.graphics.Point(it.x, it.y) }
        val p = popup ?: PopupUi(ui, wm) { collapsePopup() }.also { popup = it }
        p.onShown = { main.post { if (popup?.attached == true) removeBubble() } }
        p.anchor = anchor
        p.onFocus = { k, pkg -> focusKey = k; focusPkg = pkg }
        try {
            p.attach(key, focusInput, inboxPkg)
        } catch (e: Exception) {
            Log.w(TAG, "popup failed", e)
            popup = null
            removeBubble()
            return
        }
    }

    /**
     * Popup -> back to the floating chat head. The floating (master) bubble always comes back,
     * even when every person's head was closed, so the Instagram/WhatsApp inboxes stay reachable.
     * Only dragging the master bubble itself onto the X closes everything.
     */
    fun collapsePopup() {
        val p = popup ?: return
        p.detach {
            val a = app ?: return@detach
            if (Prefs.bubblesOn(a) && canDraw()) {
                noPopOnce = true
                showBubble(false)
            }
        }
    }

    /** Set when the bubble comes back from a minimize: appear instantly where the heads landed. */
    private var noPopOnce = false

    /** Exact spot the bubble had when the window opened: it comes back there (where the head lands). */
    private var restoreAt: android.graphics.Point? = null

    /** Centre of the floating bubble's resting spot (remembered side / height). */
    private fun homePoint(): android.graphics.Point {
        val a = app
        val screen = screenSize()
        val size = dp((a?.let { Prefs.bubbleSize(it) } ?: 64) + 20)
        val left = a != null && Prefs.rememberPosition(a) && Prefs.bubbleOnLeft(a)
        val y = if (a != null && Prefs.rememberPosition(a)) Prefs.bubbleY(a, screen.y / 4) else screen.y / 4
        val x = if (left) -dp(TUCK_DP) else screen.x - size + dp(TUCK_DP)
        return android.graphics.Point(x + size / 2, y + size / 2)
    }

    /** Preferences → Bubble Size changed: redraw the floating head at the new size. */
    fun onBubbleSizeChanged() {
        if (bubble == null) return
        removeBubble()
        showBubble(false)
    }

    /** Closes ONE person's chat head; every other head and the app inbox bubbles stay. */
    fun closeChat(key: String, clearNotifications: Boolean = true) {
        val a = app ?: return
        val c = ChatStore.get(key) ?: return
        if (clearNotifications && Prefs.clearOnRemoved(a) && !c.simulated) {
            BubbleNotificationListener.cancel(ArrayList(c.notifKeys))
        }
        ChatStore.setActive(key, false)
        if (focusKey == key) focusKey = null
    }

    /** Removes every bubble and stops the keep-alive service (chat history is kept). */
    fun dismissAll() {
        val a = app ?: return
        hidePreview()
        if (Prefs.clearOnRemoved(a)) {
            for (c in ChatStore.activeOnes()) if (!c.simulated) BubbleNotificationListener.cancel(ArrayList(c.notifKeys))
        }
        bubbleParams?.let { Prefs.saveBubblePos(a, it.x < screenSize().x / 2, it.y) }
        ChatStore.dismissAll()
        PopupUi.resetHiddenPlatforms()
        popup?.let { if (it.attached) it.detach {} }
        snapAnimator?.cancel()
        dismissTarget?.let { safeRemove(it) }
        dismissTarget = null
        val fs = ArrayList(followers)
        followers.clear()
        followLoopOn = false
        fs.forEachIndexed { i, f ->
            f.view.animate().scaleX(0f).scaleY(0f).alpha(0f).setStartDelay(40L * (i + 1)).setDuration(170)
                .withEndAction { safeRemove(f.view) }.start()
        }
        val b = bubble
        bubble = null
        bubbleParams = null
        if (b != null) {
            b.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(170).withEndAction { safeRemove(b) }.start()
        }
    }

    private fun focusConvKey(): String? {
        if (focusKey == null && focusPkg != null) return null   // an app inbox head was tapped last
        val f = ChatStore.get(focusKey)
        if (f != null && f.active) return f.key
        return ChatStore.activeOnes().firstOrNull()?.key
    }

    // ---------------- chat head ----------------

    private fun showBubble(animate: Boolean) {
        val a = app ?: return
        var v = bubble
        if (v == null) {
            ensureDismissTarget()   // added first so the chat head always stays above the X
            v = LayoutInflater.from(ui).inflate(R.layout.bubble_head, null)
            applyBubbleSize(v)
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
            val size = dp(Prefs.bubbleSize(a) + 12)
            // "Remember last position": put the head where it was last closed/moved.
            val remember = Prefs.rememberPosition(a)
            val left = remember && Prefs.bubbleOnLeft(a)
            p.x = if (left) -dp(TUCK_DP) else screen.x - size + dp(TUCK_DP)
            val y = if (remember) Prefs.bubbleY(a, screen.y / 4) else screen.y / 4
            p.y = y.coerceIn(dp(48), max(dp(48), screen.y - size - dp(96)))
            restoreAt?.let { p.x = it.x; p.y = it.y }
            restoreAt = null
            v.setOnTouchListener(BubbleTouch(v, p))
            createFollowers(p)   // added before the bubble, so they sit underneath it
            try {
                wm.addView(v, p)
            } catch (e: Exception) {
                Log.w(TAG, "add bubble failed", e)
                return
            }
            bubble = v
            bubbleParams = p
            if (noPopOnce) noPopOnce = false
            else {
                popIn(v, 0L)
                followers.forEachIndexed { i, f -> popIn(f.view, 60L * (i + 1)) }
            }
        } else if (animate) {
            popIn(v, 0L)
        }
        bindBubble()
    }

    /** Springy entrance: grows from nothing with an overshoot, like DirectChat's heads. */
    private fun popIn(v: View, delay: Long) {
        v.scaleX = 0.2f
        v.scaleY = 0.2f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(delay).setDuration(380)
            .setInterpolator(OvershootInterpolator(2.6f)).start()
    }

    // ---------------- stacked heads that trail the bubble ----------------

    /** Heads to show in the stack: other open chats (newest first), not the one on the bubble. */
    private fun followerConvs(): List<com.ershad.directbubbles.core.Conversation> {
        val top = focusConvKey()
        return ChatStore.activeOnes().filter { it.key != top }.take(MAX_FOLLOWERS)
    }

    /** At rest the other heads sit exactly behind the bubble (invisible); they only show as a trail while moving. */
    @Suppress("UNUSED_PARAMETER")
    private fun stackOffset(i: Int): Pair<Float, Float> = 0f to 0f

    private fun createFollowers(main: WindowManager.LayoutParams) {
        removeFollowers()
        val convs = followerConvs()
        // Deepest first, so the nearest follower is drawn on top of the farther one.
        for (i in convs.indices.reversed()) {
            val c = convs[i]
            val v = LayoutInflater.from(ui).inflate(R.layout.bubble_head, null)
            applyBubbleSize(v)
            v.findViewById<ImageView>(R.id.bubble_avatar).setImageBitmap(Avatars.forConv(c))
            v.findViewById<ImageView>(R.id.bubble_badge).visibility = View.GONE
            v.findViewById<View>(R.id.bubble_unread).visibility = View.GONE
            v.findViewById<View>(R.id.bubble_avatar).elevation = 3f * ui.resources.displayMetrics.density
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = main.x
            p.y = main.y
            try {
                wm.addView(v, p)
            } catch (e: Exception) {
                continue
            }
            followers.add(0, Follower(v, p, p.x.toFloat(), p.y.toFloat()))
        }
    }

    private fun removeFollowers() {
        followLoopOn = false
        for (f in followers) safeRemove(f.view)
        followers.clear()
    }

    /** Keeps the stack's pictures in sync with the open chats (hides extra followers). */
    private fun bindFollowers() {
        val convs = followerConvs()
        followers.forEachIndexed { i, f ->
            val c = convs.getOrNull(i)
            if (c == null) f.view.visibility = View.INVISIBLE
            else {
                f.view.visibility = View.VISIBLE
                f.view.findViewById<ImageView>(R.id.bubble_avatar).setImageBitmap(Avatars.forConv(c))
            }
        }
    }

    /** Each follower chases the one in front of it every frame: a soft, trailing chain. */
    private val followTick = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            val main = bubbleParams
            if (!followLoopOn || main == null || followers.isEmpty()) {
                followLoopOn = false
                return
            }
            var leadX = main.x.toFloat()
            var leadY = main.y.toFloat()
            var moving = false
            followers.forEachIndexed { i, f ->
                val (ox, oy) = stackOffset(0)
                val tx = leadX + ox
                val ty = leadY + oy
                f.fx += (tx - f.fx) * 0.30f
                f.fy += (ty - f.fy) * 0.30f
                if (kotlin.math.abs(tx - f.fx) > 0.6f || kotlin.math.abs(ty - f.fy) > 0.6f) moving = true
                else { f.fx = tx; f.fy = ty }
                val nx = f.fx.roundToInt()
                val ny = f.fy.roundToInt()
                if (nx != f.params.x || ny != f.params.y) {
                    f.params.x = nx
                    f.params.y = ny
                    try {
                        wm.updateViewLayout(f.view, f.params)
                    } catch (_: Exception) {
                    }
                }
                leadX = f.fx
                leadY = f.fy
                if (i >= MAX_FOLLOWERS) return@forEachIndexed
            }
            if (moving) android.view.Choreographer.getInstance().postFrameCallback(this)
            else followLoopOn = false
        }
    }

    private fun kickFollowers() {
        if (followers.isEmpty() || followLoopOn) return
        followLoopOn = true
        android.view.Choreographer.getInstance().postFrameCallback(followTick)
    }

    /** Preferences → Bubble Size (40–80 dp). */
    private fun applyBubbleSize(v: View) {
        val a = app ?: return
        val size = Prefs.bubbleSize(a)
        v.setPadding(0, 0, 0, 0)
        val m = 10
        val av = v.findViewById<View>(R.id.bubble_avatar)
        (av.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            width = dp(size); height = dp(size); setMargins(dp(m), dp(m), dp(m), dp(m))
        }
        // Badges sit centred on the avatar's circle edge (45 degrees), like Messenger.
        val bs = (size * 0.30f).toInt().coerceAtLeast(14)
        val off = (m + size * 0.146f - bs / 2f).toInt()
        val badge = v.findViewById<View>(R.id.bubble_badge)
        (badge.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            width = dp(bs); height = dp(bs); marginEnd = dp(off.coerceAtLeast(0)); bottomMargin = dp(off.coerceAtLeast(0))
        }
        val cnt = v.findViewById<View>(R.id.bubble_unread)
        (cnt.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            marginEnd = dp((off + 1).coerceAtLeast(0)); topMargin = dp((off + 1).coerceAtLeast(0))
        }
    }

    private fun bindBubble() {
        val v = bubble ?: return
        bindFollowers()
        val conv = ChatStore.get(focusConvKey())
        val avatar = v.findViewById<ImageView>(R.id.bubble_avatar)
        val badge = v.findViewById<ImageView>(R.id.bubble_badge)
        if (conv == null) {
            // The last tapped head was an app inbox: the bubble IS that app's icon (like DirectChat).
            // Otherwise: Direct Bubbles' own icon, which opens the inboxes.
            avatar.setImageBitmap(AppIcons.big(ui, focusPkg ?: ui.packageName))
            badge.visibility = View.GONE
            val unreadT = v.findViewById<TextView>(R.id.bubble_unread)
            val tu = ChatStore.totalUnread()
            unreadT.visibility = if (tu > 0) View.VISIBLE else View.GONE
            unreadT.text = if (tu > 99) "99+" else tu.toString()
            v.contentDescription = "Direct Bubbles inbox"
            return
        }
        badge.visibility = View.VISIBLE
        avatar.setImageBitmap(Avatars.forConv(conv))
        badge.setImageBitmap(AppIcons.badge(ui, conv.pkg))
        val unread = ChatStore.totalUnread()
        val tv = v.findViewById<TextView>(R.id.bubble_unread)
        tv.visibility = if (unread > 0) View.VISIBLE else View.GONE
        tv.text = if (unread > 99) "99+" else unread.toString()
        v.contentDescription = "Chat with ${conv.title}"
    }

    // ---------------- message preview ----------------

    /** Small card next to the minimized bubble with the new message (Preferences → Message Preview). */
    private fun showPreview(key: String) {
        val a = app ?: return
        val c = ChatStore.get(key) ?: return
        val bp = bubbleParams ?: return
        val m = c.last ?: return
        hidePreview()
        val v = LayoutInflater.from(ui).inflate(R.layout.message_preview, null)
        v.findViewById<TextView>(R.id.preview_name).text = c.title
        v.findViewById<TextView>(R.id.preview_text).text = m.text
        val screen = screenSize()
        val bubbleW = dp(Prefs.bubbleSize(a) + 12)
        val onLeft = bp.x + bubbleW / 2 < screen.x / 2
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (onLeft) {
            p.gravity = Gravity.TOP or Gravity.START
            p.x = bp.x + bubbleW
        } else {
            p.gravity = Gravity.TOP or Gravity.END
            p.x = screen.x - bp.x
        }
        p.y = bp.y + dp(6)
        v.setOnClickListener {
            hidePreview()
            // "Instant Reply": open the chat with the keyboard ready.
            openPopup(key, focusInput = Prefs.instantReply(a))
        }
        try {
            wm.addView(v, p)
            preview = v
            v.startAnimation(AnimationUtils.loadAnimation(ui, R.anim.bounce))
            main.postDelayed(hidePreviewRunnable, Prefs.previewTimeout(a) * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "preview failed", e)
        }
    }

    private fun hidePreview() {
        main.removeCallbacks(hidePreviewRunnable)
        preview?.let { safeRemove(it) }
        preview = null
    }

    private fun removeBubble() {
        hidePreview()
        snapAnimator?.cancel()
        removeFollowers()
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleParams = null
        dismissTarget?.let { safeRemove(it) }
        dismissTarget = null
    }

    private fun refreshNow() {
        if (bubble != null) bindBubble()
        popup?.let { if (it.attached) it.refresh() }
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
        private var tx = 0
        private var ty = 0
        private var following = false

        /** Eases the bubble toward the finger every frame (buttery, slightly springy follow). */
        private val frame = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (!following) return
                val dx = tx - p.x
                val dy = ty - p.y
                if (kotlin.math.abs(dx) > 0 || kotlin.math.abs(dy) > 0) {
                    p.x += if (kotlin.math.abs(dx) <= 1) dx else (dx * 0.42f).toInt()
                    p.y += if (kotlin.math.abs(dy) <= 1) dy else (dy * 0.42f).toInt()
                    updateBubble()
                }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun startFollow() {
            if (following) return
            following = true
            android.view.Choreographer.getInstance().postFrameCallback(frame)
        }

        private fun stopFollow() {
            following = false
            android.view.Choreographer.getInstance().removeFrameCallback(frame)
        }

        private var vt: android.view.VelocityTracker? = null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            // Track speed in screen coordinates (the window itself moves under the finger).
            val ev = MotionEvent.obtain(e)
            ev.setLocation(e.rawX, e.rawY)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = android.view.VelocityTracker.obtain() }
            vt?.addMovement(ev)
            ev.recycle()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    inTarget = false
                    snapAnimator?.cancel()
                    hidePreview()
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        tx = p.x
                        ty = p.y
                        showTarget()
                        startFollow()
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
                        tx = nx
                        ty = ny
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(OvershootInterpolator(3.2f)).start()
                    stopFollow()
                    if (dragging) {
                        if (inTarget) { p.x = tx; p.y = ty; updateBubble() }
                        hideTarget()
                        val t = vt
                        t?.computeCurrentVelocity(1000)
                        if (inTarget) dismissAll() else snapToEdge(t?.xVelocity ?: 0f, t?.yVelocity ?: 0f)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        openPopup(focusConvKey(), inboxPkg = focusPkg)
                    }
                    dragging = false
                    inTarget = false
                    vt?.recycle()
                    vt = null
                }
            }
            return true
        }
    }

    /** Spring-like snap to the nearest screen edge. */
    private fun snapToEdge(vx: Float = 0f, vy: Float = 0f) {
        val v = bubble ?: return
        val p = bubbleParams ?: return
        val a = app ?: return
        val screen = screenSize()
        val size = if (v.width > 0) v.width else dp(76)
        // A fling throws the bubble to the side it was thrown towards, carrying its vertical speed.
        val fling = kotlin.math.abs(vx) > dp(700)
        val left = if (fling) vx < 0 else p.x + size / 2 < screen.x / 2
        val tx = if (left) -dp(TUCK_DP) else screen.x - size + dp(TUCK_DP)
        val ty = (p.y + (vy * 0.18f).toInt()).coerceIn(dp(48), max(dp(48), screen.y - size - dp(96)))
        val sx = p.x
        val sy = p.y
        snapAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 520
        anim.interpolator = OvershootInterpolator(1.1f)
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
        kickFollowers()
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
