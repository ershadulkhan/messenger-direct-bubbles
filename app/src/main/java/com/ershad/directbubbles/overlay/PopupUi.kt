package com.ershad.directbubbles.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.ershad.directbubbles.R
import com.ershad.directbubbles.core.AppIcons
import com.ershad.directbubbles.core.Avatars
import com.ershad.directbubbles.core.ChatMessage
import com.ershad.directbubbles.core.ChatStore
import com.ershad.directbubbles.core.Prefs
import com.ershad.directbubbles.core.ReplySender
import com.ershad.directbubbles.core.Themes
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The Messenger-style popup window: a row of chat heads on top, then either the
 * vertical inbox (everyone's chats) or one person's thread with a reply bar.
 */
class PopupUi(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onCollapse: () -> Unit,
) {
    private val root = LayoutInflater.from(ctx).inflate(R.layout.popup_window, null) as PopupRootLayout
    private val panel: View = root.findViewById(R.id.panel)
    private val headsRow: LinearLayout = root.findViewById(R.id.heads_row)
    private val btnInbox: View = root.findViewById(R.id.btn_inbox)
    private val inboxRing: View = root.findViewById(R.id.inbox_ring)

    private val inboxContainer: View = root.findViewById(R.id.inbox_container)
    private val inboxList: ListView = root.findViewById(R.id.inbox_list)
    private val inboxEmpty: TextView = root.findViewById(R.id.inbox_empty)

    private val threadContainer: View = root.findViewById(R.id.thread_container)
    private val threadHeader: View = root.findViewById(R.id.thread_header)
    private val threadDivider: View = root.findViewById(R.id.thread_divider)
    private val threadList: ListView = root.findViewById(R.id.thread_list)
    private val threadAvatar: ImageView = root.findViewById(R.id.thread_avatar)
    private val threadBadge: ImageView = root.findViewById(R.id.thread_badge)
    private val threadName: TextView = root.findViewById(R.id.thread_name)
    private val threadSub: TextView = root.findViewById(R.id.thread_sub)
    private val btnBack: ImageButton = root.findViewById(R.id.btn_back)
    private val btnOpen: ImageButton = root.findViewById(R.id.btn_open)
    private val btnClose: ImageButton = root.findViewById(R.id.btn_close)
    private val replyHint: TextView = root.findViewById(R.id.reply_hint)
    private val inputBar: View = root.findViewById(R.id.input_bar)
    private val input: EditText = root.findViewById(R.id.input)
    private val btnSend: ImageButton = root.findViewById(R.id.btn_send)

    private val dismissZone: View = root.findViewById(R.id.popup_dismiss)
    private val dismissX: View = root.findViewById(R.id.popup_dismiss_x)
    private val ghost: ImageView = root.findViewById(R.id.drag_ghost)

    private val inboxAdapter = InboxAdapter(ctx)
    private val threadAdapter = ThreadAdapter(ctx)
    private var currentKey: String? = null
    private var added = false
    private val density = ctx.resources.displayMetrics.density

    var attached = false
        private set

    init {
        inboxList.adapter = inboxAdapter
        threadList.adapter = threadAdapter

        root.onBack = { if (currentKey != null) showInbox() else onCollapse() }
        root.setOnClickListener { onCollapse() }           // tap on the dimmed area
        panel.setOnClickListener { /* swallow taps inside the panel */ }
        btnInbox.setOnClickListener { showInbox() }
        root.findViewById<View>(R.id.btn_close_inbox).setOnClickListener { onCollapse() }
        btnBack.setOnClickListener { showInbox() }
        btnClose.setOnClickListener { onCollapse() }
        btnOpen.setOnClickListener { openInApp() }
        root.findViewById<View>(R.id.thread_title_box).setOnClickListener { openInApp() }
        btnSend.setOnClickListener { send() }

        inboxList.setOnItemClickListener { _, _, position, _ ->
            showThread(inboxAdapter.getItem(position).key)
        }
        inboxList.setOnItemLongClickListener { _, _, position, _ ->
            val c = inboxAdapter.getItem(position)
            ChatStore.delete(c.key)
            Toast.makeText(ctx.applicationContext, "Removed chat with ${c.title} from this app", Toast.LENGTH_SHORT).show()
            true
        }

        // Keep the panel clear of the status bar, navigation bar and keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = barInsets(insets)
            v.setPadding(0, top, 0, bottom)
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun barInsets(insets: WindowInsets): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            Pair(sys.top, max(sys.bottom, ime.bottom))
        } else {
            Pair(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
        }
    }

    @Suppress("DEPRECATION")
    fun attach(key: String?) {
        root.animate().cancel()
        panel.animate().cancel()
        root.alpha = 1f
        panel.scaleX = 1f
        panel.scaleY = 1f
        if (!added) {
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            p.gravity = Gravity.TOP or Gravity.START
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            wm.addView(root, p)
            added = true
            panel.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.popup_enter))
        }
        attached = true
        if (key != null && ChatStore.get(key) != null) showThread(key) else showInbox()
    }

    fun detach(onDone: () -> Unit) {
        if (!attached) {
            onDone()
            return
        }
        attached = false
        hideKeyboard()
        endDragUi()
        root.animate().alpha(0f).setDuration(150).start()
        panel.animate().scaleX(0.94f).scaleY(0.94f).setDuration(150).withEndAction {
            try {
                wm.removeView(root)
            } catch (_: Exception) {
            }
            added = false
            onDone()
        }.start()
    }

    fun refresh() {
        if (!attached) return
        buildHeads()
        val key = currentKey
        if (key == null) {
            val all = ChatStore.all()
            inboxAdapter.setData(all)
            inboxEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
            inboxList.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        } else {
            val c = ChatStore.get(key)
            if (c == null) {
                showInbox()
                return
            }
            applyTheme(c.pkg)
            threadAvatar.setImageBitmap(Avatars.forConv(c))
            threadBadge.setImageBitmap(AppIcons.badge(ctx, c.pkg))
            threadName.text = c.title
            val app = AppIcons.label(ctx, c.pkg)
            threadSub.text = if (c.isGroup) "Group · $app · tap to open" else "$app · tap to open in app"
            replyHint.visibility = if (c.replyAction == null) View.VISIBLE else View.GONE
            threadAdapter.setData(c)
            // Auto Mark as Read (per app): press the app's own "Mark as read" button when you view new messages.
            if (c.unread > 0 && Prefs.autoMarkRead(ctx, c.pkg)) {
                c.markReadAction?.let { ReplySender.fire(it) }
                c.markReadAction = null
            }
            ChatStore.markRead(c)
        }
    }

    /** Colors of the chat window come from the per-app theme (default: AMOLED black in dark mode). */
    private fun applyTheme(pkg: String) {
        val t = Themes.forPkg(ctx, pkg)
        threadAdapter.theme = t
        Themes.styleHeader(threadHeader, t, threadName, threadSub, listOf(btnBack, btnOpen, btnClose))
        threadDivider.setBackgroundColor(if (Themes.isDark(t.titleBar)) 0xFF1F1F1F.toInt() else 0x14000000)
        threadDivider.visibility = if (t.titleBar == t.chatBg) View.VISIBLE else View.GONE
        val r = 26 * density
        val body = GradientDrawable()
        body.setColor(t.chatBg)
        body.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        threadContainer.background = body
        threadList.setBackgroundColor(t.chatBg)
        replyHint.setTextColor(Themes.subtleOn(t.chatBg))
        Themes.styleInput(inputBar, input, btnSend, t)
    }

    private fun buildHeads() {
        while (headsRow.childCount > 1) headsRow.removeViewAt(1)
        inboxRing.visibility = if (currentKey == null) View.VISIBLE else View.INVISIBLE
        val inflater = LayoutInflater.from(ctx)
        for (c in ChatStore.activeOnes().take(15)) {
            val v = inflater.inflate(R.layout.item_head, headsRow, false)
            v.findViewById<ImageView>(R.id.head_avatar).setImageBitmap(Avatars.forConv(c))
            v.findViewById<ImageView>(R.id.head_badge).setImageBitmap(AppIcons.badge(ctx, c.pkg))
            v.findViewById<View>(R.id.head_dot).visibility = if (c.unread > 0) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.head_ring).visibility = if (c.key == currentKey) View.VISIBLE else View.INVISIBLE
            v.contentDescription = "${c.title}. Drag down to close."
            v.setOnTouchListener(HeadTouch(c.key))
            headsRow.addView(v)
        }
    }

    private fun showInbox() {
        hideKeyboard()
        currentKey = null
        threadContainer.visibility = View.GONE
        inboxContainer.visibility = View.VISIBLE
        refresh()
    }

    private fun showThread(key: String) {
        val switching = currentKey != key
        currentKey = key
        ChatStore.get(key)?.active = true   // opening a chat gives it a chat head (like Messenger)
        inboxContainer.visibility = View.GONE
        threadContainer.visibility = View.VISIBLE
        if (switching) {
            input.setText("")
            threadContainer.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.thread_enter))
        }
        refresh()
        threadList.post { threadList.setSelection(max(0, threadAdapter.count - 1)) }
    }

    /** Closes one person's chat head (history stays in the inbox). */
    private fun closeHead(key: String) {
        ChatStore.setActive(key, false)
        if (ChatStore.activeOnes().isEmpty()) {
            onCollapse()
            return
        }
        if (currentKey == key) showInbox() else refresh()
    }

    // ---------------- drag a chat head to the X ----------------

    private inner class HeadTouch(private val key: String) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var moved = false
        private var inTarget = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    moved = false
                    inTarget = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    // Mostly-vertical drag picks the head up; horizontal drags still scroll the row.
                    if (!dragging && dy > slop && abs(dy) > abs(dx)) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        startDragUi(v)
                    }
                    if (dragging) {
                        val near = moveGhost(e.rawX, e.rawY)
                        if (near != inTarget) {
                            inTarget = near
                            val s = if (near) 1.3f else 1f
                            dismissX.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(OvershootInterpolator(3f)).start()
                            if (near) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) finishDrag(v, inTarget, key)
                    else if (!moved) showThread(key)
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (dragging) finishDrag(v, false, key)
                    dragging = false
                }
            }
            return true
        }
    }

    private fun startDragUi(head: View) {
        hideKeyboard()
        ghost.setImageDrawable(head.findViewById<ImageView>(R.id.head_avatar).drawable)
        ghost.visibility = View.VISIBLE
        ghost.scaleX = 1f
        ghost.scaleY = 1f
        ghost.alpha = 1f
        head.alpha = 0f
        dismissZone.visibility = View.VISIBLE
        dismissX.scaleX = 1f
        dismissX.scaleY = 1f
        dismissX.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.slideup))
    }

    /** Moves the floating copy under the finger; returns true when it is over the X (magnet). */
    private fun moveGhost(rawX: Float, rawY: Float): Boolean {
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val xLoc = IntArray(2)
        dismissX.getLocationOnScreen(xLoc)
        val tcx = xLoc[0] + dismissX.width / 2f
        val tcy = xLoc[1] + dismissX.height / 2f
        val near = dismissX.width > 0 && hypot(rawX - tcx, rawY - tcy) < 100 * density
        val cx = if (near) tcx else rawX
        val cy = if (near) tcy else rawY
        ghost.x = cx - rootLoc[0] - ghost.width / 2f
        ghost.y = cy - rootLoc[1] - ghost.height / 2f
        return near
    }

    private fun finishDrag(head: View, drop: Boolean, key: String) {
        if (drop) {
            ghost.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160).withEndAction {
                endDragUi()
                closeHead(key)
            }.start()
        } else {
            // Fly back to its place in the row.
            val rootLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            val headLoc = IntArray(2)
            head.getLocationOnScreen(headLoc)
            val tx = headLoc[0] - rootLoc[0] + (head.width - ghost.width) / 2f
            val ty = headLoc[1] - rootLoc[1] + (head.height - ghost.height) / 2f
            ghost.animate().x(tx).y(ty).setDuration(220).setInterpolator(OvershootInterpolator(1.2f)).withEndAction {
                head.alpha = 1f
                endDragUi()
            }.start()
        }
    }

    private fun endDragUi() {
        ghost.animate().cancel()
        ghost.visibility = View.GONE
        dismissZone.visibility = View.GONE
        for (i in 1 until headsRow.childCount) headsRow.getChildAt(i).alpha = 1f
    }

    // ---------------- actions ----------------

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val c = ChatStore.get(currentKey) ?: return
        val action = c.replyAction
        if (action == null) {
            Toast.makeText(ctx.applicationContext, "Can't reply yet. Wait for their next message, or open the app.", Toast.LENGTH_LONG).show()
            return
        }
        // 1) Show my message immediately, 2) send it through Instagram/WhatsApp's own reply action.
        val m = ChatStore.addOutgoing(c, text)
        input.setText("")
        val ok = ReplySender.send(ctx.applicationContext, action, text)
        m.status = if (ok) ChatMessage.STATUS_OK else ChatMessage.STATUS_FAILED
        if (!ok) {
            c.replyAction = null
            Toast.makeText(ctx.applicationContext, "Not sent. Open the app to reply.", Toast.LENGTH_LONG).show()
        }
        ChatStore.notifyChanged()
    }

    private fun openInApp() {
        val c = ChatStore.get(currentKey) ?: return
        val appCtx = ctx.applicationContext
        onCollapse()
        val ok = if (c.simulated) ReplySender.launchApp(appCtx, c.pkg)
        else ReplySender.open(appCtx, c.contentIntent, c.pkg)
        if (!ok) {
            val msg = if (c.simulated) "This is a test chat (${AppIcons.label(appCtx, c.pkg)} isn't installed)"
            else "Couldn't open ${AppIcons.label(appCtx, c.pkg)}"
            Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = ctx.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) {
        }
    }
}
