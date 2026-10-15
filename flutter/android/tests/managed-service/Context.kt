package android.content

import android.app.AppOpsManager
import android.app.NotificationManager
import android.os.Bundle

class RestrictionsManager(val applicationRestrictions: Bundle)

class Preferences {
    val flags = mutableMapOf<String, Boolean>()
    fun edit() = this
    fun putBoolean(key: String, value: Boolean) = apply { flags[key] = value }
    fun apply() {}
}

class Intent(var action: String?) {
    constructor(context: Context, target: Class<*>) : this(null)
    val flags = mutableMapOf<String, Boolean>()
    val strings = mutableMapOf<String, String>()
    fun putExtra(key: String, value: Boolean) = apply { flags[key] = value }
    fun putExtra(key: String, value: String) = apply { strings[key] = value }
    fun getStringExtra(key: String) = strings[key]
}

open class Context {
    val policy = Bundle()
    val appOps = AppOpsManager()
    val notifications = NotificationManager()
    val preferences = Preferences()
    val starts = mutableListOf<Intent>()
    var failStart = false
    val applicationContext get() = this
    val packageName = "com.carriez.flutter_hbb"
    fun getSystemService(name: String): Any = when (name) {
        RESTRICTIONS_SERVICE -> RestrictionsManager(policy)
        APP_OPS_SERVICE -> appOps
        else -> error(name)
    }
    @Suppress("UNCHECKED_CAST")
    fun <T> getSystemService(type: Class<T>): T = notifications as T
    fun getSharedPreferences(name: String, mode: Int) = preferences
    fun startForegroundService(intent: Intent) {
        check(!failStart) { "Background start denied" }
        starts += intent
    }
    companion object {
        const val RESTRICTIONS_SERVICE = "restrictions"
        const val APP_OPS_SERVICE = "appops"
        const val MODE_PRIVATE = 0
    }
}
