package com.carriez.flutter_hbb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class NksServiceWakeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The agent uses startForegroundService(); Android kills the whole process
        // if any start, even a rejected one, returns without startForeground().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("nks-recovery", "Remote support recovery", NotificationManager.IMPORTANCE_LOW))
            runCatching {
                startForeground(1001, Notification.Builder(this, "nks-recovery")
                    .setContentTitle("RustDesk")
                    .setContentText("Checking remote support service")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build())
            }.onFailure { Log.w("NksServiceWakeService", "Android refused the foreground start", it) }
        }
        if (intent?.action != NksManagedService.ACTION_ENSURE_SERVICE ||
            !NksManagedService.acceptsWake(this, intent.getStringExtra("nonce")) ||
            MainService.isReady) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        NksManagedService.ensureRunning(this)
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf(startId) }, 5000L)
        return START_NOT_STICKY
    }
}
