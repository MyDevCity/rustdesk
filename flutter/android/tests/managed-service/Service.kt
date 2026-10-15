package android.app

import android.content.Context
import android.content.Intent
import android.os.IBinder

class Notification {
    class Builder(context: Context, channel: String) {
        fun setContentTitle(text: String) = this
        fun setContentText(text: String) = this
        fun setSmallIcon(icon: Int) = this
        fun build() = Notification()
    }
}
class NotificationChannel(id: String, name: String, importance: Int)
class NotificationManager {
    val channels = mutableListOf<NotificationChannel>()
    fun createNotificationChannel(channel: NotificationChannel) { channels += channel }
    companion object { const val IMPORTANCE_LOW = 2 }
}

abstract class Service : Context() {
    val events = mutableListOf<String>()
    abstract fun onBind(intent: Intent?): IBinder?
    abstract fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    fun startForeground(id: Int, notification: Notification) { events += "foreground" }
    fun stopSelf(startId: Int) { events += "stop $startId" }
    companion object { const val START_NOT_STICKY = 2 }
}
