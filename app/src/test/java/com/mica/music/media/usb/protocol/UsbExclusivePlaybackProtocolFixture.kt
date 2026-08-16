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
            requireNotNull(complete(DirectStage.SOURCE_ACCEPT))
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
