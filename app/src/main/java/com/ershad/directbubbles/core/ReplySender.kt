package com.ershad.directbubbles.core

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

object ReplySender {
    private const val TAG = "ReplySender"

    /**
     * Sends [text] through the app's own notification Reply action (RemoteInput) — exactly what
     * tapping "Reply" in the notification shade does, so it works with WhatsApp/Instagram closed,
     * app-locked, or the phone locked.
     *
     * Why replies failed before (Android 14+): the reply was delivered as a *background* broadcast.
     * Android 14's new broadcast queue defers background broadcasts to cached/frozen apps, so a
     * frozen WhatsApp/Instagram only received the reply much later (or never). FLAG_RECEIVER_FOREGROUND
     * delivers it immediately, like the system notification shade does.
     */
    fun send(ctx: Context, action: Notification.Action, text: String, foreground: Boolean = true): Boolean {
        val inputs = action.remoteInputs ?: return false
        if (inputs.isEmpty()) return false
        val pi = action.actionIntent ?: return false
        return try {
            val intent = Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or (if (foreground) Intent.FLAG_RECEIVER_FOREGROUND else 0))
            val bundle = Bundle()
            for (remoteInput in inputs) {
                bundle.putCharSequence(remoteInput.resultKey, text)
            }
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            pi.send(ctx, 0, intent, null, null, null, senderOptions())
            true
        } catch (e: Exception) {
            Log.w(TAG, "reply failed", e)
            false
        }
    }

    /** Android 14+: let the target app use our (visible overlay's) privileges, as DirectChat does. */
    @Suppress("DEPRECATION")
    private fun senderOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < 34) return null
        val o = ActivityOptions.makeBasic()
        o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        return o.toBundle()
    }

    /** Presses a simple notification button (e.g. "Mark as read"). */
    fun fire(ctx: Context, action: Notification.Action): Boolean = try {
        action.actionIntent.send(ctx, 0, Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null, null, null, senderOptions())
        true
    } catch (e: Exception) {
        Log.w(TAG, "action failed", e)
        false
    }

    @Suppress("DEPRECATION")
    /** Opens the exact chat in Instagram/WhatsApp via the original notification's tap action. */
    fun open(ctx: Context, pi: PendingIntent?, pkg: String): Boolean {
        if (pi != null) {
            try {
                val opts: Bundle? = if (Build.VERSION.SDK_INT >= 34) {
                    val o = ActivityOptions.makeBasic()
                    o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    o.toBundle()
                } else null
                pi.send(ctx, 0, null, null, null, null, opts)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "contentIntent failed, falling back to launcher", e)
            }
        }
        return launchApp(ctx, pkg)
    }

    fun launchApp(ctx: Context, pkg: String): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            true
        } catch (e: Exception) {
            false
        }
    }
}
