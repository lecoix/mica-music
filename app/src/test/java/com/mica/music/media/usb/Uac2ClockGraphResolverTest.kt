package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Uac2ClockGraphResolverTest {
    @Test
    fun directClockSourceResolvesToImmutablePlan() {
        val facts = facts(
            entities = mapOf(4 to UsbUac2ClockEntity.Source(4, attributes = 3, controls = 3)),
            terminalClock = 4,
        )

        val result = Uac2ClockGraphResolver.resolve(facts, terminalLink = 2)

        assertEquals(
            Uac2ClockGraphResolution.Resolved(
                UsbClockPlan.Uac2Entity(sourceEntityId = 4, entityPath = listOf(4)),
            ),
            result,
        )
    }

    @Test
    fun multiInputSelectorRequiresProvenSelection() {
        val facts = facts(
            entities = mapOf(
                10 to UsbUac2ClockEntity.Selector(10, sourceIds = listOf(4, 5), controls = 3),
                4 to UsbUac2ClockEntity.Source(4, attributes = 3, controls = 3),
                5 to UsbUac2ClockEntity.Source(5, attributes = 3, controls = 3),
            ),
            terminalClock = 10,
        )

        val ambiguous = Uac2ClockGraphResolver.resolve(facts, terminalLink = 2)
        assertTrue(ambiguous is Uac2ClockGraphResolution.Rejected)
        assertEquals(
            UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY,
            (ambiguous as Uac2ClockGraphResolution.Rejected).rejection.code,
        )

        val resolved = Uac2ClockGraphResolver.resolve(
            facts,
            terminalLink = 2,
            selectorSelections = mapOf(10 to 5),
        )
        assertEquals(
            Uac2ClockGraphResolution.Resolved(
                UsbClockPlan.Uac2Entity(sourceEntityId = 5, entityPath = listOf(10, 5)),
            ),
            resolved,
        )
    }

    @Test
    fun cycleFailsClosed() {
        val facts = facts(
            entities = mapOf(
                10 to UsbUac2ClockEntity.Selector(10, listOf(11), controls = 0),
                11 to UsbUac2ClockEntity.Selector(11, listOf(10), controls = 0),
            ),
            terminalClock = 10,
        )

        val result = Uac2ClockGraphResolver.resolve(facts, terminalLink = 2)

        assertTrue(result is Uac2ClockGraphResolution.Rejected)
        assertEquals(
            UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
            (result as Uac2ClockGraphResolution.Rejected).rejection.code,
        )
    }

    @Test
    fun multiplierNeedsVerifiedRatioAndCarriesItIntoPlan() {
        val facts = facts(
            entities = mapOf(
                10 to UsbUac2ClockEntity.Multiplier(10, sourceId = 4, controls = 0x0f),
                4 to UsbUac2ClockEntity.Source(4, attributes = 3, controls = 3),
            ),
            terminalClock = 10,
        )

        val missing = Uac2ClockGraphResolver.resolve(facts, terminalLink = 2)
        assertTrue(missing is Uac2ClockGraphResolution.Rejected)

        val resolved = Uac2ClockGraphResolver.resolve(
            facts,
            terminalLink = 2,
            multiplierRatios = mapOf(10 to UsbClockMultiplierRatio(2, 1)),
        )
        assertEquals(
            Uac2ClockGraphResolution.Resolved(
                UsbClockPlan.Uac2Entity(
                    sourceEntityId = 4,
                    entityPath = listOf(10, 4),
                    rateMultiplierNumerator = 2,
                    rateMultiplierDenominator = 1,
                ),
            ),
            resolved,
        )
    }

    @Test
    fun exposedClockValidityMustBeVerified() {
        val facts = facts(
            entities = mapOf(4 to UsbUac2ClockEntity.Source(4, attributes = 3, controls = 0x0f)),
            terminalClock = 4,
        )

        val invalid = Uac2ClockGraphResolver.resolve(facts, terminalLink = 2)
        assertTrue(invalid is Uac2ClockGraphResolution.Rejected)
        assertEquals(
            UsbAudioRejectionCode.CLOCK_INVALID,
            (invalid as Uac2ClockGraphResolution.Rejected).rejection.code,
        )

        val valid = Uac2ClockGraphResolver.resolve(
            facts,
            terminalLink = 2,
            validClockSourceIds = setOf(4),
        )
        assertTrue(valid is Uac2ClockGraphResolution.Resolved)
    }

    private fun facts(
        entities: Map<Int, UsbUac2ClockEntity>,
        terminalClock: Int,
    ) = UsbParsedAudioDescriptorFacts(
        audioFunction = UsbAudioFunction(
            protocol = UsbAudioProtocol.UAC2,
            controlInterfaceNumber = 1,
            streamingInterfaceNumbers = setOf(2),
        ),
        busSpeed = UsbBusSpeed.HIGH,
        streamingAlternates = emptyList(),
        uac2ClockEntities = entities,
        uac2TerminalClockLinks = mapOf(2 to terminalClock),
    )
}
