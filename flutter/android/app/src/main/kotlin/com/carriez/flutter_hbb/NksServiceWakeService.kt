package com.carriez.flutter_hbb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class NksServiceWakeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != NksManagedService.ACTION_ENSURE_SERVICE ||
            !NksManagedService.acceptsWake(this, intent.getStringExtra("nonce"))) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (MainService.isReady) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("nks-recovery", "Remote support recovery", NotificationManager.IMPORTANCE_LOW))
        startForeground(1001, Notification.Builder(this, "nks-recovery")
            .setContentTitle("RustDesk")
            .setContentText("Checking remote support service")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build())
        NksManagedService.ensureRunning(this)
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf(startId) }, 5000L)
        return START_NOT_STICKY
    }
}
