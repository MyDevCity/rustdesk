package android.app

class AppOpsManager {
    var mode = 1
    fun checkOpNoThrow(operation: String, uid: Int, packageName: String): Int {
        check(operation == "android:project_media")
        return mode
    }
    companion object { const val MODE_ALLOWED = 0 }
}
