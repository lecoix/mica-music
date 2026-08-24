package com.mica.music.util

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object DiagnosticLog {
    private const val TAG = "MICA_DIAGNOSTICS"
    private const val MAX_CRASH_FILES = 5
    private const val MAX_BREADCRUMBS = 80
    private const val DIRECTORY = "diagnostics"
    private const val CURRENT_SESSION_FILE = "current-session.log"
    private const val PREVIOUS_SESSION_FILE = "previous-session.log"
    private val lock = Any()
    private val fileWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mica-diagnostic-writer").apply { isDaemon = true }
    }
    private val breadcrumbs = ArrayDeque<String>()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        val applicationContext = context.applicationContext
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            appContext = applicationContext
            runCatching {
                rotateSessionLogs(diagnosticsDir(applicationContext))
            }
            event("App", "process started; ${deviceSummary(applicationContext)}")
            AudioEnvironmentDiagnostics.logEnvironment(applicationContext, "install")
            recordPreviousExit(applicationContext)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e(TAG, "Uncaught in thread ${thread.name}", throwable)
                writeCrashFile(applicationContext, thread, throwable)
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun event(category: String, message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            val line = buildString {
                append(timestampFormat.format(Date()))
                append(" [")
                append(Thread.currentThread().name)
                append("] ")
                append(category)
                append(": ")
                append(message)
                if (throwable != null) {
                    append('\n')
                    append(stackTrace(throwable))
                }
            }
            Log.d(TAG, line)
            breadcrumbs.addLast(line)
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
            val context = appContext
            if (context != null) {
                fileWriter.execute {
                    runCatching {
                        diagnosticsDir(context).resolve(CURRENT_SESSION_FILE)
                            .appendText("$line\n", Charsets.UTF_8)
                    }.onFailure { Log.w(TAG, "Unable to append diagnostics", it) }
                }
            }
        }
    }

    private fun recordPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "java-crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
            ApplicationExitInfo.REASON_SIGNALED -> "signal"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource-usage"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
            else -> "reason-${exit.reason}"
        }
        event(
            "PreviousExit",
            "reason=$reason; status=${exit.status}; importance=${exit.importance}; " +
                "time=${timestampFormat.format(Date(exit.timestamp))}; description=${exit.description}",
        )
    }

    fun shareReport(context: Context, extraReportSection: String? = null): Boolean {
        ScreenLockDiagnostics.onDiagnosticsExport(context)
        AudioEnvironmentDiagnostics.logEnvironment(context, "export")
        flushPendingWrites()
        val report = synchronized(lock) {
            runCatching {
                buildReport(
                    context = context.applicationContext,
                    extraReportSection = extraReportSection,
                )
            }.getOrNull()
        } ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                report,
            )
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mica Music 闪退日志")
            putExtra(Intent.EXTRA_TEXT, "Mica Music 诊断日志，包含最近的闪退与切歌记录。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, report.name, uri)
        }
        return runCatching {
            val chooser = Intent.createChooser(intent, "导出闪退日志")
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private fun flushPendingWrites() {
        runCatching {
            fileWriter.submit { }.get(2, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "Timed out flushing diagnostics before export", it) }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        synchronized(lock) {
            runCatching {
                val dir = diagnosticsDir(context)
                val file = dir.resolve("crash-${fileTimestampFormat.format(Date())}.log")
                file.writeText(
                    buildString {
                        appendLine("Mica Music crash report")
                        appendLine(deviceSummary(context))
                        appendLine("Thread: ${thread.name} (id=${thread.id})")
                        appendLine()
                        appendLine("Recent events:")
                        breadcrumbs.forEach { appendLine(it) }
                        appendLine()
                        appendLine("Uncaught exception:")
                        append(stackTrace(throwable))
                    },
                    Charsets.UTF_8,
                )
                trimOldCrashFiles(dir)
            }.onFailure { Log.e(TAG, "Unable to persist crash report", it) }
        }
    }

    private fun buildReport(context: Context, extraReportSection: String? = null): File {
        val dir = diagnosticsDir(context)
        val report = dir.resolve("mica-diagnostics.txt")
        val crashes = dir.listFiles { file ->
            file.isFile && file.name.startsWith("crash-") && file.extension == "log"
        }.orEmpty().sortedByDescending(File::lastModified)
        report.writeText(
            buildString {
                appendLine("Mica Music diagnostics")
                appendLine(deviceSummary(context))
                appendLine("Generated: ${timestampFormat.format(Date())}")
                appendLine()
                if (!extraReportSection.isNullOrBlank()) {
                    appendLine(extraReportSection.trimEnd())
                    appendLine()
                }
                appendLine("Current session:")
                appendLine(
                    dir.resolve(CURRENT_SESSION_FILE).takeIf(File::exists)?.readText().orEmpty(),
                )
                dir.resolve(PREVIOUS_SESSION_FILE)
                    .takeIf { it.isFile && it.length() > 0L }
                    ?.let { previousSession ->
                        appendLine()
                        appendLine("===== Previous process session =====")
                        appendLine(previousSession.readText())
                    }
                crashes.forEach { crash ->
                    appendLine()
                    appendLine("===== ${crash.name} =====")
                    appendLine(crash.readText())
                }
            },
            Charsets.UTF_8,
        )
        return report
    }

    internal fun rotateSessionLogs(dir: File) {
        dir.mkdirs()
        val current = dir.resolve(CURRENT_SESSION_FILE)
        if (current.isFile && current.length() > 0L) {
            current.copyTo(dir.resolve(PREVIOUS_SESSION_FILE), overwrite = true)
        }
        current.writeText("", Charsets.UTF_8)
    }

    private fun diagnosticsDir(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private fun trimOldCrashFiles(dir: File) {
        dir.listFiles { file ->
            file.isFile && file.name.startsWith("crash-") && file.extension == "log"
        }.orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_CRASH_FILES)
            .forEach { it.delete() }
    }

    private fun deviceSummary(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($versionCode)"
        }.getOrDefault("unknown")
        return "App=$version; Android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); " +
            "Device=${Build.MANUFACTURER} ${Build.MODEL}; ABI=${Build.SUPPORTED_ABIS.joinToString()}"
    }

    private fun stackTrace(throwable: Throwable): String =
        StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()
}
