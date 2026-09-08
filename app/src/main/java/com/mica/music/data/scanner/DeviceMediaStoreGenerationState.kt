package com.mica.music.data.scanner

import android.content.Context
import android.os.Build
import android.provider.MediaStore

internal data class DeviceVolumeGeneration(
    val volumeName: String,
    val providerVersion: String,
    val generation: Long,
) {
    init {
        require(volumeName.isNotBlank())
        require(providerVersion.isNotBlank())
        require(generation >= 0L)
    }
}

internal sealed interface DeviceGenerationSnapshot {
    data class Available(
        val volumes: Map<String, DeviceVolumeGeneration>,
    ) : DeviceGenerationSnapshot {
        init {
            require(volumes.isNotEmpty())
            require(volumes.all { (name, state) -> name == state.volumeName })
        }
    }

    data object LegacyTimestampFallbackRequired : DeviceGenerationSnapshot

    data class Unavailable(
        val detail: String,
    ) : DeviceGenerationSnapshot
}

internal fun interface DeviceMediaStoreGenerationApi {
    fun read(): DeviceGenerationSnapshot
}

internal class AndroidDeviceMediaStoreGenerationApi(
    private val context: Context,
) : DeviceMediaStoreGenerationApi {
    override fun read(): DeviceGenerationSnapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return DeviceGenerationSnapshot.LegacyTimestampFallbackRequired
        }
        return runCatching {
            val volumeNames = MediaStore.getExternalVolumeNames(context)
            if (volumeNames.isEmpty()) {
                return@runCatching DeviceGenerationSnapshot.Unavailable(
                    "No mounted external MediaStore volumes",
                )
            }
            val volumes = linkedMapOf<String, DeviceVolumeGeneration>()
            volumeNames.sorted().forEach { volumeName ->
                val version = MediaStore.getVersion(context, volumeName)
                    ?.takeIf(String::isNotBlank)
                    ?: error("MediaStore version unavailable for $volumeName")
                val generation = MediaStore.getGeneration(context, volumeName)
                volumes[volumeName] = DeviceVolumeGeneration(
                    volumeName = volumeName,
                    providerVersion = version,
                    generation = generation,
                )
            }
            DeviceGenerationSnapshot.Available(volumes)
        }.getOrElse { error ->
            DeviceGenerationSnapshot.Unavailable(
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
        }
    }
}

internal enum class DeviceGenerationReconcileReason {
    BASELINE_MISSING,
    VOLUME_SET_CHANGED,
    PROVIDER_VERSION_CHANGED,
    GENERATION_REGRESSED,
    CONFIG_CHANGED,
}

internal sealed interface DeviceGenerationPlan {
    data class EstablishBaseline(
        val snapshot: DeviceGenerationSnapshot.Available,
        val reason: DeviceGenerationReconcileReason,
    ) : DeviceGenerationPlan

    data class Delta(
        val from: DeviceGenerationSnapshot.Available,
        val to: DeviceGenerationSnapshot.Available,
    ) : DeviceGenerationPlan

    data class NoChange(
        val snapshot: DeviceGenerationSnapshot.Available,
    ) : DeviceGenerationPlan

    data object LegacyTimestampFallbackRequired : DeviceGenerationPlan

    data class Unavailable(
        val detail: String,
    ) : DeviceGenerationPlan
}

/**
 * In-memory S3 shadow cursor. Production checkpoints are intentionally untouched.
 */
internal class DeviceGenerationShadowCursor {
    private var baseline: DeviceGenerationSnapshot.Available? = null
    private var configFingerprint: String? = null

    fun plan(
        current: DeviceGenerationSnapshot,
        currentConfigFingerprint: String,
    ): DeviceGenerationPlan = when (current) {
        DeviceGenerationSnapshot.LegacyTimestampFallbackRequired ->
            DeviceGenerationPlan.LegacyTimestampFallbackRequired

        is DeviceGenerationSnapshot.Unavailable ->
            DeviceGenerationPlan.Unavailable(current.detail)

        is DeviceGenerationSnapshot.Available -> planAvailable(
            current = current,
            currentConfigFingerprint = currentConfigFingerprint,
        )
    }

    fun advance(
        snapshot: DeviceGenerationSnapshot.Available,
        currentConfigFingerprint: String,
    ) {
        baseline = snapshot
        configFingerprint = currentConfigFingerprint
    }

    fun restoreIfEmpty(
        snapshot: DeviceGenerationSnapshot.Available,
        currentConfigFingerprint: String,
    ): Boolean {
        if (baseline != null || configFingerprint != null) return false
        advance(snapshot, currentConfigFingerprint)
        return true
    }

    fun reset() {
        baseline = null
        configFingerprint = null
    }

    private fun planAvailable(
        current: DeviceGenerationSnapshot.Available,
        currentConfigFingerprint: String,
    ): DeviceGenerationPlan {
        val previous = baseline
            ?: return DeviceGenerationPlan.EstablishBaseline(
                current,
                DeviceGenerationReconcileReason.BASELINE_MISSING,
            )
        if (configFingerprint != currentConfigFingerprint) {
            return DeviceGenerationPlan.EstablishBaseline(
                current,
                DeviceGenerationReconcileReason.CONFIG_CHANGED,
            )
        }
        if (previous.volumes.keys != current.volumes.keys) {
            return DeviceGenerationPlan.EstablishBaseline(
                current,
                DeviceGenerationReconcileReason.VOLUME_SET_CHANGED,
            )
        }
        var changed = false
        current.volumes.forEach { (volumeName, now) ->
            val before = requireNotNull(previous.volumes[volumeName])
            if (before.providerVersion != now.providerVersion) {
                return DeviceGenerationPlan.EstablishBaseline(
                    current,
                    DeviceGenerationReconcileReason.PROVIDER_VERSION_CHANGED,
                )
            }
            if (now.generation < before.generation) {
                return DeviceGenerationPlan.EstablishBaseline(
                    current,
                    DeviceGenerationReconcileReason.GENERATION_REGRESSED,
                )
            }
            if (now.generation > before.generation) changed = true
        }
        return if (changed) {
            DeviceGenerationPlan.Delta(previous, current)
        } else {
            DeviceGenerationPlan.NoChange(current)
        }
    }
}
