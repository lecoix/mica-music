package com.mica.music.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import java.util.Locale

/**
 * 记录可能干扰播放的第三方音效 App，以及系统级音频效果 / 播放占用概况。
 * 仅使用公开 SDK API（clientUid 等为 @hide，第三方 App 无法直接读取）。
 */
object AudioEnvironmentDiagnostics {
    @Volatile
    private var appContext: Context? = null

    private val effectKeywords = listOf(
        "viper",
        "dolby",
        "atmos",
        "equalizer",
        "equaliser",
        "音效",
        "蝰蛇",
        "杜比",
        "soundalive",
        "wavelet",
        "poweramp",
        "harman",
        "jbl",
        "audiofx",
        "audio.fx",
        "audioeffect",
        "fxserver",
        "misound",
        "dirac",
        "dts",
        "audioeffect",
        "soundenhance",
        "earbuds",
    )

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun logEnvironment(context: Context, reason: String) {
        val appCtx = context.applicationContext
        appContext = appCtx
        logEnvironment(reason)
    }

    fun logEnvironment(reason: String) {
        val appCtx = appContext ?: return
        val installed = installedEffectCandidates(appCtx)
        val registered = registeredSystemEffects()
        val running = runningEffectProcesses(appCtx)
        val playbackCount = activePlaybackCount(appCtx)
        val musicActive = isMusicActive(appCtx)
        val message = buildString {
            append("reason=$reason")
            append("; installedEffects=")
            append(if (installed.isEmpty()) "none" else installed.joinToString("|"))
            append("; registeredEffects=")
            append(if (registered.isEmpty()) "none" else registered.joinToString("|"))
            append("; runningEffectProcesses=")
            append(if (running.isEmpty()) "none" else running.joinToString("|"))
            append("; activePlaybackCount=$playbackCount")
            append("; musicActive=$musicActive")
        }
        DiagnosticLog.event("AudioEnv", message)
    }

    private fun installedEffectCandidates(context: Context): List<String> {
        val pm = context.packageManager
        val self = context.packageName
        return runCatching {
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != self }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || looksLikeEffectPackage(it.packageName) }
                .mapNotNull { app -> toCandidate(pm, app) }
                .distinct()
                .sorted()
                .take(12)
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun looksLikeEffectPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.US)
        return effectKeywords.any { lower.contains(it) }
    }

    private fun toCandidate(
        pm: android.content.pm.PackageManager,
        app: ApplicationInfo,
    ): String? {
        val pkg = app.packageName
        val label = runCatching { pm.getApplicationLabel(app).toString().trim() }.getOrDefault(pkg)
        val haystack = "$pkg $label".lowercase(Locale.US)
        if (!effectKeywords.any { haystack.contains(it) }) return null
        return "$label($pkg)"
    }

    /** 系统已注册的音频效果（ViPER / 杜比等常会在这里留下 implementor 名称）。 */
    private fun registeredSystemEffects(): List<String> {
        return runCatching {
            AudioEffect.queryEffects()
                .asSequence()
                .mapNotNull { descriptor ->
                    val haystack = "${descriptor.name} ${descriptor.implementor}".lowercase(Locale.US)
                    if (!effectKeywords.any { haystack.contains(it) }) return@mapNotNull null
                    "${descriptor.name}@${descriptor.implementor}"
                }
                .distinct()
                .sorted()
                .take(8)
                .toList()
        }.getOrDefault(emptyList())
    }

    /**
     * 尝试匹配正在运行的音效进程。Android 11+ 通常只能看到本 App，结果为空不代表没开。
     */
    @Suppress("DEPRECATION")
    private fun runningEffectProcesses(context: Context): List<String> {
        val installedPkgs = installedEffectPackageNames(context)
        if (installedPkgs.isEmpty()) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching {
            am.runningAppProcesses
                ?.asSequence()
                ?.filter { proc ->
                    proc.pkgList?.any { pkg -> pkg in installedPkgs } == true
                }
                ?.map { proc ->
                    val pkg = proc.pkgList?.firstOrNull { it in installedPkgs } ?: proc.processName
                    val label = runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0),
                        ).toString()
                    }.getOrDefault(pkg)
                    "$label($pkg)"
                }
                ?.distinct()
                ?.sorted()
                ?.take(6)
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun installedEffectPackageNames(context: Context): Set<String> {
        val pm = context.packageManager
        val self = context.packageName
        return runCatching {
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != self }
                .filter { app ->
                    val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
                    val haystack = "${app.packageName} $label".lowercase(Locale.US)
                    effectKeywords.any { haystack.contains(it) }
                }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun activePlaybackCount(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return -1
        val manager = context.getSystemService(AudioManager::class.java) ?: return -1
        return runCatching { manager.activePlaybackConfigurations.size }.getOrDefault(-1)
    }

    private fun isMusicActive(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching { manager.isMusicActive }.getOrDefault(false)
    }
}
