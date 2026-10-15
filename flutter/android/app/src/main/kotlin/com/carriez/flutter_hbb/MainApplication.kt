package com.carriez.flutter_hbb

import android.app.Application
import android.util.Log
import ffi.FFI

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"
    }

    override fun onCreate() {
        super.onCreate()
        NksDiagnostics.installCrashRecorder(this)
        Log.d(TAG, "App start")
        FFI.onAppStart(applicationContext)
    }
}
