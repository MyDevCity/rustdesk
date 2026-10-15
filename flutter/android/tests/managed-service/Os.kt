package android.os

class Bundle {
    val values = mutableMapOf<String, Any>()
    fun getBoolean(key: String) = values[key] == true
    fun getString(key: String) = values[key] as? String
}
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun post(action: () -> Unit) { action() }
    fun postDelayed(action: () -> Unit, delay: Long) { action() }
}
interface IBinder
object Build {
    object VERSION { const val SDK_INT = 33 }
    object VERSION_CODES { const val O = 26 }
}
object Process { fun myUid() = 10160 }
object SystemClock {
    var now = 100_000L
    fun elapsedRealtime() = now
}
