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
import android.widget.HorizontalScrollView
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
import com.ershad.directbubbles.service.BubbleNotificationListener
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
    private val headsScroll: HorizontalScrollView = root.findViewById(R.id.heads_scroll)
    private val headsRow: LinearLayout = root.findViewById(R.id.heads_row)
    private val topArrow: ImageView = root.findViewById(R.id.chat_top_arrow)
    private val inboxTitle: TextView = root.findViewById(R.id.inbox_title)

    private val inboxContainer: View = root.findViewById(R.id.inbox_container)
    private val inboxList: ListView = root.findViewById(R.id.inbox_list)
    private val inboxEmpty: TextView = root.findViewById(R.id.inbox_empty)
    private val inboxTabs: View = root.findViewById(R.id.inbox_tabs)
    private val inboxSearch: View = root.findViewById(R.id.inbox_search)
    private val searchBox: EditText = root.findViewById(R.id.search_box)
    private val searchIcon: ImageView = root.findViewById(R.id.search_icon)
    private val tabChats: TextView = root.findViewById(R.id.tab_chats)
    private val tabActivity: TextView = root.findViewById(R.id.tab_activity)
    private val tabClear: TextView = root.findViewById(R.id.tab_clear)

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
    private val activityAdapter = ActivityAdapter(ctx)
    /** Instagram inbox: false = Chats tab, true = Activity tab. */
    private var showingActivity = false
    private val threadAdapter = ThreadAdapter(ctx)
    /** Open person's chat, or null when an app inbox is showing. */
    private var currentKey: String? = null
    /** Which app's inbox is showing (Instagram, WhatsApp...). Apps never mix. */
    private var currentInboxPkg: String? = null
    private var selectedHead: View? = null
    private var added = false
    private val density = ctx.resources.displayMetrics.density

    var attached = false
        private set

    init {
        inboxList.adapter = inboxAdapter
        threadList.adapter = threadAdapter
        threadList.setOnItemClickListener { _, _, position, _ ->
            val m = threadAdapter.getItem(position)
            if (ThreadAdapter.canRetry(m)) retry(m)
        }

        root.onBack = { onCollapse() }                     // Back minimizes to the floating bubble
        root.setOnClickListener { onCollapse() }           // tap on the transparent space
        panel.setOnClickListener { /* swallow taps inside the panel */ }
        panel.clipToOutline = true                         // rounded corners clip the coloured header
        headsScroll.setOnScrollChangeListener { _, _, _, _, _ -> positionArrow() }
        root.findViewById<View>(R.id.btn_close_inbox).setOnClickListener { onCollapse() }
        root.findViewById<View>(R.id.btn_camera).setOnClickListener {
            val pkg = currentInboxPkg ?: return@setOnClickListener
            onCollapse()
            ReplySender.launchApp(ctx.applicationContext, pkg)
        }
        root.findViewById<View>(R.id.btn_more).setOnClickListener {
            val pkg = currentInboxPkg ?: return@setOnClickListener
            onCollapse()
            try {
                ctx.startActivity(
                    android.content.Intent(ctx, com.ershad.directbubbles.settings.AppSettingsActivity::class.java)
                        .putExtra(com.ershad.directbubbles.settings.AppSettingsActivity.EXTRA_PKG, pkg)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (attached) refresh() }
        })
        btnBack.setOnClickListener { showInbox(ChatStore.get(currentKey)?.pkg) }
        btnClose.setOnClickListener { onCollapse() }
        btnOpen.setOnClickListener { openInApp() }
        root.findViewById<View>(R.id.thread_title_box).setOnClickListener { openInApp() }
        btnSend.setOnClickListener { send() }

        inboxList.setOnItemClickListener { _, _, position, _ ->
            if (showingActivity) openActivity(activityAdapter.getItem(position))
            else showThread(inboxAdapter.getItem(position).key)
        }
        tabChats.setOnClickListener { showingActivity = false; refresh() }
        tabActivity.setOnClickListener { showingActivity = true; refresh() }
        tabClear.setOnClickListener { currentInboxPkg?.let { ChatStore.clearActivity(it) } }
        inboxList.setOnItemLongClickListener { _, _, position, _ ->
            if (showingActivity) return@setOnItemLongClickListener false
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
    fun attach(key: String?, focusInput: Boolean = false) {
        root.animate().cancel()
        panel.animate().cancel()
        root.alpha = 1f
        panel.alpha = 1f
        panel.scaleX = 1f
        panel.scaleY = 1f
        headsScroll.alpha = 1f
        headsScroll.translationX = 0f
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
            headsScroll.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.heads_enter))
        }
        attached = true
        if (key != null && ChatStore.get(key) != null) {
            showThread(key)
            // Instant Reply: keyboard up, ready to type.
            if (focusInput) input.post { focusInput() }
        } else showInbox(null)
    }

    fun detach(onDone: () -> Unit) {
        if (!attached) {
            onDone()
            return
        }
        attached = false
        hideKeyboard()
        endDragUi()
        // Shrink back towards the chat heads (top-right), like Messenger.
        panel.pivotX = panel.width * 0.92f
        panel.pivotY = 0f
        headsScroll.animate().alpha(0f).translationX(headsScroll.width * 0.2f).setDuration(200).start()
        root.animate().alpha(0f).setDuration(220).start()
        panel.animate().scaleX(0.55f).scaleY(0.55f).alpha(0f).setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
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
            val pkg = currentInboxPkg?.takeIf { it !in hiddenPlatforms } ?: platforms().firstOrNull() ?: run {
                // Every app bubble is closed: show a person, or minimize.
                val person = ChatStore.activeOnes().firstOrNull()
                if (person != null) showThread(person.key) else onCollapse()
                return
            }
            currentInboxPkg = pkg
            val label = AppIcons.label(ctx, pkg)
            val q = searchBox.text.toString().trim().lowercase()
            val list = ChatStore.all().filter { it.pkg == pkg }   // only this app's chats
                .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.preview().lowercase().contains(q) }
            inboxTitle.text = label
            applyInboxTheme(pkg)
            val hasActivityTab = pkg in com.ershad.directbubbles.core.NotificationParser.ACTIVITY_APPS
            if (!hasActivityTab) showingActivity = false
            inboxTabs.visibility = if (hasActivityTab) View.VISIBLE else View.GONE
            val empty: Boolean
            if (showingActivity) {
                val acts = ChatStore.activityFor(pkg)
                if (inboxList.adapter !== activityAdapter) inboxList.adapter = activityAdapter
                activityAdapter.setData(acts)
                empty = acts.isEmpty()
                inboxEmpty.text = "No $label activity yet.\nNew instants, stories, follows and follow requests appear here."
                tabClear.visibility = if (empty) View.GONE else View.VISIBLE
                ChatStore.markActivityRead(pkg)
            } else {
                if (inboxList.adapter !== inboxAdapter) inboxList.adapter = inboxAdapter
                inboxAdapter.setData(list)
                empty = list.isEmpty()
                inboxEmpty.text = "No $label chats yet.\nNew $label messages will appear here."
                tabClear.visibility = View.GONE
            }
            if (hasActivityTab) {
                styleTab(tabChats, !showingActivity)
                val n = ChatStore.unreadActivity(pkg)
                tabActivity.text = if (n > 0 && !showingActivity) "Activity · $n" else "Activity"
                styleTab(tabActivity, showingActivity)
            }
            inboxEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            inboxList.visibility = if (empty) View.GONE else View.VISIBLE
            topArrow.imageTintList = android.content.res.ColorStateList.valueOf(Themes.forPkg(ctx, pkg).chatBg)
        } else {
            val c = ChatStore.get(key)
            if (c == null) {
                showInbox(null)
                return
            }
            currentInboxPkg = c.pkg
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
                c.markReadAction?.let { ReplySender.fire(ctx.applicationContext, it) }
                c.markReadAction = null
            }
            ChatStore.markRead(c)
        }
    }

    private var inboxTheme: com.ershad.directbubbles.core.ChatTheme? = null

    /** The app inbox page (Instagram / WhatsApp) follows the same per-app theme as its chats. */
    private fun applyInboxTheme(pkg: String) {
        val t = Themes.forInbox(ctx, pkg)
        inboxTheme = t
        inboxAdapter.theme = t
        activityAdapter.theme = t
        inboxContainer.background = GradientDrawable().apply { setColor(t.chatBg) }
        inboxList.setBackgroundColor(t.chatBg)
        val on = Themes.onColor(t.chatBg)
        val whatsapp = pkg.startsWith("com.whatsapp")
        // WhatsApp-style title: green accent on light/dark themes alike; other apps use plain text colour.
        inboxTitle.setTextColor(if (whatsapp) t.send else on)
        inboxEmpty.setTextColor(Themes.subtleOn(t.chatBg))
        val tint = android.content.res.ColorStateList.valueOf(on)
        for (id in intArrayOf(R.id.btn_close_inbox, R.id.btn_camera, R.id.btn_more)) root.findViewById<ImageView>(id).imageTintList = tint
        val pill = GradientDrawable()
        pill.cornerRadius = 23 * density
        val pillColor = if (t.inputBg != t.chatBg) t.inputBg else if (Themes.isDark(t.chatBg)) 0xFF1C1C1E.toInt() else 0xFFF5F4F2.toInt()
        pill.setColor(pillColor)
        inboxSearch.background = pill
        val onPill = Themes.onColor(pillColor)
        searchBox.setTextColor(onPill)
        searchBox.setHintTextColor(Themes.subtleOn(pillColor))
        searchBox.hint = if (whatsapp) "Ask Meta AI or Search" else "Search"
        searchIcon.imageTintList = android.content.res.ColorStateList.valueOf(Themes.subtleOn(pillColor))
        inboxSearch.visibility = if (showingActivity) View.GONE else View.VISIBLE
        inboxAdapter.notifyDataSetChanged()
        activityAdapter.notifyDataSetChanged()
    }

    /** Colors of the chat window come from the per-app theme (default: AMOLED black in dark mode). */
    private fun applyTheme(pkg: String) {
        val t = Themes.forPkg(ctx, pkg)
        threadAdapter.theme = t
        topArrow.imageTintList = android.content.res.ColorStateList.valueOf(t.titleBar)
        Themes.styleHeader(threadHeader, t, threadName, threadSub, listOf(btnBack, btnOpen, btnClose))
        threadDivider.setBackgroundColor(if (Themes.isDark(t.titleBar)) 0xFF1F1F1F.toInt() else 0x14000000)
        threadDivider.visibility = if (t.titleBar == t.chatBg) View.VISIBLE else View.GONE
        threadContainer.background = GradientDrawable().apply { setColor(t.chatBg) }
        threadList.setBackgroundColor(t.chatBg)
        replyHint.setTextColor(Themes.subtleOn(t.chatBg))
        Themes.styleInput(inputBar, input, btnSend, t)
    }

    /** App inboxes to offer: apps that have chats, plus Instagram & WhatsApp when enabled. */
    private fun platforms(): List<String> {
        val order = listOf("com.instagram.android", "com.whatsapp", "com.whatsapp.w4b")
        val enabled = Prefs.enabledApps(ctx)
        val set = LinkedHashSet<String>()
        for (p in order) if (p in enabled || ChatStore.all().any { it.pkg == p }) set.add(p)
        for (c in ChatStore.all()) set.add(c.pkg)
        if (set.isEmpty()) set.addAll(listOf("com.instagram.android", "com.whatsapp"))
        // App bubbles you dragged to the X stay closed until that app gets a new message.
        set.removeAll(hiddenPlatforms)
        return set.sortedBy { val i = order.indexOf(it); if (i < 0) 99 else i }
    }

    companion object {
        /** App inbox bubbles closed by dragging them to the X (kept while the app runs). */
        private val hiddenPlatforms = HashSet<String>()

        /** A new message from [pkg] brings its inbox bubble back. */
        fun unhidePlatform(pkg: String) {
            hiddenPlatforms.remove(pkg)
        }

        fun resetHiddenPlatforms() {
            hiddenPlatforms.clear()
        }

        /** How many times each message was re-sent; every retry starts from the next reply route. */
        private val attempts = HashMap<String, Int>()

        /** Replies waiting for their turn (no reply button yet, or the previous reply is still in flight). */
        private val queued = HashMap<String, MutableList<ChatMessage>>()
        private val lastSent = HashMap<String, ChatMessage>()
        private val lastSentAt = HashMap<String, Long>()
        private const val IN_FLIGHT_MS = 2_500L
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        private fun keyOf(c: com.ershad.directbubbles.core.Conversation, m: ChatMessage) = c.key + "\u0000" + m.text + "\u0000" + m.time

        fun attemptOf(c: com.ershad.directbubbles.core.Conversation, m: ChatMessage) = attempts[keyOf(c, m)] ?: 0

        /**
         * Instagram/WhatsApp handle one reply per notification at a time: a second reply sent while the
         * first is still being processed is dropped or delayed. So replies to one chat go out in order,
         * the next one as soon as the previous is confirmed (or after 5 seconds).
         */
        fun inFlight(c: com.ershad.directbubbles.core.Conversation): Boolean {
            val m = lastSent[c.key] ?: return false
            return m.status == ChatMessage.STATUS_OK && System.currentTimeMillis() - (lastSentAt[c.key] ?: 0L) < IN_FLIGHT_MS
        }

        fun markSent(c: com.ershad.directbubbles.core.Conversation, m: ChatMessage) {
            lastSent[c.key] = m
            lastSentAt[c.key] = System.currentTimeMillis()
        }

        /** @return null = sent, "none" = no reply button right now, "failed" = every button refused. */
        fun sendNow(ctx: android.content.Context, c: com.ershad.directbubbles.core.Conversation, m: ChatMessage, attempt: Int): String? {
            val appCtx = ctx.applicationContext
            BubbleNotificationListener.ensureConnected(appCtx)
            val fresh = BubbleNotificationListener.freshRepliesFor(c)
            val all = (fresh + listOfNotNull(c.replyAction)).distinctBy { it.actionIntent }
            if (all.isEmpty()) return "none"
            val r = attempt % all.size
            val candidates = all.drop(r) + all.take(r)
            attempts[keyOf(c, m)] = attempt
            for (a in candidates) {
                if (ReplySender.send(appCtx, a, m.text, (attempt / all.size) % 2 == 0)) {
                    m.sentAt = System.currentTimeMillis()
                    m.route = com.ershad.directbubbles.core.NotificationParser.routeOf(a)
                    com.ershad.directbubbles.core.ReplyLog.record(c.pkg, c.title, m, attempt)
                    c.replyAction = a
                    return null
                }
            }
            c.replyAction = null
            return "failed"
        }

        fun queue(ctx: android.content.Context, c: com.ershad.directbubbles.core.Conversation, m: ChatMessage) {
            queued.getOrPut(c.key) { ArrayList() }.add(m)
            schedulePump(ctx, c.key, if (inFlight(c)) IN_FLIGHT_MS + 100 else 1500)
        }

        private fun schedulePump(ctx: android.content.Context, key: String, delay: Long) {
            val appCtx = ctx.applicationContext
            handler.postDelayed({ flushQueued(appCtx, key) }, delay)
        }

        /** Sends this chat's waiting replies, one at a time. Called on new notifications and by a timer. */
        fun flushQueued(ctx: android.content.Context, key: String) {
            val list = queued[key] ?: return
            val c = ChatStore.get(key) ?: run { queued.remove(key); return }
            while (list.isNotEmpty()) {
                val m = list[0]
                if (m.status != ChatMessage.STATUS_SENDING) { list.removeAt(0); continue }
                if (inFlight(c)) { schedulePump(ctx, key, 1200); break }
                when (sendNow(ctx, c, m, 0)) {
                    null -> { m.status = ChatMessage.STATUS_OK; markSent(c, m); list.removeAt(0) }
                    "none" -> break   // wait for this chat's next notification
                    else -> { m.status = ChatMessage.STATUS_FAILED; list.removeAt(0) }
                }
            }
            if (list.isEmpty()) queued.remove(key)
            ChatStore.notifyChanged()
    }
    }

    /** Closes one app's inbox bubble (Instagram / WhatsApp); everything else stays. */
    private fun closePlatform(pkg: String) {
        hiddenPlatforms.add(pkg)
        if (currentKey == null && currentInboxPkg == pkg) {
            currentInboxPkg = null
            val next = platforms().firstOrNull()
            val person = ChatStore.activeOnes().firstOrNull()
            when {
                next != null -> showInbox(next)
                person != null -> showThread(person.key)
                else -> onCollapse()     // nothing left open
            }
        } else {
            refresh()
        }
    }

    private fun buildHeads() {
        headsRow.removeAllViews()
        selectedHead = null
        val inflater = LayoutInflater.from(ctx)
        // 1) One inbox bubble per app (Instagram inbox, WhatsApp inbox, ...)
        for (pkg in platforms()) {
            val v = inflater.inflate(R.layout.item_head, headsRow, false)
            v.findViewById<ImageView>(R.id.head_avatar).setImageBitmap(AppIcons.big(ctx, pkg))
            v.findViewById<View>(R.id.head_badge).visibility = View.GONE
            val unread = ChatStore.all().any { it.pkg == pkg && it.unread > 0 } || ChatStore.unreadActivity(pkg) > 0
            val pu = ChatStore.all().filter { it.pkg == pkg }.sumOf { it.unread }
            val dotP = v.findViewById<TextView>(R.id.head_dot)
            dotP.visibility = if (unread) View.VISIBLE else View.GONE
            dotP.text = if (pu > 99) "99+" else if (pu > 0) pu.toString() else ""
            val selected = currentKey == null && currentInboxPkg == pkg
            v.findViewById<View>(R.id.head_ring).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            if (selected) selectedHead = v
            v.contentDescription = "${AppIcons.label(ctx, pkg)} inbox. Drag down to close."
            v.setOnTouchListener(
                HeadTouch(
                    // Tapping the open inbox again minimizes; tapping another switches.
                    onTap = { if (currentKey == null && currentInboxPkg == pkg) onCollapse() else showInbox(pkg) },
                    onDrop = { closePlatform(pkg) },
                )
            )
            headsRow.addView(v)
        }
        // A small gap between app inboxes and people.
        val gap = View(ctx)
        headsRow.addView(gap, LinearLayout.LayoutParams((6 * density).toInt(), 1))
        // 2) One bubble per active person.
        for (c in ChatStore.activeOnes().take(15)) {
            val v = inflater.inflate(R.layout.item_head, headsRow, false)
            v.findViewById<ImageView>(R.id.head_avatar).setImageBitmap(Avatars.forConv(c))
            v.findViewById<ImageView>(R.id.head_badge).setImageBitmap(AppIcons.badge(ctx, c.pkg))
            val dotC = v.findViewById<TextView>(R.id.head_dot)
            dotC.visibility = if (c.unread > 0) View.VISIBLE else View.GONE
            dotC.text = if (c.unread > 99) "99+" else c.unread.toString()
            val selected = c.key == currentKey
            v.findViewById<View>(R.id.head_ring).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            if (selected) selectedHead = v
            v.contentDescription = "${c.title}. Tap again to minimize, drag down to close."
            v.setOnTouchListener(
                HeadTouch(
                    // Tapping the open person again minimizes; tapping another person switches.
                    onTap = { if (currentKey == c.key) onCollapse() else showThread(c.key) },
                    onDrop = { closeHead(c.key) },
                )
            )
            headsRow.addView(v)
        }
        headsRow.post {
            val sel = selectedHead
            if (sel != null) {
                // Keep the selected head visible.
                if (sel.left < headsScroll.scrollX || sel.right > headsScroll.scrollX + headsScroll.width) {
                    headsScroll.smoothScrollTo(sel.left, 0)
                }
            }
            positionArrow()
        }
    }

    /** Points the little triangle at the selected chat head. */
    private fun positionArrow() {
        val sel = selectedHead
        if (sel == null || !sel.isAttachedToWindow) {
            topArrow.visibility = View.INVISIBLE
            return
        }
        topArrow.visibility = View.VISIBLE
        val parent = topArrow.parent as View
        val selLoc = IntArray(2)
        sel.getLocationInWindow(selLoc)
        val parentLoc = IntArray(2)
        parent.getLocationInWindow(parentLoc)
        val center = selLoc[0] + sel.width / 2f - parentLoc[0]
        val lo = 30 * density
        val hi = max(lo, parent.width - 30 * density)
        topArrow.translationX = center.coerceIn(lo, hi) - topArrow.width / 2f
    }

    private fun showInbox(requested: String?) {
        hideKeyboard()
        // Never open the inbox of an app whose bubble you closed.
        val pkg = requested?.takeIf { it !in hiddenPlatforms }
        if (currentInboxPkg in hiddenPlatforms) currentInboxPkg = null
        val switching = currentKey != null || (pkg != null && pkg != currentInboxPkg)
        if (pkg != null && pkg != currentInboxPkg) { showingActivity = false; searchBox.setText("") }
        if (pkg != null) currentInboxPkg = pkg
        else if (currentInboxPkg == null) currentInboxPkg = platforms().firstOrNull()
        currentKey = null
        if (switching) inboxContainer.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.thread_enter))
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
        OverlayController.closeChat(key)
        // The app inbox bubbles stay, so the popup stays open on that app's inbox.
        if (currentKey == key) showInbox(ChatStore.get(key)?.pkg) else refresh()
    }

    // ---------------- drag a chat head to the X ----------------

    private inner class HeadTouch(
        private val onTap: () -> Unit,
        private val onDrop: () -> Unit,
    ) : View.OnTouchListener {
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
                    if (dragging) finishDrag(v, inTarget, onDrop)
                    else if (!moved) onTap()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (dragging) finishDrag(v, false, onDrop)
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

    private fun finishDrag(head: View, drop: Boolean, onDrop: () -> Unit) {
        if (drop) {
            ghost.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160).withEndAction {
                endDragUi()
                onDrop()
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
        for (i in 0 until headsRow.childCount) headsRow.getChildAt(i).alpha = 1f
    }

    // ---------------- actions ----------------

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val c = ChatStore.get(currentKey) ?: return
        input.setText("")
        // 1) Show my message immediately, 2) send it through Instagram/WhatsApp's own reply action.
        deliver(c, ChatStore.addOutgoing(c, text))
    }

    /**
     * Sends one reply. Uses the FRESHEST reply button first (from the notification currently in the
     * shade — Instagram replaces its notification, and the newest button is the one it expects),
     * then the cached one as a backup.
     */
    private fun deliver(c: com.ershad.directbubbles.core.Conversation, m: ChatMessage, attempt: Int = 0) {
        val appCtx = ctx.applicationContext
        if (inFlight(c) || queued[c.key]?.isNotEmpty() == true) {
            // Keep order: wait for the previous reply to be handled first.
            m.status = ChatMessage.STATUS_SENDING
            queue(appCtx, c, m)
            ChatStore.notifyChanged()
            return
        }
        when (sendNow(appCtx, c, m, attempt)) {
            null -> {
                m.status = ChatMessage.STATUS_OK
                markSent(c, m)
                root.postDelayed({ if (attached) refresh() }, ThreadAdapter.UNCONFIRMED_AFTER_MS + 500)
            }
            "none" -> {
                m.status = ChatMessage.STATUS_SENDING
                queue(appCtx, c, m)
                Toast.makeText(appCtx, "Waiting: it sends automatically with ${c.title}'s next message. Or tap ↗ to open the app.", Toast.LENGTH_LONG).show()
            }
            else -> {
                m.status = ChatMessage.STATUS_FAILED
                Toast.makeText(appCtx, "Not sent: the reply button expired. Tap the message to retry, or open the app.", Toast.LENGTH_LONG).show()
            }
        }
        ChatStore.notifyChanged()
    }

    /** Tap on my failed / unconfirmed message: send it again. */
    private fun retry(m: ChatMessage) {
        val c = ChatStore.get(currentKey) ?: return
        val next = attemptOf(c, m) + 1
        ChatStore.removeMessage(c, m)
        deliver(c, ChatStore.addOutgoing(c, m.text), next)
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = 17 * density
        val t = inboxTheme
        if (selected) {
            bg.setColor(t?.send ?: ctx.getColor(R.color.brand_blue))
            tab.setTextColor(if (t != null) Themes.onColor(t.send) else android.graphics.Color.WHITE)
        } else {
            bg.setColor(t?.inputBg ?: ctx.getColor(R.color.surface))
            tab.setTextColor(if (t != null) Themes.onColor(t.inputBg) else ctx.getColor(R.color.text_primary))
        }
        tab.background = bg
    }

    /** Activity item tapped: open it in the app (its own notification action), else launch the app. */
    private fun openActivity(a: com.ershad.directbubbles.core.ActivityItem) {
        val appCtx = ctx.applicationContext
        onCollapse()
        if (!ReplySender.open(appCtx, a.contentIntent, a.pkg)) {
            Toast.makeText(appCtx, "Couldn't open ${AppIcons.label(appCtx, a.pkg)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun focusInput() {
        input.requestFocus()
        try {
            ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {
        }
    }

    private fun openInApp() {
        val c = ChatStore.get(currentKey) ?: return
        val appCtx = ctx.applicationContext
        onCollapse()
        val ok = if (c.simulated) ReplySender.launchApp(appCtx, c.pkg)
        else ReplySender.open(appCtx, c.contentIntent, c.pkg)
        // Preferences → Close ChatHeads on app launch.
        if (ok && Prefs.closeOnAppLaunch(appCtx)) OverlayController.closeChat(c.key, clearNotifications = false)
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
