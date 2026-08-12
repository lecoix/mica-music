package com.mica.music.media.usb

import kotlin.math.abs

internal data class UsbClockMultiplierRatio(
    val numerator: Long,
    val denominator: Long,
) {
    init {
        require(numerator > 0)
        require(denominator > 0)
    }
}

internal sealed interface Uac2ClockGraphResolution {
    data class Resolved(val plan: UsbClockPlan.Uac2Entity) : Uac2ClockGraphResolution
    data class Rejected(val rejection: UsbAudioRejection) : Uac2ClockGraphResolution
}

internal object Uac2ClockGraphResolver {
    fun resolve(
        facts: UsbParsedAudioDescriptorFacts,
        terminalLink: Int,
        selectorSelections: Map<Int, Int> = emptyMap(),
        multiplierRatios: Map<Int, UsbClockMultiplierRatio> = emptyMap(),
        validClockSourceIds: Set<Int> = emptySet(),
        maxDepth: Int = 16,
    ): Uac2ClockGraphResolution {
        if (facts.audioFunction.protocol != UsbAudioProtocol.UAC2) {
            return rejected(UsbAudioRejectionCode.UNSUPPORTED_PROTOCOL, "clock graph resolver requires UAC2")
        }
        val rootId = facts.uac2TerminalClockLinks[terminalLink]
            ?: return rejected(
                UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                "terminalLink=$terminalLink has no clock association",
            )

        val path = mutableListOf<Int>()
        val visiting = linkedSetOf<Int>()
        val ratioState = MutableRatio(1L, 1L)

        fun walk(entityId: Int, depth: Int): Uac2ClockGraphResolution {
            if (depth > maxDepth) {
                return rejected(
                    UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                    "clock graph exceeded maxDepth=$maxDepth",
                )
            }
            if (!visiting.add(entityId)) {
                return rejected(
                    UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                    "clock graph cycle at entity=$entityId",
                )
            }
            path += entityId
            val entity = facts.uac2ClockEntities[entityId]
                ?: return rejected(
                    UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                    "clock entity=$entityId is missing",
                )
            val result = when (entity) {
                is UsbUac2ClockEntity.Source -> {
                    val validityCapability = (entity.controls ushr 2) and 0x03
                    if (validityCapability != 0 && entity.id !in validClockSourceIds) {
                        rejected(
                            UsbAudioRejectionCode.CLOCK_INVALID,
                            "clock source=${entity.id} exposes validity control but is not verified valid",
                        )
                    } else {
                        Uac2ClockGraphResolution.Resolved(
                            UsbClockPlan.Uac2Entity(
                                sourceEntityId = entity.id,
                                entityPath = path.toList(),
                                rateMultiplierNumerator = ratioState.numerator,
                                rateMultiplierDenominator = ratioState.denominator,
                            ),
                        )
                    }
                }

                is UsbUac2ClockEntity.Selector -> {
                    if (entity.sourceIds.isEmpty()) {
                        rejected(
                            UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                            "clock selector=${entity.id} has no source pins",
                        )
                    } else {
                        val selected = when {
                            entity.sourceIds.size == 1 -> entity.sourceIds.single()
                            else -> selectorSelections[entity.id]
                        }
                        if (selected == null || selected !in entity.sourceIds) {
                            rejected(
                                UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY,
                                "clock selector=${entity.id} selection is not proven",
                            )
                        } else {
                            walk(selected, depth + 1)
                        }
                    }
                }

                is UsbUac2ClockEntity.Multiplier -> {
                    val ratio = multiplierRatios[entity.id]
                        ?: return rejected(
                            UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                            "clock multiplier=${entity.id} ratio is not verified",
                        )
                    if (!ratioState.multiply(ratio)) {
                        rejected(
                            UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                            "clock multiplier=${entity.id} ratio overflow/invalid",
                        )
                    } else {
                        walk(entity.sourceId, depth + 1)
                    }
                }
            }
            visiting.remove(entityId)
            return result
        }

        return walk(rootId, 0)
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = abs(a)
        var y = abs(b)
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0L) 1L else x
    }

    private fun rejected(code: UsbAudioRejectionCode, detail: String) =
        Uac2ClockGraphResolution.Rejected(UsbAudioRejection(code, detail))

    // Kept local to resolve() by the compiler through this helper shape would obscure overflow handling;
    // multiplication is implemented inline below by a tiny extension-like local function.
    private fun Long.safeMultiply(other: Long): Long? {
        if (this == 0L || other == 0L) return 0L
        if (this > Long.MAX_VALUE / other) return null
        return this * other
    }

    private fun MutableRatio.multiply(ratio: UsbClockMultiplierRatio): Boolean {
        var n = ratio.numerator
        var d = ratio.denominator
        val cross1 = gcd(n, denominator)
        n /= cross1
        denominator /= cross1
        val cross2 = gcd(numerator, d)
        numerator /= cross2
        d /= cross2
        numerator = numerator.safeMultiply(n) ?: return false
        denominator = denominator.safeMultiply(d) ?: return false
        return numerator > 0 && denominator > 0
    }

    private data class MutableRatio(var numerator: Long, var denominator: Long)
}
