package android.os

class Bundle {
    val values = mutableMapOf<String, Any>()
    fun getBoolean(key: String) = values[key] == true
    fun getString(key: String) = values[key] as? String
}
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) { fun post(action: () -> Unit) { action() } }
object Process { fun myUid() = 10160 }
object SystemClock {
    var now = 100_000L
    fun elapsedRealtime() = now
}
