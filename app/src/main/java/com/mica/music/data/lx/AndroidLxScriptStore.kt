package com.mica.music.data.lx

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

internal class AndroidLxScriptStore(context: Context) : LxScriptStore {
    private val baseFile = File(context.noBackupFilesDir, "lx-experiment.json")
    private val pendingFile = File(context.noBackupFilesDir, "lx-experiment.json.new")

    override fun read(): LxStoredScript? {
        if (!baseFile.exists()) return null
        val bytes = FileInputStream(baseFile).use { it.readBytesBounded(4 * 1024 * 1024) }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return LxStoredScript(json.getString("id"), json.getString("raw"), json.getBoolean("enabled")).also { it.metadata }
    }

    override fun write(value: LxStoredScript?) {
        if (value == null) {
            if (pendingFile.exists() && !pendingFile.delete()) throw IOException("Failed to delete pending LX source")
            if (baseFile.exists() && !baseFile.delete()) throw IOException("Failed to delete LX source")
            return
        }
        val bytes = JSONObject().put("id", value.id).put("raw", value.raw).put("enabled", value.enabled).toString().toByteArray()
        baseFile.parentFile?.mkdirs()
        FileOutputStream(pendingFile, false).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        try {
            Files.move(
                pendingFile.toPath(), baseFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pendingFile.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
            // Some host filesystems reject atomic replacement even though an atomic move is exposed.
            if (!pendingFile.exists()) throw failure
            Files.move(pendingFile.toPath(), baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val committed = read() ?: throw IOException("LX source durable commit disappeared")
        if (committed.id != value.id) throw IOException("LX source durable commit verification failed")
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "输入超过容量限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
