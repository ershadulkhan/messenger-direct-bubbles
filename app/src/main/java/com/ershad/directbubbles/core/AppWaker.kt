package com.ershad.directbubbles.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * Wakes a frozen chat app WITHOUT opening it on screen, so a held reply is delivered at once.
 *
 * Measured on a vivo X200 FE (OriginOS 7 / Android 17): Instagram's Reply button is a broadcast, and
 * while Instagram is frozen the phone keeps it as "DEFERRED ... mBroadcastConsumerDeferForFrozen".
 * A single query to one of Instagram's public (exported) content providers made the system send
 * vivo.intent.action.PACKAGE_UNFREEZE for Instagram, and the held reply was delivered within 7 ms.
 *
 * The query reads nothing: it uses a made-up path and the result is ignored. It runs on a background
 * thread so a slow or frozen app never blocks the bubble.
 */
object AppWaker {
    private const val TAG = "AppWaker"
    private const val MIN_GAP_MS = 700L

    /** Known public providers that answer without any permission. */
    private val KNOWN = mapOf(
        "com.instagram.android" to "com.instagram.contentprovider.InstallReferrerProvider",
    )

    private val io = Executors.newSingleThreadExecutor()
    private val authorities = HashMap<String, String>()
    private val noProvider = HashSet<String>()
    private val lastWake = HashMap<String, Long>()

    /** Never needed for these (their reply button starts a service, which wakes them already). */
    private val SKIP = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun wake(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName || pkg in SKIP) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lastWake) {
            if (now - (lastWake[pkg] ?: 0L) < MIN_GAP_MS) return
            lastWake[pkg] = now
        }
        val app = ctx.applicationContext
        io.execute {
            val auth = authorityFor(app, pkg) ?: return@execute
            try {
                app.contentResolver.query(Uri.parse("content://$auth/wake"), null, null, null, null)?.close()
            } catch (t: Throwable) {
                // Expected for providers that reject unknown paths: the call itself already woke the app.
                Log.d(TAG, "wake $pkg via $auth: ${t.javaClass.simpleName}")
            }
        }
    }

    /** Known provider first, otherwise the first exported provider of [pkg] that needs no permission. */
    private fun authorityFor(c: Context, pkg: String): String? {
        synchronized(authorities) {
            authorities[pkg]?.let { return it }
            if (pkg in noProvider) return null
        }
        var found: String? = KNOWN[pkg]
        if (found == null) {
            try {
                val pm = c.packageManager
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS)
                }
                found = info.providers?.firstOrNull {
                    it.exported && it.enabled && it.readPermission == null && it.writePermission == null && !it.authority.isNullOrEmpty()
                }?.authority?.split(';')?.firstOrNull()
            } catch (t: Throwable) {
                Log.d(TAG, "no providers for $pkg", t)
            }
        }
        synchronized(authorities) {
            if (found == null) noProvider.add(pkg) else authorities[pkg] = found
        }
        return found
    }
}
