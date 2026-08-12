package com.carriez.flutter_hbb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.os.Bundle
import android.util.Log
import ffi.FFI
import kotlin.concurrent.thread

/**
 * Lets a managing agent rotate this device's unattended password without a human
 * at the screen.
 *
 * A store terminal is unattended by definition, and the platform hands an
 * operator a single-use password for each support session — so the password has
 * to change after every session. The desktop client does that with
 * `rustdesk --password`; Android has no CLI, so the values arrive as Android
 * **managed configuration** instead.
 *
 * Managed configuration is the reason there is no authentication code here:
 * only a Device Owner or Profile Owner can set an app's restrictions, and the
 * OS enforces that. Anything reaching this class was written by the enrolled
 * management agent or it did not arrive at all.
 *
 * Keys (all optional; absent means "leave alone"):
 * - `password`  — the new unattended password
 * - `reportTo`  — package of the managing agent, to send the outcome back to
 */
object NksManagedConfig {

    private const val TAG = "NksManagedConfig"
    private const val PREFS = "nks_managed_config"
    private const val KEY_APPLIED = "appliedPassword"

    const val KEY_PASSWORD = "password"
    const val KEY_REPORT_TO = "reportTo"

    /** Broadcast sent back to the managing agent once a rotation settles. */
    const val ACTION_STATE = "com.nkspos.uem.RUSTDESK_STATE"

    /**
     * Applies whatever the agent has pushed. Safe to call repeatedly: a password
     * that is already live is skipped, because the restrictions-changed
     * broadcast fires for any key and re-setting would churn the config.
     */
    fun apply(context: Context): Boolean {
        val restrictions = restrictionsOf(context) ?: return false
        val password = restrictions.getString(KEY_PASSWORD).orEmpty()
        if (password.isBlank()) return false

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_APPLIED, "") == password) return true

        // An empty id means the native config is not initialised yet, and
        // setting a password now would write it somewhere the server never
        // reads. Report nothing and let the retry in applyWhenReady catch it.
        if (FFI.getId().isEmpty()) {
            Log.i(TAG, "native config not ready; deferring password rotation")
            return false
        }

        val ok = FFI.setPermanentPassword(password)
        if (ok) {
            prefs.edit().putString(KEY_APPLIED, password).apply()
        }
        Log.i(TAG, "password rotation applied=$ok")
        report(context, restrictions.getString(KEY_REPORT_TO).orEmpty(), ok)
        return ok
    }

    /**
     * Applies once the native side is up.
     *
     * The receiver can fire while the process is cold, and `configureFlutterEngine`
     * runs before Dart calls `startServer`, so there is no callback to hang this
     * off that is guaranteed to be late enough.
     *
     * ponytail: bounded poll, ~15s. Replace with a startServer completion hook if
     * a device is ever seen taking longer than that to initialise.
     */
    fun applyWhenReady(context: Context) {
        val app = context.applicationContext
        thread(isDaemon = true) {
            repeat(30) {
                if (apply(app)) return@thread
                Thread.sleep(500)
            }
        }
    }

    private fun restrictionsOf(context: Context): Bundle? =
        runCatching {
            (context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager)
                .applicationRestrictions
        }.getOrNull()

    /**
     * Tells the agent the outcome and this device's id, so it can report the
     * pair to the platform.
     *
     * Explicit intent at a package the Device Owner named, rather than an
     * exported receiver or provider: nothing else on the device can see it, and
     * the fork gains no new attack surface.
     */
    private fun report(context: Context, reportTo: String, applied: Boolean) {
        if (reportTo.isBlank()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_STATE).apply {
                    setPackage(reportTo)
                    putExtra("id", FFI.getId())
                    putExtra("applied", applied)
                },
            )
        }.onFailure { Log.w(TAG, "could not report to $reportTo: ${it.message}") }
    }
}

/** Wakes the app when the agent pushes new managed configuration. */
class NksManagedConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) return
        NksManagedConfig.applyWhenReady(context)
    }
}
