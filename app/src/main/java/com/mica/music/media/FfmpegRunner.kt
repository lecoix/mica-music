package com.mica.music.media

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 通过 [ProcessBuilder] 调用自编 FFmpeg。
 *
 * 二进制以 [LIB_NAME] 打进 jniLibs（由 Gradle 从 assets 同步），安装后位于
 * [nativeLibraryDir]——Android 10+ 仅允许从该只读目录 exec，不能从 filesDir 执行。
 */
object FfmpegRunner {

    data class Session(
        val returnCode: Int,
        val logs: String,
    ) {
        val success: Boolean get() = returnCode == 0
    }

    @Volatile
    private var cachedBinary: File? = null

    fun hasEmbeddedBinary(context: Context): Boolean = resolveBinary(context) != null

    fun executeWithArguments(context: Context, args: Array<String>): Session {
        val bin = resolveBinary(context)
            ?: return Session(
                returnCode = -1,
                logs = "未找到 FFmpeg（$LIB_NAME / assets/$ASSET_PATH）。请运行 scripts\\build-ffmpeg-arm64.ps1 后重新编译安装。",
            )
        return executeCli(bin, args)
    }

    private fun resolveBinary(context: Context): File? {
        cachedBinary?.let { if (it.exists() && it.length() > 0L) return it }

        val fromNative = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
        if (fromNative.exists() && fromNative.length() > 0L) {
            ensureExecutable(fromNative)
            cachedBinary = fromNative
            return fromNative
        }

        if (!hasAssetBinary(context)) return null

        // 兜底：极旧系统或 native 未打进包时，仍尝试解压到 filesDir（Android 10+ 通常会 EACCES）
        val fallback = File(context.filesDir, "bin/ffmpeg-arm64")
        if (!fallback.exists() || fallback.length() <= 0L) {
            fallback.parentFile?.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                fallback.outputStream().use { output -> input.copyTo(output) }
            }
            ensureExecutable(fallback)
        }
        cachedBinary = fallback
        return fallback
    }

    private fun hasAssetBinary(context: Context): Boolean =
        runCatching {
            context.assets.open(ASSET_PATH).use { it.read() }
            true
        }.getOrDefault(false)

    private fun ensureExecutable(file: File) {
        runCatching {
            Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IXUSR or OsConstants.S_IRGRP or OsConstants.S_IXGRP)
        }
        file.setExecutable(true, false)
    }

    private fun executeCli(binary: File, args: Array<String>): Session {
        return try {
            val command = listOf(binary.absolutePath) + args.toList()
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val logs = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    logs.appendLine(line)
                    line = reader.readLine()
                }
            }
            Session(returnCode = process.waitFor(), logs = logs.toString())
        } catch (e: Exception) {
            Session(
                returnCode = -1,
                logs = buildString {
                    append("无法启动 FFmpeg：${e.message}")
                    append("\n路径：${binary.absolutePath}")
                    append("\n若提示 Permission denied，请确认已用 jniLibs 打包 $LIB_NAME 并重新安装 APK。")
                },
            )
        }
    }

    private const val LIB_NAME = "libmica_ffmpeg.so"
    private const val ASSET_PATH = "ffmpeg/arm64-v8a/ffmpeg"
}
