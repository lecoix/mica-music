package com.afalphy.sylvakru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPacketCadenceTest {
    @Test
    fun seventyTwoHourProjectionIsExactForSupportedRates() {
        val seconds = 72L * 60L * 60L
        for (rate in listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800)) {
            val packets = seconds * 1_000L
            assertEquals(
                seconds * rate,
                UsbPacketCadence.projectNominalFrames(rate, 1_000, packets),
            )
        }
    }

    @Test
    fun fixedSeedHundredThousandStepStressKeepsExactFrameTotal() {
        val rates = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800)
        val random = java.util.Random(20_260_820L)
        repeat(32) {
            val rate = rates[random.nextInt(rates.size)]
            val cadence = UsbPacketCadence(rate, 1_000)
            var total = 0L
            repeat(100_000) {
                val frames = cadence.nextNominalFrames()
                assertTrue(frames > 0)
                total += frames
            }
            assertEquals(
                UsbPacketCadence.projectNominalFrames(rate, 1_000, 100_000),
                total,
            )
        }
    }
}
