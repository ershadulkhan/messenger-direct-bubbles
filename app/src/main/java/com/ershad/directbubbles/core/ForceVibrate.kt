package com.ershad.directbubbles.core

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * "Force vibrate in Vibrate mode" (OriginOS fix).
 *
 * On vivo OriginOS 7, Vibrate mode sets the Notifications volume to 0 and greys it out, and the
 * notification vibrations of WhatsApp/Instagram are suppressed with it. The Alarm stream stays on.
 * So, when a chat app posts a new message or an incoming call, Direct Bubbles vibrates the motor itself
 * with ALARM vibration attributes. Event-driven only (called from the notification listener), no polling,
 * no wake lock: the system vibrator service keeps running the pattern even with the screen off.
 *
 * Never vibrates in Silent mode or while Do Not Disturb is on.
 */
object ForceVibrate {
    private const val TAG = "ForceVibrate"
    private const val COOLDOWN_MS = 1_500L
    /** A ringing call stops buzzing after this long even if the call notification stays. */
    private const val MAX_RING_MS = 45_000L

    private val MESSAGE_TIMINGS = longArrayOf(0, 250, 100, 250)
    private val MESSAGE_AMPS = intArrayOf(0, 255, 0, 255)
    /** Repeats from index 1: 500 on, 300 off, 500 on, 700 off... */
    private val CALL_TIMINGS = longArrayOf(0, 500, 300, 500, 700)
    private val CALL_AMPS = intArrayOf(0, 255, 0, 255, 0)

    private val lastAt = HashMap<String, Long>()
    private var ringingKey: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private val stopRing = Runnable { stopCall() }

    /** Human-readable ringer state, shown next to the Test button. */
    fun modeLabel(c: Context): String {
        val am = c.getSystemService(AudioManager::class.java) ?: return "unknown"
        val mode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent mode"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibration mode"
            else -> "Ring mode"
        }
        return if (dndOn(c)) "$mode · Do Not Disturb on" else mode
    }

    private fun dndOn(c: Context): Boolean {
        val f = c.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: return false
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** True when the current phone state allows a forced vibration. */
    fun allowed(c: Context): Boolean {
        if (!Prefs.forceVibrate(c)) return false
        val am = c.getSystemService(AudioManager::class.java) ?: return false
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> return false
            AudioManager.RINGER_MODE_VIBRATE -> {}
            else -> if (!Prefs.forceVibrateInRing(c)) return false
        }
        return !dndOn(c)
    }

    /** A new chat message arrived from [pkg]. One short double pulse; bursts within 1.5 s buzz once. */
    fun message(c: Context, pkg: String) {
        if (!allowed(c)) return
        if (ringingKey != null) return   // a call is already buzzing
        val now = SystemClock.elapsedRealtime()
        if (now - (lastAt[pkg] ?: 0L) < COOLDOWN_MS) return
        lastAt[pkg] = now
        vibrate(c, waveform(c, MESSAGE_TIMINGS, MESSAGE_AMPS, -1))
    }

    /** An incoming (ringing) call notification [key] was posted: buzz like a ringtone until it ends. */
    fun callStart(c: Context, key: String) {
        if (!Prefs.callVibrate(c) || !allowed(c)) return
        if (ringingKey == key) return
        ringingKey = key
        appCtx = c.applicationContext
        vibrate(c, waveform(c, CALL_TIMINGS, CALL_AMPS, 1))
        main.removeCallbacks(stopRing)
        main.postDelayed(stopRing, MAX_RING_MS)
    }

    /** The call notification [key] was answered, declined, missed or removed. */
    fun callEnd(key: String) {
        if (ringingKey == key) stopCall()
    }

    private fun stopCall() {
        main.removeCallbacks(stopRing)
        ringingKey = null
        appCtx?.let { vibrator(it)?.cancel() }
    }

    /** Test button: always vibrates once (you asked for it), whatever the mode. */
    fun test(c: Context) {
        vibrate(c, waveform(c, MESSAGE_TIMINGS, MESSAGE_AMPS, -1))
    }

    /**
     * Incoming call = CallStyle "incoming" (android.callType 1), or a call notification with a
     * full-screen intent that is not CallStyle "ongoing" (2).
     */
    fun isRingingCall(n: Notification): Boolean {
        if (n.category != Notification.CATEGORY_CALL) return false
        val type = n.extras?.getInt("android.callType", 0) ?: 0
        return when (type) {
            1 -> true
            0 -> n.fullScreenIntent != null
            else -> false
        }
    }

    private fun vibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            c.getSystemService(Vibrator::class.java)
        }

    private fun waveform(c: Context, t: LongArray, a: IntArray, repeat: Int): VibrationEffect {
        val v = vibrator(c)
        return if (v != null && v.hasAmplitudeControl()) VibrationEffect.createWaveform(t, a, repeat)
        else VibrationEffect.createWaveform(t, repeat)
    }

    private fun vibrate(c: Context, effect: VibrationEffect) {
        val v = vibrator(c) ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Alarm usage: the Alarm stream stays enabled in Vibrate mode on OriginOS.
                val attrs = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
                v.vibrate(effect, attrs)
            } else {
                val audio = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                v.vibrate(effect, audio)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }
}
