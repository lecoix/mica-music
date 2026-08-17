package com.mica.music.media.usb.protocol

internal val TEST_PCM_GEOMETRY = PcmAudioGeometry(
    sampleRate = 96_000,
    channelCount = 2,
    pcmEncoding = 0x20000000,
    outputChannels = null,
)

internal fun UsbExclusivePlaybackProtocol.commitPcmConfigure(
    permit: PcmConfigurePermit,
    receipt: SideEffectReceipt,
): CommitDisposition = commitPcmConfigure(permit, receipt, TEST_PCM_GEOMETRY)

/**
 * Test-only ownership seeding through the same public permit/receipt transactions used by M1.
 * Production source intentionally exposes no family-ownership fabrication seam.
 */
internal fun UsbExclusivePlaybackProtocol.installOwnedFamilyForModel(
    family: PlaybackFamily,
    mutationId: MutationId,
    adapterInstanceId: AdapterInstanceId,
    occurrence: PlaybackOccurrence,
    runtimeIdentity: RuntimeIdentity,
    facts: String = "model",
    geometry: PcmAudioGeometry = TEST_PCM_GEOMETRY,
): CommitDisposition {
    val ledgerField = UsbExclusivePlaybackProtocol::class.java.getDeclaredField("ledger").apply {
        isAccessible = true
    }
    val ledger = ledgerField.get(this) as PlaybackIntentLedger
    val originalIntent = ledger.snapshot().desired
    val originalApplicationCurrent = snapshot().applicationCurrent
    if (originalIntent != PlaybackIntent.PLAY) ledger.publish(PlaybackIntent.PLAY)

    registerAdapter(adapterInstanceId)
    val mediaId = "fixture-${occurrence.windowSequenceNumber}-${occurrence.periodUid.hashCode()}"
    updateApplicationCurrent(mediaId, occurrence.periodUid, occurrence)
    val mutation = requireNotNull(
        beginMutation(
            kind = MutationKind.MANUAL,
            targetMediaId = mediaId,
            targetFamily = family,
            targetFacts = facts,
            targetOccurrence = occurrence,
            destinationAdapterInstanceId = adapterInstanceId,
        ),
    )
    check(mutation.mutationId == mutationId) {
        "fixture expected mutation=$mutationId but protocol minted ${mutation.mutationId}"
    }

    val disposition = when (family) {
        PlaybackFamily.PCM -> {
            val permit = requireNotNull(
                preparePcmConfigure(mutation.mutationId, adapterInstanceId, occurrence, facts),
            )
            val resourceValue = runtimeIdentity.value.removePrefix("pcm:")
            commitPcmConfigure(
                permit,
                SideEffectReceipt.Completed(
                    permit.activationId,
                    ResourceIdentity(resourceValue),
                    "fixture-configured",
                ),
                geometry,
            )
        }
        PlaybackFamily.DOP -> {
            fun complete(stage: DirectStage): CommitDisposition? {
                val permit = requireNotNull(
                    prepareDirectStage(
                        mutation.mutationId,
                        adapterInstanceId,
                        occurrence,
                        stage,
                        runtimeIdentity,
                    ),
                )
                return commitDirectStage(
                    permit,
                    SideEffectReceipt.Completed(
                        permit.activationId,
                        ResourceIdentity("fixture-${runtimeIdentity.value}-${stage.name.lowercase()}"),
                        "fixture-$stage",
                        runtimeIdentity,
                    ),
                )
            }
            check(complete(DirectStage.CREATE_RUNTIME) == null)
            check(complete(DirectStage.PREFILL) == null)
            check(observeAdapterStarted(adapterInstanceId, occurrence))
            check(complete(DirectStage.ARM) == null)
            val accepted = requireNotNull(complete(DirectStage.SOURCE_ACCEPT))
            check(
                attachDirectPhysicalEndpoint(
                    adapterInstanceId,
                    runtimeIdentity,
                    FakeDirectPhysicalEndpoint(),
                ),
            )
            accepted
        }
    }

    updateApplicationCurrent(
        originalApplicationCurrent.mediaId,
        originalApplicationCurrent.periodUid,
        originalApplicationCurrent.occurrence,
    )
    if (originalIntent != PlaybackIntent.PLAY) {
        ledger.publish(originalIntent)
        adoptLatestIntent()
    }
    return disposition
}

internal fun greenDirectFullReleaseFacts(
    writerJoined: Boolean = true,
    pauseWorkerJoined: Boolean = true,
    feederStagedPendingZero: Boolean = true,
    feederUpstreamPendingZero: Boolean = true,
    feederErrorNull: Boolean = true,
    p5PendingPackedZero: Boolean = true,
    p5PendingPartialZero: Boolean = true,
    p5PendingHalfZero: Boolean = true,
    nativeDestroyed: Boolean = true,
    altRestored: Boolean = true,
    clockRestored: Boolean = true,
    interfacesReleased: Boolean = true,
    driversRebound: Boolean = true,
) = DirectFullReleaseFacts(
    writerJoined = writerJoined,
    pauseWorkerJoined = pauseWorkerJoined,
    feederStagedPendingZero = feederStagedPendingZero,
    feederUpstreamPendingZero = feederUpstreamPendingZero,
    feederErrorNull = feederErrorNull,
    p5PendingPackedZero = p5PendingPackedZero,
    p5PendingPartialZero = p5PendingPartialZero,
    p5PendingHalfZero = p5PendingHalfZero,
    nativeDestroyed = nativeDestroyed,
    altRestored = altRestored,
    clockRestored = clockRestored,
    interfacesReleased = interfacesReleased,
    driversRebound = driversRebound,
)

internal fun greenDirectRetainedFacts(
    feederPendingZero: Boolean = true,
    p5PendingPackedZero: Boolean = true,
    p5PendingPartialZero: Boolean = true,
    p5PendingHalfZero: Boolean = true,
    sourceResetApplied: Boolean = true,
    markerContinuityRetained: Boolean = true,
) = DirectRetainedCarrierFacts(
    feederPendingZero = feederPendingZero,
    p5PendingPackedZero = p5PendingPackedZero,
    p5PendingPartialZero = p5PendingPartialZero,
    p5PendingHalfZero = p5PendingHalfZero,
    sourceResetApplied = sourceResetApplied,
    markerContinuityRetained = markerContinuityRetained,
)

internal class FakeDirectPhysicalEndpoint(
    var fullRelease: DirectFullReleaseFacts? = greenDirectFullReleaseFacts(),
    var retained: DirectRetainedCarrierFacts? = greenDirectRetainedFacts(),
) : DirectPhysicalRuntimeEndpoint {
    override fun fullReleaseFactsAfterClose(): DirectFullReleaseFacts? = fullRelease
    override fun retainedCarrierFactsAfterTransition(): DirectRetainedCarrierFacts? = retained
}

internal fun UsbExclusivePlaybackProtocol.completeOwnedDirectRelease(
    adapter: AdapterInstanceId,
    runtime: RuntimeIdentity,
): Boolean {
    val mutationId = snapshot().mutation?.mutationId ?: return false
    return completeDirectFamilyReleaseFromEndpoint(mutationId, adapter, runtime)
}

internal fun UsbExclusivePlaybackProtocol.typedDirectReleased(
    runtime: RuntimeIdentity,
    occurrence: PlaybackOccurrence,
    adapter: AdapterInstanceId,
    facts: DirectFullReleaseFacts = greenDirectFullReleaseFacts(),
) = FamilyProof.DirectFamilyReleased(
    runtimeIdentity = runtime,
    sourceOccurrence = occurrence,
    adapterInstanceId = adapter,
    outputTarget = snapshot().outputTarget,
    facts = facts,
)

internal fun UsbExclusivePlaybackProtocol.typedDirectRetained(
    runtime: RuntimeIdentity,
    sourceOccurrence: PlaybackOccurrence,
    targetOccurrence: PlaybackOccurrence,
    adapter: AdapterInstanceId,
    sourceGeneration: Long = (snapshot().outputTarget as? OutputTarget.UsbBound)?.generation?.value ?: 0L,
    facts: DirectRetainedCarrierFacts = greenDirectRetainedFacts(),
) = FamilyProof.DirectRuntimeRetained(
    runtimeIdentity = runtime,
    sourceOccurrence = sourceOccurrence,
    targetOccurrence = targetOccurrence,
    adapterInstanceId = adapter,
    outputTarget = snapshot().outputTarget,
    sourceGeneration = sourceGeneration,
    facts = facts,
)
