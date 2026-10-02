package com.carriez.flutter_hbb

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log

object NksManagedService {
    const val ACTION_ENSURE_SERVICE = "com.nkspos.uem.ENSURE_REMOTE_SERVICE"
    private var lastStartAt = 0L

    private fun restrictions(context: Context): Bundle? = runCatching {
        (context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager).applicationRestrictions
    }.getOrNull()

    fun isManaged(context: Context): Boolean {
        val policy = restrictions(context) ?: return false
        return policy.getBoolean("serviceEnabled") &&
            policy.getString("reportTo") == "com.nkspos.uem" &&
            !policy.getString("nonce").isNullOrBlank()
    }

    fun acceptsWake(context: Context, nonce: String?): Boolean =
        isManaged(context) && !nonce.isNullOrBlank() && nonce == restrictions(context)?.getString("nonce")

    fun ensureRunning(context: Context) {
        if (!isManaged(context)) return
        val app = context.applicationContext
        app.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_START_ON_BOOT_OPT, true).apply()
        Handler(Looper.getMainLooper()).post {
            if (MainService.isReady) return@post
            val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val captureAllowed = runCatching {
                appOps.checkOpNoThrow("android:project_media", Process.myUid(), app.packageName) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
            if (!captureAllowed) {
                Log.w("NksManagedService", "Screen capture needs factory provisioning or Android approval")
                return@post
            }
            val now = SystemClock.elapsedRealtime()
            if (lastStartAt != 0L && now - lastStartAt < 30_000L) return@post
            lastStartAt = now
            runCatching {
                app.startForegroundService(Intent(app, MainService::class.java).apply {
                    action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
                    putExtra(EXT_INIT_FROM_BOOT, true)
                })
            }.onFailure {
                lastStartAt = 0L
                Log.w("NksManagedService", "Could not restore remote service: ${it.message}")
            }
        }
    }
}
