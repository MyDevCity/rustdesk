package android.content

import android.app.AppOpsManager
import android.os.Bundle

class RestrictionsManager(val applicationRestrictions: Bundle)

class Preferences {
    val flags = mutableMapOf<String, Boolean>()
    fun edit() = this
    fun putBoolean(key: String, value: Boolean) = apply { flags[key] = value }
    fun apply() {}
}

class Intent(context: Context, target: Class<*>) {
    var action: String? = null
    val flags = mutableMapOf<String, Boolean>()
    fun putExtra(key: String, value: Boolean) = apply { flags[key] = value }
}

class Context {
    val policy = Bundle()
    val appOps = AppOpsManager()
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
