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
 * Lets a managing agent point this device at our own relay and rotate its
 * unattended password, without a human at the screen.
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
 * - `server`    — rendezvous/relay host
 * - `key`       — that server's public key, to pin
 * - `reportTo`  — package of the managing agent, to send the outcome back to
 */
object NksManagedConfig {

    private const val TAG = "NksManagedConfig"
    private const val PREFS = "nks_managed_config"
    private const val KEY_APPLIED = "appliedPassword"

    const val KEY_PASSWORD = "password"
    const val KEY_REPORT_TO = "reportTo"
    const val KEY_SERVER = "server"
    const val KEY_SERVER_KEY = "key"

    /**
     * Echoed back on the reply. The agent's receiver has to be exported for this
     * app to reach it at all, so this value — which only a Device Owner could
     * have written into our restrictions — is how it tells us apart from any
     * other app that noticed the broadcast action.
     */
    const val KEY_NONCE = "nonce"

    // The option names RustDesk itself uses; the desktop agent writes the same
    // three into RustDesk2.toml.
    private const val OPT_RENDEZVOUS = "custom-rendezvous-server"
    private const val OPT_RELAY = "relay-server"
    private const val OPT_KEY = "key"

    /** Broadcast sent back to the managing agent. */
    const val ACTION_STATE = "com.nkspos.uem.RUSTDESK_STATE"

    /**
     * Applies whatever the agent has pushed, and always answers with this
     * device's id when it has somewhere to answer to.
     *
     * The id is reported even when there is nothing to apply. An agent cannot
     * report a device to the platform until it knows the id, and it will not
     * send a password until the platform knows the device — so a version that
     * only answered when handed a password deadlocked, with each side waiting
     * for the other. Found on an emulator; it would have been every terminal.
     */
    fun apply(context: Context): Boolean {
        val restrictions = restrictionsOf(context) ?: return false

        // An empty id means the native config is not initialised yet, so neither
        // applying nor reporting would mean anything. Let applyWhenReady retry.
        val id = FFI.getId()
        if (id.isEmpty()) return false

        applyServer(
            restrictions.getString(KEY_SERVER).orEmpty(),
            restrictions.getString(KEY_SERVER_KEY).orEmpty(),
        )

        val password = restrictions.getString(KEY_PASSWORD).orEmpty()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Skip a password that is already live: the restrictions-changed
        // broadcast fires for any key, and re-setting would churn the config.
        val rotated = when {
            password.isBlank() -> false
            prefs.getString(KEY_APPLIED, "") == password -> true
            else -> FFI.setPermanentPassword(password).also { ok ->
                if (ok) prefs.edit().putString(KEY_APPLIED, password).apply()
                Log.i(TAG, "password rotation applied=$ok")
            }
        }

        report(
            context,
            restrictions.getString(KEY_REPORT_TO).orEmpty(),
            restrictions.getString(KEY_NONCE).orEmpty(),
            id,
            rotated,
        )
        return true
    }

    /**
     * Points the client at our relay. Written through RustDesk's own option
     * setter, which restarts the rendezvous mediator when the server changes —
     * without that the device keeps talking to the previous server until it is
     * next launched.
     */
    private fun applyServer(server: String, serverKey: String) {
        if (server.isNotBlank()) {
            FFI.setOption(OPT_RENDEZVOUS, server)
            FFI.setOption(OPT_RELAY, server)
        }
        if (serverKey.isNotBlank()) FFI.setOption(OPT_KEY, serverKey)
    }

    /**
     * Applies the relay baked in at build time, but only if nothing has set one
     * yet.
     *
     * Without this a freshly installed client registers with RustDesk's public
     * servers until the agent's first push lands — a terminal briefly reachable
     * through infrastructure we do not run. The managed config always wins
     * afterwards, so the platform still owns server identity and a rebuilt
     * server is still self-healing.
     */
    fun applyBootstrap(context: Context) {
        val server = context.getString(R.string.nks_default_rendezvous_server)
        if (server.isBlank()) return
        if (FFI.getLocalOption(OPT_RENDEZVOUS).isNotBlank()) return
        applyServer(server, context.getString(R.string.nks_default_rendezvous_key))
        Log.i(TAG, "bootstrapped rendezvous server to $server")
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
                if (FFI.getId().isNotEmpty()) {
                    applyBootstrap(app)
                    apply(app)
                    return@thread
                }
                Thread.sleep(500)
            }
            Log.w(TAG, "native config never became ready; nothing applied")
        }
    }

    private fun restrictionsOf(context: Context): Bundle? =
        runCatching {
            (context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager)
                .applicationRestrictions
        }.getOrNull()

    /**
     * Tells the agent this device's id and whether a rotation landed, so it can
     * report the pair to the platform.
     *
     * Explicit intent at a package the Device Owner named, rather than an
     * exported receiver or provider: nothing else on the device can see it, and
     * the fork gains no new attack surface.
     */
    private fun report(
        context: Context,
        reportTo: String,
        nonce: String,
        id: String,
        applied: Boolean,
    ) {
        if (reportTo.isBlank()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_STATE).apply {
                    setPackage(reportTo)
                    putExtra("nonce", nonce)
                    putExtra("id", id)
                    putExtra("applied", applied)
                },
            )
            Log.i(TAG, "reported id=$id applied=$applied to $reportTo")
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
