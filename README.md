# Direct Bubbles 💬

Messenger-style **floating chat heads** and a **popup inbox** for Instagram and WhatsApp DMs, on Android 10 through Android 16+ (Android 17/18 ready).

Read and reply to messages from anywhere, without opening the app.

## ⬇️ Download (on your phone)

**[Download the latest DirectBubbles.apk](https://github.com/ershadulkhan/messenger-direct-bubbles/releases/latest/download/DirectBubbles.apk)**

Or open the [Releases page](https://github.com/ershadulkhan/messenger-direct-bubbles/releases/latest) and tap **DirectBubbles.apk**.
If Android asks, allow your browser to *install unknown apps*. Each new build installs over the previous one and keeps your chats and settings.

## Setup (about 1 minute)

1. **Notification access**: tap *Grant access* and turn on **Direct Bubbles**.
   - Switch greyed out (Android 13+ sideloaded apps)? Tap **App info → ⋮ (top-right) → Allow restricted settings**, then try again.
2. **Display over other apps**: tap *Allow*.
3. *(Android 13+, optional)* **Notifications**: allows a silent panel entry that reopens your chats.
4. Tap **Test Messenger Bubbles & Inbox**. Three test people message you one after another. Tap the bubble, browse the inbox, open a chat, and reply. The test person answers back.

## What it does

| | |
|---|---|
| 🫧 **Chat head** | Circular profile picture, small Instagram/WhatsApp badge, unread count. Drag it anywhere; it snaps to the screen edge. Drag it onto the **X** at the bottom to dismiss. |
| 📥 **Separate inboxes** | An **Instagram inbox** and a **WhatsApp inbox** (apps never mix), each listing that app's chats one below another, newest at the top. The row of bubbles at the top switches between the app inboxes and each person. |
| 💬 **Chat thread** | One person's messages, one below another. Theirs on the left, yours on the right in Messenger-blue bubbles. History is saved on the phone. |
| ↩️ **Direct reply** | Type and press send. The reply goes out through Instagram/WhatsApp's own notification *Reply* action (`RemoteInput`). The reply action is cached, so you can send several replies even after the notification is cleared. |
| ↗️ **Open in app** | Tap the name or ↗ to jump straight to that chat in Instagram/WhatsApp. |
| 🔕 **No notifications** | Direct Bubbles never shows a notification of its own — the bubble holds everything. Optional per-app **Bubble only** also clears Instagram/WhatsApp's own notification from the panel. |
| 👆 **Tap to toggle** | Tap the floating bubble to open; tap the selected bubble again, the empty space above it, or Back to minimize. |
| ⚙️ **Any app** | Instagram and WhatsApp (plus WhatsApp Business) are on by default. *Choose apps* adds Messenger, Telegram, Signal, SMS and others. |

### Per-app settings (Main screen → app → Settings ›)
- **Auto Mark as Read**: when you open a chat in the popup, the app's own *Mark as read* button is pressed for you (the sender sees it as read). Off by default.
- **Themes**: presets (Messenger AMOLED, Messenger Light, WhatsApp Light/Dark, AMOLED Dark, Instagram Dark), every colour (title bar, chat background, incoming/outgoing bubbles and text, send button, input field and text), gradient bubbles, bubble style (rounded / classic with tail / square) and input style (pill / pill + round send / flat bar). Live preview, plus **Test** to see it in a real chat head.
- **Filter Chats**: hide chat heads for chat titles that contain, start with or end with keywords (comma-separated).

Dark mode uses true **AMOLED black** by default.

**Closing one chat head:** in the popup, drag a person's head (top row) down onto the **X**. Dragging the floating head onto the X closes all of them.

Tip: long-press a chat in the inbox to remove it from this app (it is not deleted from Instagram/WhatsApp).

## Privacy & battery

- **Permissions used:** notification access and display over other apps (plus optional notifications, only for the Test button). That's all.
- **No** internet permission, accessibility service, Shizuku/root, location, background polling or wake locks.
- **0% CPU while idle.** Android wakes the app only when a notification arrives. Overlay windows exist only while a bubble is on screen.
- Messages never leave the phone. They are stored in the app's private storage (a small JSON file).
- Reading through bubbles doesn't open Instagram/WhatsApp, so it doesn't mark messages as seen.

## Good to know

- Replies work for messages that arrive with a **Reply** button in the notification. That covers Instagram and WhatsApp DMs and most chat apps.
- If you clear a chat's notification and later restart the phone, replies for that chat unlock again with the person's next message (Android doesn't let reply actions survive a reboot).
- **Vivo / OriginOS, Xiaomi, Oppo:** if bubbles stop after a while, go to *App info → Battery* and allow background activity / autostart.

## Build it yourself

Every push builds automatically with GitHub Actions (`.github/workflows/build-apk.yml`, JDK 17 + Gradle). The APK is uploaded as a workflow artifact and published as a GitHub Release.

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

The project uses only the Android framework (no AndroidX, no third-party libraries). The release APK is a few hundred KB.

The signing key in `keystore/` is intentionally public, so every build can update the previous one. This app is for personal sideloading, not for the Play Store.

---
Made by **Md Ershadul Haque Khan**. Inspired by DirectChat; all code is original.
