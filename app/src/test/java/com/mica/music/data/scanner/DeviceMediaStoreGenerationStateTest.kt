package com.mica.music.data.scanner

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DeviceMediaStoreGenerationStateTest {

    @Test
    fun firstAvailableSnapshotRequiresBaselineEstablishment() {
        val cursor = DeviceGenerationShadowCursor()
        val current = snapshot(volume("external_primary", "v1", 10L))

        assertEquals(
            DeviceGenerationPlan.EstablishBaseline(
                current,
                DeviceGenerationReconcileReason.BASELINE_MISSING,
            ),
            cursor.plan(current, "cfg"),
        )
    }

    @Test
    fun unchangedVersionAndMonotonicGenerationAllowsDelta() {
        val cursor = DeviceGenerationShadowCursor()
        val before = snapshot(
            volume("external_primary", "v1", 10L),
            volume("1234-5678", "sd-v1", 4L),
        )
        cursor.advance(before, "cfg")
        val current = snapshot(
            volume("external_primary", "v1", 14L),
            volume("1234-5678", "sd-v1", 4L),
        )

        assertEquals(DeviceGenerationPlan.Delta(before, current), cursor.plan(current, "cfg"))
    }

    @Test
    fun unchangedGenerationShortCircuitsAsNoChange() {
        val cursor = DeviceGenerationShadowCursor()
        val current = snapshot(
            volume("external_primary", "v1", 10L),
            volume("1234-5678", "sd-v1", 4L),
        )
        cursor.advance(current, "cfg")

        assertEquals(
            DeviceGenerationPlan.NoChange(current),
            cursor.plan(current, "cfg"),
        )
    }

    @Test
    fun providerVersionChangeRequiresReconcileInsteadOfGenerationDelta() {
        val cursor = DeviceGenerationShadowCursor()
        val before = snapshot(volume("external_primary", "v1", 10L))
        cursor.advance(before, "cfg")
        val current = snapshot(volume("external_primary", "v2", 1L))

        assertEquals(
            DeviceGenerationPlan.EstablishBaseline(
                current,
                DeviceGenerationReconcileReason.PROVIDER_VERSION_CHANGED,
            ),
            cursor.plan(current, "cfg"),
        )
    }

    @Test
    fun volumeSetChangeRequiresReconcile() {
        val cursor = DeviceGenerationShadowCursor()
        val before = snapshot(volume("external_primary", "v1", 10L))
        cursor.advance(before, "cfg")
        val current = snapshot(
            volume("external_primary", "v1", 11L),
            volume("1234-5678", "sd-v1", 1L),
        )

        assertEquals(
            DeviceGenerationPlan.EstablishBaseline(
                current,
                DeviceGenerationReconcileReason.VOLUME_SET_CHANGED,
            ),
            cursor.plan(current, "cfg"),
        )
    }

    @Test
    fun generationRegressionRequiresReconcileEvenWhenVersionMatches() {
        val cursor = DeviceGenerationShadowCursor()
        val before = snapshot(volume("external_primary", "v1", 10L))
        cursor.advance(before, "cfg")
        val current = snapshot(volume("external_primary", "v1", 9L))

        assertEquals(
            DeviceGenerationPlan.EstablishBaseline(
                current,
                DeviceGenerationReconcileReason.GENERATION_REGRESSED,
            ),
            cursor.plan(current, "cfg"),
        )
    }

    @Test
    fun configChangeInvalidatesShadowCursor() {
        val cursor = DeviceGenerationShadowCursor()
        val before = snapshot(volume("external_primary", "v1", 10L))
        cursor.advance(before, "cfg-a")
        val current = snapshot(volume("external_primary", "v1", 11L))

        assertEquals(
            DeviceGenerationPlan.EstablishBaseline(
                current,
                DeviceGenerationReconcileReason.CONFIG_CHANGED,
            ),
            cursor.plan(current, "cfg-b"),
        )
    }

    @Test
    @Config(sdk = [29])
    fun androidApiBelowRRequiresLegacyTimestampFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            DeviceGenerationSnapshot.LegacyTimestampFallbackRequired,
            AndroidDeviceMediaStoreGenerationApi(context).read(),
        )
    }

    private fun snapshot(
        vararg volumes: DeviceVolumeGeneration,
    ): DeviceGenerationSnapshot.Available = DeviceGenerationSnapshot.Available(
        volumes.associateBy(DeviceVolumeGeneration::volumeName),
    )

    private fun volume(
        name: String,
        version: String,
        generation: Long,
    ) = DeviceVolumeGeneration(name, version, generation)
}
