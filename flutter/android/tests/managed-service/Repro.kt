package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.os.SystemClock

fun main() {
    val context = Context()
    NksManagedService.ensureRunning(context)
    check(context.starts.isEmpty()) { "Unmanaged clients must remain user-controlled" }
    context.policy.values["serviceEnabled"] = true
    context.policy.values["reportTo"] = "com.nkspos.uem"
    check(!NksManagedService.isManaged(context)) { "A manager without a nonce must not lock the client" }
    context.policy.values["nonce"] = "test-nonce"
    check(!NksManagedService.acceptsWake(context, "wrong-nonce"))
    check(!NksManagedService.acceptsWake(context, null))
    check(NksManagedService.acceptsWake(context, "test-nonce"))
    NksManagedService.ensureRunning(context)
    check(context.preferences.flags[KEY_START_ON_BOOT_OPT] == true)
    check(context.starts.isEmpty()) { "Missing capture consent must not trigger repeated permission prompts" }
    context.appOps.mode = 0
    NksManagedService.ensureRunning(context)
    check(context.starts.size == 1) { "A provisioned managed client must recover its stopped service" }
    check(context.starts.single().action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE)
    check(context.starts.single().flags[EXT_INIT_FROM_BOOT] == true)
    NksManagedService.ensureRunning(context)
    check(context.starts.size == 1) { "Concurrent wakes must not request duplicate projection tokens" }
    SystemClock.now += 31_000L
    MainService.isReady = true
    NksManagedService.ensureRunning(context)
    check(context.starts.size == 1) { "Healthy sharing must not be interrupted" }
    MainService.isReady = false
    NksManagedService.ensureRunning(context)
    check(context.starts.size == 2) { "A later disruption must be recovered" }
    SystemClock.now += 31_000L
    context.failStart = true
    NksManagedService.ensureRunning(context)
    context.failStart = false
    NksManagedService.ensureRunning(context)
    check(context.starts.size == 3) { "A failed Android start must remain retryable" }
    context.policy.values["serviceEnabled"] = false
    SystemClock.now += 31_000L
    NksManagedService.ensureRunning(context)
    check(context.starts.size == 3) { "Management revocation must stop recovery" }

    val wake = NksServiceWakeService()
    wake.policy.values += mapOf("serviceEnabled" to true, "reportTo" to "com.nkspos.uem", "nonce" to "test-nonce")
    wake.appOps.mode = 0
    fun wakeWith(intent: Intent?, startId: Int): List<String> {
        wake.events.clear()
        wake.onStartCommand(intent, 0, startId)
        return wake.events.toList()
    }
    fun ensure(nonce: String) = Intent(NksManagedService.ACTION_ENSURE_SERVICE).putExtra("nonce", nonce)
    check(wakeWith(ensure("wrong-nonce"), 1) == listOf("foreground", "stop 1")) { "A rejected wake must enter the foreground before it stops, or Android kills the app" }
    check(wakeWith(null, 2) == listOf("foreground", "stop 2")) { "A wake without an intent must enter the foreground before it stops" }
    MainService.isReady = true
    check(wakeWith(ensure("test-nonce"), 3) == listOf("foreground", "stop 3")) { "A wake while sharing must enter the foreground before it stops" }
    check(wake.preferences.flags.isEmpty() && wake.starts.isEmpty()) { "A wake while sharing must leave the running service alone" }
    MainService.isReady = false
    SystemClock.now += 31_000L
    check(wakeWith(ensure("test-nonce"), 4) == listOf("foreground", "stop 4")) { "A recovery wake must enter the foreground" }
    check(wake.starts.size == 1) { "A recovery wake must restart the stopped service" }
    wake.foregroundDenied = true
    check(wakeWith(ensure("wrong-nonce"), 5) == listOf("stop 5")) { "A refused foreground start must not crash a rejected wake" }
    SystemClock.now += 31_000L
    check(wakeWith(ensure("test-nonce"), 6) == listOf("stop 6")) { "A refused foreground start must not crash a recovery wake" }
    check(wake.starts.size == 2) { "A recovery wake must restart the service even when the foreground start is refused" }
    println("PASS: stopped-service recovery, consent, authentication, startup, retry, revocation, and foreground wakes")
}
