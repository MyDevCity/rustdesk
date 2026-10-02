package com.carriez.flutter_hbb

import android.content.Context
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
    println("PASS: stopped-service recovery, consent, authentication, startup, retry, and revocation")
}
