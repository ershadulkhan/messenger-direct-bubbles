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
| 📥 **Inbox** | Everyone's chats, one below another, newest at the top: avatar + app badge, name, latest message + time, unread count. A row of chat heads at the top switches between people in one tap. |
| 💬 **Chat thread** | One person's messages, one below another. Theirs on the left, yours on the right in Messenger-blue bubbles. History is saved on the phone. |
| ↩️ **Direct reply** | Type and press send. The reply goes out through Instagram/WhatsApp's own notification *Reply* action (`RemoteInput`). The reply action is cached, so you can send several replies even after the notification is cleared. |
| ↗️ **Open in app** | Tap the name or ↗ to jump straight to that chat in Instagram/WhatsApp. |
| 🔕 **Panel entry** | While a bubble is on screen, a silent notification lets you reopen the inbox or hide the bubbles. |
| ⚙️ **Any app** | Instagram and WhatsApp (plus WhatsApp Business) are on by default. *Choose apps* adds Messenger, Telegram, Signal, SMS and others. |

Tip: long-press a chat in the inbox to remove it from this app (it is not deleted from Instagram/WhatsApp).

## Privacy & battery

- **Permissions used:** notification access, display over other apps, foreground service (only while a bubble is visible), post notifications. That's all.
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
