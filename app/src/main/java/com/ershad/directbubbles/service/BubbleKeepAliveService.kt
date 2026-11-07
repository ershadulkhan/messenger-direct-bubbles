package com.ershad.directbubbles.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ershad.directbubbles.core.Notifications
import com.ershad.directbubbles.overlay.OverlayController

/**
 * Started only while a chat bubble/popup is on screen. It just holds the silent
 * quick-access notification so Android keeps the overlay alive. Stops when bubbles are dismissed.
 */
class BubbleKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OverlayController.init(this)
        if (intent?.action == ACTION_DISMISS) {
            OverlayController.dismissAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = Notifications.buildOngoing(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID_ONGOING, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "BubbleKeepAlive"
        const val ACTION_DISMISS = "com.ershad.directbubbles.DISMISS"

        @Volatile
        var running = false

        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, BubbleKeepAliveService::class.java))
            } catch (e: Exception) {
                // Android refused a background start: show the same entry as a plain silent notification.
                Log.w(TAG, "foreground start not allowed, posting plain notification", e)
                try {
                    c.getSystemService(NotificationManager::class.java)
                        .notify(Notifications.ID_ONGOING, Notifications.buildOngoing(c))
                } catch (_: Exception) {
                }
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, BubbleKeepAliveService::class.java))
            } catch (_: Exception) {
            }
            Notifications.cancel(c)
        }
    }
}
