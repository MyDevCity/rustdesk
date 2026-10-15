package com.carriez.flutter_hbb

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

/**
 * Answers the managing agent's `collect_logs` command. Another app cannot read
 * this app's logcat, exit history, or private files, so RustDesk collects its
 * own and sends them back as one gzipped text report.
 */
object NksDiagnostics {
    private const val TAG = "NksDiagnostics"
    const val ACTION_COLLECT = "com.nkspos.uem.COLLECT_REMOTE_DIAGNOSTICS"
    const val ACTION_REPORT = "com.nkspos.uem.RUSTDESK_DIAGNOSTICS"
    private const val CRASH_DIR = "nks-crashes"
    private const val KEEP_CRASHES = 5
    // Binder transactions fail near 1 MB, shared with everything else in flight.
    private const val MAX_REPORT_BYTES = 400 * 1024

    private val appOpModes = arrayOf("allowed", "ignored", "errored", "default", "foreground")
    private val exitReasons = arrayOf(
        "UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR",
        "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED",
        "USER_STOPPED", "DEPENDENCY_DIED", "OTHER", "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED",
    )

    fun installCrashRecorder(context: Context) {
        val dir = File(context.filesDir, CRASH_DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { crashed, error ->
            runCatching {
                dir.mkdirs()
                val now = System.currentTimeMillis()
                File(dir, "crash-$now.txt").writeText(
                    "time=${stamp(now)}\nthread=${crashed.name}\n\n${error.stackTraceToString()}"
                )
                dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_CRASHES)?.forEach { it.delete() }
            }
            previous?.uncaughtException(crashed, error)
        }
    }

    fun send(context: Context, nonce: String, requestId: String) {
        val policy = NksManagedService.restrictions(context) ?: return
        val reportTo = policy.getString(NksManagedConfig.KEY_REPORT_TO).orEmpty()
        @Suppress("DEPRECATION")
        val secrets = policy.keySet().mapNotNull { policy.get(it) as? String }.filter { it.isNotBlank() }
        val report = report(context, secrets)
        context.sendBroadcast(Intent(ACTION_REPORT).apply {
            setPackage(reportTo)
            putExtra("nonce", nonce)
            putExtra("requestId", requestId)
            putExtra("report", report)
        })
        Log.i(TAG, "sent ${report.size} byte report to $reportTo")
    }

    private fun report(context: Context, secrets: List<String>): ByteArray {
        val head = section("App") { app(context) } +
            section("Service") { "MainService.isReady=${MainService.isReady}" } +
            section("Permissions") { permissions(context) } +
            section("Process exits") { exits(context) } +
            section("Recorded crashes") { crashes(context) } +
            section("RustDesk log") { rustLog(context) }
        var logcat = runCatching { logcat() }.getOrElse { listOf("unavailable: $it") }
        while (true) {
            var text = head + section("Logcat") { logcat.joinToString("\n") }
            for (secret in secrets) text = text.replace(secret, "[redacted]")
            val packed = gzip(text)
            if (packed.size <= MAX_REPORT_BYTES || logcat.isEmpty()) return packed
            logcat = logcat.drop(logcat.size / 2 + 1)
        }
    }

    private fun section(title: String, body: () -> String) =
        "== $title ==\n${runCatching(body).getOrElse { "unavailable: $it" }}\n\n"

    private fun app(context: Context): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return "versionName=${info.versionName}\nversionCode=$code\n" +
            "sdk=${Build.VERSION.SDK_INT}\nrelease=${Build.VERSION.RELEASE}\n" +
            "model=${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun permissions(context: Context): String {
        val pkg = context.packageName
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val projection = appOps.checkOpNoThrow("android:project_media", Process.myUid(), pkg)
        val input = ComponentName(context, InputService::class.java)
        val accessibility = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':')
            .any { it.equals(input.flattenToString(), true) || it.equals(input.flattenToShortString(), true) }
        val lines = mutableListOf(
            "project_media=${appOpModes.getOrElse(projection) { "$projection" }}",
            "accessibility=$accessibility",
        )
        fun granted(permission: String) = context.packageManager.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            lines += "overlay=${Settings.canDrawOverlays(context)}"
            lines += "ignoringBatteryOptimizations=${context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)}"
            lines += "RECORD_AUDIO=${granted(Manifest.permission.RECORD_AUDIO)}"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lines += "POST_NOTIFICATIONS=${granted(Manifest.permission.POST_NOTIFICATIONS)}"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lines += "MANAGE_EXTERNAL_STORAGE=${Environment.isExternalStorageManager()}"
        }
        return lines.joinToString("\n")
    }

    private fun exits(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "needs Android 11"
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 15)
        return exits.joinToString("\n") { exit ->
            val trace = runCatching { exit.traceInputStream?.use { it.head(4 * 1024) } }.getOrNull()
            "${stamp(exit.timestamp)} ${exitReasons.getOrElse(exit.reason) { "${exit.reason}" }} " +
                "importance=${exit.importance} ${exit.description.orEmpty()}" +
                (trace?.let { "\n$it" } ?: "")
        }.ifEmpty { "none" }
    }

    private fun crashes(context: Context): String =
        File(context.filesDir, CRASH_DIR).listFiles().orEmpty().sortedByDescending { it.name }
            .joinToString("\n") { file -> "--- ${file.name}\n${file.inputStream().use { it.head(32 * 1024) }}" }
            .ifEmpty { "none" }

    // hbb_common::init_log writes to <home>/RustDesk/Logs, where Flutter sets home to external storage.
    private fun rustLog(context: Context): String {
        @Suppress("DEPRECATION")
        val roots = listOfNotNull(
            File(Environment.getExternalStorageDirectory(), "RustDesk/Logs"),
            context.filesDir.parentFile,
            context.getExternalFilesDir(null),
        )
        val newest = roots
            .flatMap { root -> root.walkTopDown().maxDepth(4).filter { it.isFile && it.name.endsWith(".log") }.toList() }
            .maxByOrNull { it.lastModified() }
            ?: return "no .log file under ${roots.joinToString()}"
        return "${newest.path}\n${tail(newest, 64 * 1024)}"
    }

    private fun logcat(): List<String> {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "3000"))
        val timeout = Timer(true)
        timeout.schedule(5000L) { process.destroy() }
        try {
            return process.inputStream.bufferedReader().readLines()
        } finally {
            timeout.cancel()
            process.destroy()
        }
    }

    private fun InputStream.head(limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun tail(file: File, limit: Int): String = RandomAccessFile(file, "r").use {
        val start = maxOf(0L, it.length() - limit)
        val bytes = ByteArray((it.length() - start).toInt())
        it.seek(start)
        it.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun stamp(millis: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(millis))
}

class NksDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nonce = intent.getStringExtra("nonce").orEmpty()
        if (intent.action != NksDiagnostics.ACTION_COLLECT || !NksManagedService.acceptsWake(context, nonce)) return
        val requestId = intent.getStringExtra("requestId").orEmpty()
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                NksDiagnostics.send(app, nonce, requestId)
            } catch (e: Throwable) {
                Log.w("NksDiagnostics", "could not send report: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
