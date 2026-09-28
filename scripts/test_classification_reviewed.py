"""Manually reviewed overrides for Mica test taxonomy.

Categories express what a test is allowed to change with. Exact test overrides
win over suite rules. Ambiguous mechanism tests default to Implementation
Verification rather than being promoted to a durable contract.
"""

BEHAVIOR_CONTRACT = "BEHAVIOR_CONTRACT"
ARCHITECTURE_CONTRACT = "ARCHITECTURE_CONTRACT"
IMPLEMENTATION_VERIFICATION = "IMPLEMENTATION_VERIFICATION"
OBSOLETE = "OBSOLETE"

REVIEWED_SUITE_CATEGORIES = {
    "PlaybackErrorMapperTest": BEHAVIOR_CONTRACT,
    "LibraryAccessCoordinatorTest": BEHAVIOR_CONTRACT,
    "PlaylistStoreTest": BEHAVIOR_CONTRACT,
    "PlaybackOrderStateTest": BEHAVIOR_CONTRACT,
    "ExternalAudioOpenTest": BEHAVIOR_CONTRACT,
    "SongActionsTest": BEHAVIOR_CONTRACT,
    "LyricDisplayRowsTest": BEHAVIOR_CONTRACT,
    "PlaybackStatisticsRepositoryTest": BEHAVIOR_CONTRACT,
    "ReplayGainTest": BEHAVIOR_CONTRACT,
    "ServicePlaybackStateCoordinatorTest": BEHAVIOR_CONTRACT,
    "SoundFxSettingsTest": BEHAVIOR_CONTRACT,
    "DeviceObjectPostProbeValidatorTest": BEHAVIOR_CONTRACT,
    "MicaRendererSupportPoliciesTest": BEHAVIOR_CONTRACT,
    "RemoteMediaItemProviderTest": BEHAVIOR_CONTRACT,
    "ArtistNamesTest": BEHAVIOR_CONTRACT,
    "LibraryBrowseDetailsTest": BEHAVIOR_CONTRACT,
    "LoudnessReplayGainTest": BEHAVIOR_CONTRACT,
    "LyricsTimelineEngineTest": BEHAVIOR_CONTRACT,
    "PlaybackMimeResolverTest": BEHAVIOR_CONTRACT,
    "PlaylistStoreFollowupPublicationTest": BEHAVIOR_CONTRACT,
    "SongSorterTest": BEHAVIOR_CONTRACT,
    "SongTitleDisplayTest": BEHAVIOR_CONTRACT,
    "PlaybackQueueModelTest": BEHAVIOR_CONTRACT,
    "PlaybackStatusTest": BEHAVIOR_CONTRACT,
    "SleepTimerControllerTest": BEHAVIOR_CONTRACT,
    "ExternalLyricsSettingsTest": BEHAVIOR_CONTRACT,
    "LyricsTimingTest": BEHAVIOR_CONTRACT,
    "PlayHistoryStoreTest": BEHAVIOR_CONTRACT,
    "PlaybackTuningTest": BEHAVIOR_CONTRACT,
    "SongLyricsOffsetStoreTest": BEHAVIOR_CONTRACT,
    "TrackMetadataContainerTest": BEHAVIOR_CONTRACT,
    "TransientPlaybackCatalogTest": BEHAVIOR_CONTRACT,
    "AppUpdateTest": BEHAVIOR_CONTRACT,
    "AppUiSettingsWallpaperRobolectricTest": BEHAVIOR_CONTRACT,
    "AppWallpaperStoreTest": BEHAVIOR_CONTRACT,
    "LyricsBoundaryClockTest": BEHAVIOR_CONTRACT,
    "ReleaseDatesTest": BEHAVIOR_CONTRACT,
    "RemoteSongSortTest": BEHAVIOR_CONTRACT,
    "AutoSyncDurationFilterTest": BEHAVIOR_CONTRACT,
    "PendingMediaSelectionTest": BEHAVIOR_CONTRACT,
    "R128LoudnessAnalyzerTest": BEHAVIOR_CONTRACT,
    "SpatialAudioStateTest": BEHAVIOR_CONTRACT,
    "AppLetterSealImporterTest": BEHAVIOR_CONTRACT,
    "LibraryAnalyzerTest": BEHAVIOR_CONTRACT,
    "LibraryFolderStoreTest": BEHAVIOR_CONTRACT,
    "LyricsSlotsTest": BEHAVIOR_CONTRACT,
    "MicaRoutingDataSourceTest": BEHAVIOR_CONTRACT,
    "MicaSmbRoutingDataSourceTest": BEHAVIOR_CONTRACT,
    "PlaybackOutputStatusMonitorTest": BEHAVIOR_CONTRACT,
    "PlaybackSongResolverTest": BEHAVIOR_CONTRACT,
    "SongIdentityTest": BEHAVIOR_CONTRACT,
    "SpectrumAnalysisOwnerLifecycleTest": BEHAVIOR_CONTRACT,
    "SoundFxEngineTest": BEHAVIOR_CONTRACT,
    "AudioMathTest": BEHAVIOR_CONTRACT,

    "PlaybackStackLifecycleOwnerTest": ARCHITECTURE_CONTRACT,
    "MicaAppTest": ARCHITECTURE_CONTRACT,

    "LibraryScaleTest": IMPLEMENTATION_VERIFICATION,
    "DsdDecimationAudioProcessorTest": IMPLEMENTATION_VERIFICATION,
    "MicaFloatDspAudioSinkTest": IMPLEMENTATION_VERIFICATION,
    "PcmDeliveryProbeTest": IMPLEMENTATION_VERIFICATION,
    "RendererSupportProbeDiagnosticsTest": IMPLEMENTATION_VERIFICATION,
    "SpectrumAudioProcessorTest": IMPLEMENTATION_VERIFICATION,
    "SafShadowRelationRematcherTest": IMPLEMENTATION_VERIFICATION,
    "CoverDecodeTargetTest": IMPLEMENTATION_VERIFICATION,
    "NotificationLyricsBoundaryPlannerTest": IMPLEMENTATION_VERIFICATION,
    "SpectrumAnalysisEngineTest": IMPLEMENTATION_VERIFICATION,
    "PlaybackQueueMirrorTest": IMPLEMENTATION_VERIFICATION,
    "MainActivityRobolectricTest": IMPLEMENTATION_VERIFICATION,
    "LyricsDocumentMemoryCacheTest": IMPLEMENTATION_VERIFICATION,
    "AudioOffloadCircuitBreakerTest": IMPLEMENTATION_VERIFICATION,
    "MicaAudioProcessorChainTest": IMPLEMENTATION_VERIFICATION,
    "AudioOutputPathConfigTest": IMPLEMENTATION_VERIFICATION,
    "DesktopLyricsOverlayStateStoreTest": IMPLEMENTATION_VERIFICATION,
    "PendingPlaybackNavigationTest": IMPLEMENTATION_VERIFICATION,
    "PlaybackQueueNavigationTest": IMPLEMENTATION_VERIFICATION,
    "AlbumArtRepairCoordinatorTest": IMPLEMENTATION_VERIFICATION,
    "PlaybackCoverColorResolverTest": IMPLEMENTATION_VERIFICATION,
    "FloatDspSpectrumTapDecouplingTest": IMPLEMENTATION_VERIFICATION,
    "SpectrumPcmTimelineTest": IMPLEMENTATION_VERIFICATION,
    "PendingSeekClearTest": IMPLEMENTATION_VERIFICATION,
    "MainAppSurfaceScreenshotTest": IMPLEMENTATION_VERIFICATION,
    "LyricsRenderStateTest": IMPLEMENTATION_VERIFICATION,
    "PersistentSortKeyCacheTest": IMPLEMENTATION_VERIFICATION,
    "DeviceRetryObservationResolverTest": IMPLEMENTATION_VERIFICATION,
    "MicaExtractorsFactoryTest": IMPLEMENTATION_VERIFICATION,
    "PipelineFormatTraceAudioProcessorTest": IMPLEMENTATION_VERIFICATION,
    "ReplayGainStateOwnerTest": IMPLEMENTATION_VERIFICATION,
    "SpectrumAnalyzerStateOwnerTest": IMPLEMENTATION_VERIFICATION,
    "SpectrumPcmPipelineDiagnosticsTest": IMPLEMENTATION_VERIFICATION,
    "CoverLoadCoordinatorTest": IMPLEMENTATION_VERIFICATION,
    "StandardCoverRequestSpecTest": IMPLEMENTATION_VERIFICATION,
    "DiagnosticLogSessionRotationTest": IMPLEMENTATION_VERIFICATION,
    "SpectrumClockAudioSinkTest": IMPLEMENTATION_VERIFICATION,
    "SpectrumResumeRegressionTest": IMPLEMENTATION_VERIFICATION,
    "UsbDsdRendererSelectionTest": IMPLEMENTATION_VERIFICATION,
    "DeviceShadowCanonicalMixedGateTest": IMPLEMENTATION_VERIFICATION,
    "MusicVideoMediaSourceFactoryTest": IMPLEMENTATION_VERIFICATION,
    "PlaybackRouteMonitorTest": IMPLEMENTATION_VERIFICATION,
    "PlayerLowerBackgroundModeTest": IMPLEMENTATION_VERIFICATION,
    "SharedPcmPipelineDiagnosticsTest": IMPLEMENTATION_VERIFICATION,
    "SpectrumMedia3ClockIntegrationTest": IMPLEMENTATION_VERIFICATION,
    "AudioPipelineCoordinatorTest": IMPLEMENTATION_VERIFICATION,
}

REVIEWED_SUITE_DEFAULTS = {
    "LibraryBrowseTest": BEHAVIOR_CONTRACT,
    "MusicLibraryTest": BEHAVIOR_CONTRACT,
    "PlaybackStatisticsTrackerTest": BEHAVIOR_CONTRACT,
    "MicaMediaServiceUsbOperationTest": BEHAVIOR_CONTRACT,
    "ServicePlaybackEngineCoordinatorTest": IMPLEMENTATION_VERIFICATION,
    "EqDspTest": BEHAVIOR_CONTRACT,
    "MicaCompositePlayerCommandRoutingTest": IMPLEMENTATION_VERIFICATION,
    "LibraryFollowupConsumerTest": BEHAVIOR_CONTRACT,
    "MicaSessionPresentationPlayerTest": IMPLEMENTATION_VERIFICATION,
    "DeviceGenerationCheckpointCodecTest": BEHAVIOR_CONTRACT,
    "MusicVideoPreferenceOwnerTest": BEHAVIOR_CONTRACT,
}

REVIEWED_TEST_CATEGORIES = {
    ("LibraryBrowseTest", "browseGroupPresentationPrecomputesFastScrollIndexWhenAlphabetical"): IMPLEMENTATION_VERIFICATION,
    ("LibraryBrowseTest", "browseGroupPresentationSkipsFastScrollIndexForCountSorts"): IMPLEMENTATION_VERIFICATION,

    ("MusicLibraryTest", "artworkCacheRepairStartsForcedArtworkOnlyScanWhenCachedArtNeedsRepair"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "changedBrowseSortRewritesTheReadyPresentationSnapshot"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "coldAlbumRestorePreparesOnlyTheAlbumBrowseGroups"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "coldCacheHydratesBrowseGroupsWithoutRebuildingThem"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "coldSongRestoreDoesNotPrepareUnrelatedBrowseGroups"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "coverColorRepairWritesFallbackArtworkSongAndSkipsUsableColor"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "lyricsAreLoadedOnceAndThenServedFromTheBoundedCache"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "sameIdsWithChangedMetadataAdvanceQueueRevisionAndInvalidateBrowseCache"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "staleArtistRulesRejectPersistedBrowseGroupsAndRebuildOnce"): IMPLEMENTATION_VERIFICATION,
    ("MusicLibraryTest", "successfulScanUsesCacheAndPublishesSyncResult"): IMPLEMENTATION_VERIFICATION,

    ("PlaybackStatisticsTrackerTest", "explicitTransitionConsumesRequestBeforePhysicalSelectionConfirmation"): IMPLEMENTATION_VERIFICATION,

    ("MicaMediaServiceUsbOperationTest", "serviceDoesNotOwnUsbAndroidRuntimeInternals"): ARCHITECTURE_CONTRACT,

    ("ServicePlaybackEngineCoordinatorTest", "decodeFailureInvokesCallbackAndAutoSkipsWhenPossible"): BEHAVIOR_CONTRACT,
    ("ServicePlaybackEngineCoordinatorTest", "dffSelectionStopsOldPlaybackAndCannotRestartIt"): BEHAVIOR_CONTRACT,
    ("ServicePlaybackEngineCoordinatorTest", "indexedSelectionPreservesPausedIntentDuringRestore"): BEHAVIOR_CONTRACT,
    ("ServicePlaybackEngineCoordinatorTest", "musicVideoErrorRebuildsCurrentItemAsAudioOnlyOnceAtSamePosition"): BEHAVIOR_CONTRACT,
    ("ServicePlaybackEngineCoordinatorTest", "skipToNextUsesAdjacentPlaylistItem"): BEHAVIOR_CONTRACT,
    ("ServicePlaybackEngineCoordinatorTest", "skipToPreviousSeeksToStartWhenPastThreshold"): BEHAVIOR_CONTRACT,
    ("ServicePlaybackEngineCoordinatorTest", "usbOutputFailureStopsOnCurrentItemInsteadOfAutoSkipping"): BEHAVIOR_CONTRACT,

    ("EqDspTest", "adjacentUiBandsCollapseIntoOneAndroidBand"): IMPLEMENTATION_VERIFICATION,
    ("EqDspTest", "singleUiBandContributesHalfOfItsPairLevel"): IMPLEMENTATION_VERIFICATION,

    ("MicaCompositePlayerCommandRoutingTest", "playPauseCommandsPublishSemanticIntentEvenWhenUnderlyingStateDoesNotChange"): BEHAVIOR_CONTRACT,
    ("MicaCompositePlayerCommandRoutingTest", "rapidIndexedSelectionsPreserveOrder"): BEHAVIOR_CONTRACT,
    ("MicaCompositePlayerCommandRoutingTest", "replayGainMultipliesRequestedVolume"): BEHAVIOR_CONTRACT,
    ("MicaCompositePlayerCommandRoutingTest", "technicalPipelineFlushDoesNotRewriteSemanticPlayIntent"): BEHAVIOR_CONTRACT,
    ("MicaCompositePlayerCommandRoutingTest", "deferredPlayPausePublishesIntentWithoutTouchingPhysicalPlayer"): ARCHITECTURE_CONTRACT,
    ("MicaCompositePlayerCommandRoutingTest", "retiredPlayerDropsLateMediaSessionMutationsBeforeTheyReachReleasedExo"): ARCHITECTURE_CONTRACT,
    ("MicaCompositePlayerCommandRoutingTest", "trackSelectionCommandsRouteThroughCoordinator"): ARCHITECTURE_CONTRACT,

    ("LibraryFollowupConsumerTest", "boundedDrainResumePreventsUnacknowledgedPrefixStarvation"): IMPLEMENTATION_VERIFICATION,
    ("LibraryFollowupConsumerTest", "drainConsumesMultipleKeysetPagesInStableOrder"): IMPLEMENTATION_VERIFICATION,
    ("LibraryFollowupConsumerTest", "drainYieldsAtFixedBudgetAndContinuesOnNextInvocation"): IMPLEMENTATION_VERIFICATION,
    ("LibraryFollowupConsumerTest", "tenKDrainToTailKeepsEveryBatchBoundedWithoutAnotherLibraryEvent"): IMPLEMENTATION_VERIFICATION,

    ("MicaSessionPresentationPlayerTest", "lyricPresentationChangesSessionViewWithoutMutatingSourceQueue"): ARCHITECTURE_CONTRACT,
    ("MicaSessionPresentationPlayerTest", "transportCommandsRemainOwnedByWrappedPlayer"): ARCHITECTURE_CONTRACT,

    ("DeviceGenerationCheckpointCodecTest", "replacementMutationReplacesPerVolumeAnchorAndDeletesStaleVolumes"): IMPLEMENTATION_VERIFICATION,
    ("MusicVideoPreferenceOwnerTest", "settingChangeRewritesOnlyNonCurrentAndTransitionRefreshesOldItem"): IMPLEMENTATION_VERIFICATION,
}

AUTO_SYNC_SUITE_DEFAULTS = {
    "SafAutoProbePlannerTest": IMPLEMENTATION_VERIFICATION,
    "DeviceAutoProbePlannerTest": IMPLEMENTATION_VERIFICATION,
    "SafUnknownFingerprintDebtPlannerTest": IMPLEMENTATION_VERIFICATION,
    "SafShadowRetryPlannerTest": IMPLEMENTATION_VERIFICATION,
    "DeviceShadowRetryPlannerTest": IMPLEMENTATION_VERIFICATION,
    "AutoSyncWakePolicyTest": IMPLEMENTATION_VERIFICATION,
    "SafMassDeletionConfirmationPlannerTest": IMPLEMENTATION_VERIFICATION,
    "SafFastVerifyPlannerTest": IMPLEMENTATION_VERIFICATION,
    "DeviceDeltaCandidatePlannerTest": IMPLEMENTATION_VERIFICATION,
    "DeviceDeltaFolderCasingPlannerTest": IMPLEMENTATION_VERIFICATION,
    "DeviceLyricsSidecarDiffPlannerTest": BEHAVIOR_CONTRACT,
    "SafAutoSyncPublicationPlannerTest": BEHAVIOR_CONTRACT,
    "DeviceAutoSyncPublicationPlannerTest": BEHAVIOR_CONTRACT,
    "LibraryMembershipDecisionPolicyTest": BEHAVIOR_CONTRACT,
    "LibraryScanSideEffectPolicyTest": ARCHITECTURE_CONTRACT,
}

AUTO_SYNC_TEST_CATEGORIES = {
    ("SafAutoProbePlannerTest", "currentPlaybackObjectIsDeferredButOtherChangedObjectRemainsReady"): BEHAVIOR_CONTRACT,
    ("SafAutoProbePlannerTest", "serializedProviderDefersAllHeavyProbeWhilePlaybackInstanceExists"): BEHAVIOR_CONTRACT,
    ("DeviceAutoProbePlannerTest", "currentPlaybackAudioCandidateDefersHeavyProbeWhilePausedOrBufferingLeaseExists"): BEHAVIOR_CONTRACT,
    ("DeviceAutoProbePlannerTest", "serializedSourceDefersEveryHeavyProbeDuringActivePlayback"): BEHAVIOR_CONTRACT,

    ("SafAutoSyncPublicationPlannerTest", "heavyBudgetDebtBlocksCheckpointEvenWithoutVisibleMutation"): IMPLEMENTATION_VERIFICATION,

    ("SafMassDeletionConfirmationPlannerTest", "debtFromAnotherActivationEpochIsNotReusedAsProgress"): BEHAVIOR_CONTRACT,
    ("SafMassDeletionConfirmationPlannerTest", "partialDiscoveryPreservesExistingConfirmationDebtExactly"): BEHAVIOR_CONTRACT,
    ("SafMassDeletionConfirmationPlannerTest", "partialDiscoveryRevokesProofForExplicitlyPresentObjectsAndResetsCursor"): BEHAVIOR_CONTRACT,
    ("SafMassDeletionConfirmationPlannerTest", "finalBatchOpensOnlyWhenAccumulatedProofCoversEntireRemovalSet"): BEHAVIOR_CONTRACT,
    ("SafMassDeletionConfirmationPlannerTest", "failedBatchClearsAccumulatedConfirmedMissingProof"): BEHAVIOR_CONTRACT,
    ("SafMassDeletionConfirmationPlannerTest", "changedRemovalFingerprintResetsCursorAndAttempt"): BEHAVIOR_CONTRACT,

    ("SafFastVerifyPlannerTest", "unreliableFingerprintRequiresVerifyInsteadOfPretendingUnchanged"): BEHAVIOR_CONTRACT,
    ("SafFastVerifyPlannerTest", "excludedProviderObjectIsObservedButNeverBecomesAddedWork"): BEHAVIOR_CONTRACT,
    ("SafFastVerifyPlannerTest", "removalRequiresCompleteSafTreeInventory"): BEHAVIOR_CONTRACT,

    ("DeviceDeltaFolderCasingPlannerTest", "distinctPhysicalDirectoriesWithSameCaseFoldAreNeverMerged"): BEHAVIOR_CONTRACT,
    ("DeviceDeltaFolderCasingPlannerTest", "existingObjectOldCasingIsReplacedNotDoubleCounted"): BEHAVIOR_CONTRACT,
}


# Review pass 2: correct broad heuristic over-promotion in scanner/UI/cache
# areas and promote explicit authority/commit boundaries to architecture.
REVIEWED_SUITE_CATEGORIES.update({
    "AlbumArtCacheTest": IMPLEMENTATION_VERIFICATION,
    "LandscapePlayerPolicyTest": IMPLEMENTATION_VERIFICATION,
    "CoverColorRepairTest": IMPLEMENTATION_VERIFICATION,
    "DeviceMediaStoreGenerationStateTest": IMPLEMENTATION_VERIFICATION,
    "LyricsScanBatchTest": IMPLEMENTATION_VERIFICATION,
    "AudioProbeBytesTest": IMPLEMENTATION_VERIFICATION,
    "ProbeResultTest": IMPLEMENTATION_VERIFICATION,
    "CoverColorExtractorTest": IMPLEMENTATION_VERIFICATION,
    "RemoteArtworkByteCacheTest": IMPLEMENTATION_VERIFICATION,
    "DiagnosticDetailPolicyTest": IMPLEMENTATION_VERIFICATION,
    "UsbSharedQuiescencePolicyResolverTest": IMPLEMENTATION_VERIFICATION,
    "NotificationLyricsSongCacheTest": IMPLEMENTATION_VERIFICATION,
    "ExternalLyricsProjectionTest": IMPLEMENTATION_VERIFICATION,
    "UsbSharedReturnCapabilityStoreTest": IMPLEMENTATION_VERIFICATION,
})

# Discovery patch contains both safety semantics and current publication recipes.
REVIEWED_SUITE_DEFAULTS.update({
    "DiscoveryPatchModelsTest": BEHAVIOR_CONTRACT,
    "DeviceMediaStoreDeltaReaderTest": BEHAVIOR_CONTRACT,
    "FolderScannerMissingFailureTest": BEHAVIOR_CONTRACT,
    "UsbOutputStateMachineTest": IMPLEMENTATION_VERIFICATION,
})

REVIEWED_TEST_CATEGORIES.update({
    # Discovery: publication recipe/query-window details are replaceable.
    ("DiscoveryPatchModelsTest", "anyVisibleAutoDeltaRequiresSnapshotPublication"): IMPLEMENTATION_VERIFICATION,
    ("DiscoveryPatchModelsTest", "emptyAutoDeltaUsesCheckpointOnlyPath"): IMPLEMENTATION_VERIFICATION,
    ("DeviceMediaStoreDeltaReaderTest", "unchangedVolumeGenerationSkipsAllChannelQueriesButRemainsComplete"): IMPLEMENTATION_VERIFICATION,
    ("DeviceMediaStoreDeltaReaderTest", "windowsUsePerVolumeExclusiveInclusiveGenerationBounds"): IMPLEMENTATION_VERIFICATION,

    # Missing classification is safety; worker counts/budgets/serialization are mechanism.
    ("FolderScannerMissingFailureTest", "builtInExternalStorageUsesFourMetadataQueryWorkers"): IMPLEMENTATION_VERIFICATION,
    ("FolderScannerMissingFailureTest", "massDeletionVerificationDefaultBudgetIsBounded"): IMPLEMENTATION_VERIFICATION,
    ("FolderScannerMissingFailureTest", "tenKMassDeletionVerificationStopsAtSingleBatchBudgetAndReturnsCursor"): IMPLEMENTATION_VERIFICATION,
    ("FolderScannerMissingFailureTest", "thirdPartyProviderMetadataQueriesStaySerialized"): IMPLEMENTATION_VERIFICATION,

    # Cache mechanics remain replaceable; stale/corrupt data must not publish.
    ("CoverColorRepairTest", "extractCannotPersistAfterArtworkUriChangedOrSongRemoved"): BEHAVIOR_CONTRACT,
    ("CoverColorRepairTest", "staleExtractCannotPersistAfterLibraryRowAlreadyHasUsableColor"): BEHAVIOR_CONTRACT,
    ("LyricsScanBatchTest", "emptyCompletedLyricsProbeIsForwardedToClearOldPayloads"): BEHAVIOR_CONTRACT,
    ("LyricsScanBatchTest", "readFailedSongIsCountedButNeverWritten"): BEHAVIOR_CONTRACT,
    ("RemoteArtworkByteCacheTest", "credential config or catalog revision change cannot reuse stale bytes"): BEHAVIOR_CONTRACT,
    ("NotificationLyricsSongCacheTest", "reloadsLyricsWhenDataVersionChangesForSameRevision"): BEHAVIOR_CONTRACT,
    ("NotificationLyricsSongCacheTest", "reloadsLyricsWhenRevisionChangesForSameSong"): BEHAVIOR_CONTRACT,
    ("NotificationLyricsSongCacheTest", "reportsLoadFailureInsteadOfTreatingItAsAbsentLyrics"): BEHAVIOR_CONTRACT,

    # USB state machine itself can be replaced; stale-event and playback-intent fencing cannot regress.
    ("UsbOutputStateMachineTest", "stalePermissionCallbackCannotAdvanceNewGeneration"): BEHAVIOR_CONTRACT,
    ("UsbOutputStateMachineTest", "nativeDuringSharedRouteWaitCancelsLateSharedBuild"): BEHAVIOR_CONTRACT,
    ("UsbOutputStateMachineTest", "frozenPlaybackIntentRestoresOnlyWhenOpeningBecomesActive"): BEHAVIOR_CONTRACT,
    ("UsbOutputStateMachineTest", "attachDoesNotRestartAnExclusiveSessionThatAlreadyOwnsItsTarget"): BEHAVIOR_CONTRACT,

    # Library operation commit boundaries are architecture, not ordinary behavior.
    ("LibraryOperationExecutorTest", "deviceFullAnchorIsDurableBeforeInMemoryCursorAdopt"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "cancellationAfterFinalValidationCompletesRoomAndMemoryPublication"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "releaseAfterFullStoreCommitCannotSplitRoomAndMemoryPublication"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "clearRequestedDuringFinalCommitRunsAfterAtomicPublicationAndWins"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "cancellationAfterCheckpointFinalValidationCompletesDurableMutation"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "newOperationWaitsBehindCheckpointFinalCommit"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "scheduledDeviceAutoPublishesValidatedObjectAndCheckpointTogether"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "s4ScheduledSafAutoPublishesValidatedObjectAndCheckpointTogether"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "deviceReadinessCommitsStoreAndMemoryBeforeAcceptingGenerationCursor"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "deviceReadinessRetryableFailureCommitsCheckpointAndDebtBeforeCursorAdvance"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "safReadinessBridgeStagesLyricsThenPublishesVisiblePlan"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "autoPublishConfirmedMissingCommitsSnapshotCheckpointAndPlaylistFollowupTogether"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "cancellationAfterAutoStoreCommitStillCompletesMemoryAdopt"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "releaseAfterAutoStoreCommitStillCompletesMemoryAdoptBeforeReleaseCleanup"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "visibleAutoPublicationAllocatesRevisionOnlyAfterSuccessfulStoreCommit"): ARCHITECTURE_CONTRACT,
    ("LibraryOperationExecutorTest", "successfulFolderSourceSwitchPromotesStagingAndBindingAtomically"): ARCHITECTURE_CONTRACT,

    # Repository transaction/commit boundaries are architecture contracts.
    ("LibraryRepositoryTest", "checkpointAndRetryCommitAtomically"): ARCHITECTURE_CONTRACT,
    ("LibraryRepositoryTest", "retryInsertFailureRollsBackCheckpointAdvance"): ARCHITECTURE_CONTRACT,
    ("LibraryRepositoryTest", "autoSnapshotCheckpointAndFollowupRollbackTogetherWhenFollowupInsertFails"): ARCHITECTURE_CONTRACT,
    ("LibraryRepositoryTest", "autoSnapshotPromotesStagedLyricsWithCheckpointAndRetryInSameCommit"): ARCHITECTURE_CONTRACT,
    ("LibraryRepositoryTest", "autoSnapshotFailureRollsBackStagedLyricsCheckpointRetryAndSnapshotTogether"): ARCHITECTURE_CONTRACT,
    ("LibraryRepositoryTest", "followupOutboxRollsBackWhenSnapshotMutationFails"): ARCHITECTURE_CONTRACT,
    ("LibraryRepositoryTest", "userExclusionRollsBackWhenSnapshotMutationFails"): ARCHITECTURE_CONTRACT,
    ("LibraryRepositoryTest", "daoReplaceAllIsAtomicFromCallerPerspective"): ARCHITECTURE_CONTRACT,

    # Followup publication must cross persistence->memory as one commit boundary.
    ("PlaylistStoreFollowupPublicationTest", "cancellationAfterFollowupCommitCannotSkipMemoryAdoption"): ARCHITECTURE_CONTRACT,
    ("PlaylistStoreFollowupPublicationTest", "cancellationAfterOrdinaryMutationCommitCannotSkipMemoryAdoption"): ARCHITECTURE_CONTRACT,
    ("PlaylistStoreFollowupPublicationTest", "cancellationWhileWaitingForPublicationGateLeavesSecondEventAndPlaylistIntact"): ARCHITECTURE_CONTRACT,
    ("PlaylistStoreFollowupPublicationTest", "userEditWaitsForFollowupAdoptionAndSurvivesDuplicateReplay"): ARCHITECTURE_CONTRACT,

    # Publication plan composition is an explicit commit-boundary decision.
    ("SafAutoSyncPublicationPlannerTest", "validatedChangedObjectPublishesSongLyricsAndCheckpointTogether"): ARCHITECTURE_CONTRACT,
    ("DeviceAutoSyncPublicationPlannerTest", "validatedHeavyAndSidecarResultsSplitLyricsAndCheckpointTogether"): ARCHITECTURE_CONTRACT,
    ("DeviceAutoSyncPublicationPlannerTest", "retryableProbeFailureCanAdvanceWhenDebtIsPersistedAtomically"): ARCHITECTURE_CONTRACT,
})


# Review pass 3: USB authority vs replaceable transport staging.
REVIEWED_SUITE_CATEGORIES.update({
    "UsbHybridSessionOwnerTest": ARCHITECTURE_CONTRACT,
    "DsdPrerollGateTest": IMPLEMENTATION_VERIFICATION,
})


# Review pass 4: persistence preferences, remote transport/cache boundaries, and
# USB protocol-vs-state-machine separation. Stable persisted user choices and
# migration/default semantics are Behavior Contracts; rendering/wiring recipes
# remain Implementation Verification.
REVIEWED_SUITE_CATEGORIES.update({
    "AppearancePreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "AudioOffloadPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "DetailedDiagnosticsPreferencesTest": BEHAVIOR_CONTRACT,
    "LibraryAutoSyncPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "LibraryBrowsePreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "LibraryScanPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "LibraryZoomPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "LyricsPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "PlaybackUiPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "PreferencesFallbackRobolectricTest": BEHAVIOR_CONTRACT,
    "PreferencesStorageRobolectricTest": BEHAVIOR_CONTRACT,
    "RemoteAutoSyncPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "RemoteSongSortPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "ReplayGainPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "SleepTimerPreferencesRobolectricTest": BEHAVIOR_CONTRACT,
    "UsbSharedReturnCapabilityStoreTest": BEHAVIOR_CONTRACT,

    # Despite its historical name this suite mostly asserts externally visible
    # format/error behavior, not dependency direction or authority ownership.
    "PlaybackArchitectureTest": BEHAVIOR_CONTRACT,

    # Read-ahead/windowing is replaceable transport implementation.
    "SeekableByteSourceTest": IMPLEMENTATION_VERIFICATION,

    # Internal compatibility tables/candidate inference can be replaced while
    # retaining the same USB user-facing capability behavior.
    "UsbDacQuirksTest": IMPLEMENTATION_VERIFICATION,
    "NativeDsdCandidateTest": IMPLEMENTATION_VERIFICATION,
    "UsbStreamTransitionTest": IMPLEMENTATION_VERIFICATION,
})

REVIEWED_TEST_CATEGORIES.update({
    # Preference rendering/wiring details are not persistence contracts.
    ("LyricsPreferencesRobolectricTest", "lyricsWordAnimationPresetsMapToDistinctRenderingParameters"): IMPLEMENTATION_VERIFICATION,
    ("LyricsPreferencesRobolectricTest", "externalLyricsEffectChangesInvalidateTheLiveDisplay"): IMPLEMENTATION_VERIFICATION,

    # Internal request-id/revision recipe is replaceable.
    ("PlaybackArchitectureTest", "requestIdsIncreaseAndRevisionTracksSourceChanges"): IMPLEMENTATION_VERIFICATION,

    # WebDAV: keep data/protocol/safety semantics durable; cache/probe strategy is replaceable.
    ("WebDavCatalogAdapterTest", "artworkResolverAndLoaderFetchCanonicalSidecarJustInTime"): IMPLEMENTATION_VERIFICATION,
    ("WebDavCatalogAdapterTest", "embeddedArtworkHintPublishesTrackScopedArtworkAndProbeRevisionIsReusable"): IMPLEMENTATION_VERIFICATION,
    ("WebDavCatalogAdapterTest", "sidecarArtworkIsDiscoveredWithoutDownloadingImageBytes"): IMPLEMENTATION_VERIFICATION,
    ("WebDavCatalogAdapterTest", "unchangedEtagReusesMetadataWithoutGetAndChangedEtagReprobes"): IMPLEMENTATION_VERIFICATION,
    ("WebDavCatalogAdapterTest", "xmlBody400FallsBackOnceToEmptyBodyAndStillUsesSardineParser"): IMPLEMENTATION_VERIFICATION,

    # SMB catalog: publication safety/identity remain behavior; probing/cache/parallelism are mechanism.
    ("SmbSourceSyncTest", "sidecarArtworkIsDiscoveredWithoutReadingImageAndRefreshesAcrossMetadataReuse"): IMPLEMENTATION_VERIFICATION,
    ("SmbSourceSyncTest", "embeddedArtworkHintPublishesTrackScopedArtworkAndProbeRevisionIsReusable"): IMPLEMENTATION_VERIFICATION,
    ("SmbSourceSyncTest", "metadataProbeEnrichesBrowseFieldsAndClosesRandomAccessFile"): IMPLEMENTATION_VERIFICATION,
    ("SmbSourceSyncTest", "metadataProbeFailureFallsBackWithoutMarkingProbeRevisionAndRetriesNextSync"): IMPLEMENTATION_VERIFICATION,
    ("SmbSourceSyncTest", "olderMetadataProbeRevisionForcesOneTimeReprobeEvenWhenContentIsUnchanged"): IMPLEMENTATION_VERIFICATION,
    ("SmbSourceSyncTest", "unchangedContentRevisionReusesMetadataWithoutOpeningFileAndChangedRevisionReprobes"): IMPLEMENTATION_VERIFICATION,
    ("SmbSourceSyncTest", "metadataProbesUseBoundedParallelismAndPreserveCatalogOrder"): IMPLEMENTATION_VERIFICATION,

    # Pagination page-size choice and SMB read-ahead windows are replaceable.
    ("NavidromeCatalogPagerTest", "limit bounds search page size and avoids unnecessary fallback"): IMPLEMENTATION_VERIFICATION,
    ("SmbPlaybackTransportTest", "tinyReadsShareOneBoundedProtocolReadAheadWindow"): IMPLEMENTATION_VERIFICATION,
    ("SmbPlaybackTransportTest", "readAheadRefillsAtNextSequentialProtocolOffset"): IMPLEMENTATION_VERIFICATION,

    # USB stream-transition implementation is replaceable, but cross-session/device
    # stale authority and gain-safety guarantees remain durable behavior.
    ("UsbStreamTransitionTest", "doesNotPublishInactiveStateForAnUncommittedReplacementFailure"): BEHAVIOR_CONTRACT,
    ("UsbStreamTransitionTest", "limitsVolumeRiseSpeedButKeepsReductionFast"): BEHAVIOR_CONTRACT,
    ("UsbStreamTransitionTest", "preservesTrustedHardwareTargetOnlyForTheSameDeviceAndProtocol"): BEHAVIOR_CONTRACT,
    ("UsbStreamTransitionTest", "frozenPcmCompensationCanOnlyReduceTotalOutput"): BEHAVIOR_CONTRACT,
    ("UsbStreamTransitionTest", "staleOrDsdVerificationCannotMutateTheNewPcmSession"): BEHAVIOR_CONTRACT,
    ("UsbStreamTransitionTest", "frozenHardwareVolumeMustRecoverTrustedRegisterBeforeFurtherWrites"): BEHAVIOR_CONTRACT,

    # UsbVolumeProtocol mixes external protocol/safety behavior with the current
    # debounce/reader-recovery state machine. The latter may churn with a rewrite.
    ("UsbVolumeProtocolTest", "keepsLatestPendingTargetWhenPendingDoesNotLowerOutput"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "keepsOnlyTheLatestPendingAbsoluteTarget"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "replacesLatchedReductionWithAnEvenLowerTarget"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "acceptsAnIncreaseAfterTheLowerTargetBecomesRunning"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "acceptsLatestTargetAcrossSessionOrModeChanges"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "comparesTotalEffectiveDsdOutputWhenCoalescingTargets"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "waitsForIbassoSettleAndLatestPendingQuietWindow"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "skipsPendingDebounceOutsideAnActiveIbassoSequence"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "yieldsVerificationToAPendingRequestInsteadOfFreezing"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "waitsForPcmReaderRestartWithoutVerifyingOrFreezing"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "verifiesPcmAsSoonAsTheRestartedReaderIsReady"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "freezesPcmWhenReaderRecoveryExpiresOrBecomesWriteOnly"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "keepsDsdVerificationImmediateAndCancelsStaleSessions"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "skipsOnlyVerifiedDuplicateIbassoVolumeTargets"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "transitionsReaderFromRestartToWriteOnlyAfterTwoFailures"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "verifiedReadbackClearsPreviousReaderFailureBeforeNextFailure"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "restartsOnlyAfterTheFailedCurrentReaderThreadExits"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "ignoresIdleReaderTimeoutsWithoutPendingResponse"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "pendingReaderFailuresRestartThenBecomeWriteOnly"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "successfulReaderReadResetsPendingFailures"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "idleTimeoutResetsAnIncompletePendingFailureSequence"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "keepsWrittenRawOnlyInsideConfirmationWindow"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "readsInitialHardwareVolumeOnlyForNewReadableControl"): IMPLEMENTATION_VERIFICATION,
    ("UsbVolumeProtocolTest", "consumesOnlyTheLatestDebouncedVolumeEventOnce"): IMPLEMENTATION_VERIFICATION,

    # Cross-generation/device fencing is durable even if the reader implementation changes.
    ("UsbVolumeProtocolTest", "rejectsCallbacksFromSupersededReaderGenerations"): BEHAVIOR_CONTRACT,
    ("UsbVolumeProtocolTest", "resumesReaderFailureHealthOnlyForTheSameDevice"): BEHAVIOR_CONTRACT,
    ("UsbVolumeProtocolTest", "keepsTrustedIbassoTargetOnlyForSameDevice"): BEHAVIOR_CONTRACT,
    ("UsbVolumeProtocolTest", "selectsOnlyTrustedIbassoRollbackTargets"): BEHAVIOR_CONTRACT,

    # Session-owner epoch/generation mechanics are replaceable. Stale-operation
    # fencing is behavior; the single-owner responsibility itself is architecture.
    ("UsbHybridSessionOwnerTest", "failedStackRetirementMintsFenceButDoesNotRequestPermission"): IMPLEMENTATION_VERIFICATION,
    ("UsbHybridSessionOwnerTest", "retarget authorized target keeps epoch"): IMPLEMENTATION_VERIFICATION,
    ("UsbHybridSessionOwnerTest", "simultaneousRequestsPublishMonotonicEpochsToNativeAndFacts"): IMPLEMENTATION_VERIFICATION,
    ("UsbHybridSessionOwnerTest", "technicalRetireKeepsEpochAndAuthorizedTargetForFreshDsdSession"): IMPLEMENTATION_VERIFICATION,
    ("UsbHybridSessionOwnerTest", "unrelatedDetachOnlyAdvancesDiscoveryRevision"): IMPLEMENTATION_VERIFICATION,
    ("UsbHybridSessionOwnerTest", "nativePolicyPublishesPcmAsActualTransportWhenCurrentStreamIsPcm"): BEHAVIOR_CONTRACT,
    ("UsbHybridSessionOwnerTest", "oldPermissionResultCannotOpenAfterNewModeRequest"): BEHAVIOR_CONTRACT,
    ("UsbHybridSessionOwnerTest", "openThatFinishesAfterSupersedeIsClosedWithoutPublishingActiveFacts"): BEHAVIOR_CONTRACT,
    ("UsbHybridSessionOwnerTest", "permissionResultFromReplacedRuntimeTargetFailsClosed"): BEHAVIOR_CONTRACT,
    ("UsbHybridSessionOwnerTest", "staleTelemetrySampleCannotPublishAfterNewEpoch"): BEHAVIOR_CONTRACT,
})
# Review pass 5: promote stable queue/remote-source/concurrency obligations that
# were hidden behind Coordinator/Manager/Shadow class names. Keep event wiring,
# scheduling recipes and query optimizations as implementation verification.
REVIEWED_SUITE_CATEGORIES.update({
    "LibraryPlaybackQueueCoordinatorTest": BEHAVIOR_CONTRACT,
    "RemoteSourceManagerTest": BEHAVIOR_CONTRACT,
    "RemoteSourceOwnerTest": ARCHITECTURE_CONTRACT,
})

REVIEWED_SUITE_DEFAULTS.update({
    "DeviceAutoSyncShadowTest": IMPLEMENTATION_VERIFICATION,
    "PlaybackTimelineCoordinatorTest": IMPLEMENTATION_VERIFICATION,
    "MusicVideoOutputCoordinatorTest": IMPLEMENTATION_VERIFICATION,
})

REVIEWED_TEST_CATEGORIES.update({
    # Remote source scheduling freshness/catch-up is replaceable; credential,
    # source isolation and network-use safety remain behavior.
    ("RemoteSourceManagerTest", "automaticSyncSkipsFreshAndDisabledSourcesAndContinuesAfterOneFailure"): IMPLEMENTATION_VERIFICATION,
    ("RemoteSourceManagerTest", "sourceChangesRequestAutomaticCatchUpOnlyWhenEnabled"): IMPLEMENTATION_VERIFICATION,
    ("RemoteSourceManagerTest", "credentialRotationAllocatesNewRefBeforeSwitchingSourcePointer"): ARCHITECTURE_CONTRACT,

    # Auto-sync shadow implementation may change, but incomplete/contradictory
    # observations must never advance durable discovery authority.
    ("DeviceAutoSyncShadowTest", "baselineMissingCannotAdvanceThroughOrdinaryAccept"): BEHAVIOR_CONTRACT,
    ("DeviceAutoSyncShadowTest", "crossVolumeIdentityConflictNeverAdvancesCursor"): BEHAVIOR_CONTRACT,
    ("DeviceAutoSyncShadowTest", "partialChannelFailureNeverAdvancesCursor"): BEHAVIOR_CONTRACT,
    ("DeviceAutoSyncShadowTest", "persistedAnchorRestoreSeedsDeltaAfterProcessRecreationWithoutMovingLiveCursorBackward"): BEHAVIOR_CONTRACT,
    ("DeviceAutoSyncShadowTest", "providerVersionChangeDuringQueryRequestsReconcileWithoutAdvance"): BEHAVIOR_CONTRACT,
    ("DeviceAutoSyncShadowTest", "postQueryNewerGenerationSchedulesFollowupAndOnlyAdvancesToQueriedUpperBound"): BEHAVIOR_CONTRACT,

    # Playback timeline user-visible semantics survive a coordinator rewrite.
    ("PlaybackTimelineCoordinatorTest", "explicitDiscontinuityCanStillPublishBackwardPosition"): BEHAVIOR_CONTRACT,
    ("PlaybackTimelineCoordinatorTest", "presentationClockFreezesOnPauseAndResumesFromFrozenAnchor"): BEHAVIOR_CONTRACT,
    ("PlaybackTimelineCoordinatorTest", "repeatedPauseResumeDoesNotAccumulateReportedPositionLag"): BEHAVIOR_CONTRACT,
    ("PlaybackTimelineCoordinatorTest", "restoreAnchorSurvivesSyncUntilMatchingSongStarts"): BEHAVIOR_CONTRACT,
    ("PlaybackTimelineCoordinatorTest", "songChangeDropsPreviousPlayerDuration"): BEHAVIOR_CONTRACT,
    ("PlaybackTimelineCoordinatorTest", "staleRestoreAnchorIsClearedByDifferentSong"): BEHAVIOR_CONTRACT,

    # Video surface lease fencing is a concurrency contract; generation/frame
    # bookkeeping itself remains implementation verification.
    ("MusicVideoOutputCoordinatorTest", "controllerDisconnectDetachesOnlyItsOwnSurface"): BEHAVIOR_CONTRACT,
    ("MusicVideoOutputCoordinatorTest", "playbackStackRebuildReattachesOnlyCurrentControllerAndSong"): BEHAVIOR_CONTRACT,
    ("MusicVideoOutputCoordinatorTest", "staleDetachAndFirstFrameCannotAffectNewLease"): BEHAVIOR_CONTRACT,
})
# Review pass 6: follow-up/outbox and provider backoff boundaries. Diagnostics,
# batch compaction and retry cadence are implementation evidence; durable missing
# evidence, rollback/commit boundaries and stale-source fencing are contracts.
REVIEWED_SUITE_CATEGORIES.update({
    "StorageDiagnosticsTest": IMPLEMENTATION_VERIFICATION,
    "SafProviderDiscoveryBackoffTest": IMPLEMENTATION_VERIFICATION,
})

REVIEWED_SUITE_DEFAULTS.update({
    "PlaylistRepositoryFollowupTest": BEHAVIOR_CONTRACT,
    "SafAutoSyncTargetedHintTest": BEHAVIOR_CONTRACT,
})

REVIEWED_TEST_CATEGORIES.update({
    # Outbox batching/compaction is replaceable; delete+ack transactionality is architecture.
    ("PlaylistRepositoryFollowupTest", "appliedBacklogBatchCompactsTenThousandMemberPlaylistOncePerBatch"): IMPLEMENTATION_VERIFICATION,
    ("PlaylistRepositoryFollowupTest", "tenThousandRealOutboxEventsDrainWithOneSnapshotPerBoundedBatch"): IMPLEMENTATION_VERIFICATION,
    ("PlaylistRepositoryFollowupTest", "confirmedMissingPlaylistDeleteAndAckRollbackTogether"): ARCHITECTURE_CONTRACT,
    ("PlaylistRepositoryFollowupTest", "failureOnSecondAcknowledgementRollsBackEntireBatchAndCanRetry"): ARCHITECTURE_CONTRACT,

    # Provider retry cadence/backoff constants are mechanism. Scope isolation is durable safety.
    ("SafProviderDiscoveryBackoffTest", "sourceOrActivationScopeChangeDoesNotInheritOldProviderState"): BEHAVIOR_CONTRACT,

    # Targeted-path eligibility is implementation; authority containment is behavior.
    ("SafAutoSyncTargetedHintTest", "pureMediaStoreHintsAreEligible"): IMPLEMENTATION_VERIFICATION,
    ("SafAutoSyncTargetedHintTest", "mixedCauseIncompleteOrMissingHintsDisableTargetedPath"): IMPLEMENTATION_VERIFICATION,

    # Automatic-sync freshness scheduling is policy implementation; disabled-source
    # network blocking remains covered at the manager/resolver boundaries.
    ("RemoteAutoSyncTest", "freshOrDisabledSourcesDoNotNeedAutomaticSync"): IMPLEMENTATION_VERIFICATION,
    ("RemoteAutoSyncTest", "neverSyncedAndStaleEnabledSourcesNeedAutomaticSync"): IMPLEMENTATION_VERIFICATION,

    # SAF follow-up cause tagging is an internal scheduler recipe.
    ("LibraryFollowupProtocolTest", "safBudgetContinuationIsAnAutoSyncCause"): IMPLEMENTATION_VERIFICATION,
})
# Review pass 7: persisted UI state/accessibility and playback-model semantics.
# UI geometry remains implementation verification, but persisted state migrations,
# accessibility affordances and remote/local action safety are durable behavior.
REVIEWED_SUITE_CATEGORIES.update({
    "SettingsScanStateTest": BEHAVIOR_CONTRACT,
    "HomeUiStateTest": BEHAVIOR_CONTRACT,
    "BrowseGroupDisplaySheetTest": BEHAVIOR_CONTRACT,
    "SharpPlayPauseButtonTest": BEHAVIOR_CONTRACT,
    "SongActionMenuSheetTest": BEHAVIOR_CONTRACT,
    "PlaybackTuningCoordinatorTest": BEHAVIOR_CONTRACT,
})

REVIEWED_SUITE_DEFAULTS.update({
    "PlayerControllerQueueModelTest": IMPLEMENTATION_VERIFICATION,
})

REVIEWED_TEST_CATEGORIES.update({
    # Tutorial screenshots/layout are implementation, but persisted onboarding state must survive refactors.
    ("UsageTutorialTest", "interruptedTutorialResumesAndSkipPersists"): BEHAVIOR_CONTRACT,
    ("UsageTutorialTest", "scanInvitationAndAcceptedPageSurviveActivityStateRestoration"): BEHAVIOR_CONTRACT,
    ("UsageTutorialTest", "existingInstallationIsNotInterrupted"): BEHAVIOR_CONTRACT,
    ("UsageTutorialTest", "oldInstallAndExternalOpenDoNotShowScanInvitation"): BEHAVIOR_CONTRACT,

    # Queue-model facade ownership is architecture. User-visible queue concurrency,
    # shuffle preservation and metadata/remove semantics are behavior.
    ("PlayerControllerQueueModelTest", "playerControllerFacadeDoesNotOwnRuntimeInternals"): ARCHITECTURE_CONTRACT,
    ("PlayerControllerQueueModelTest", "appendSongsAppendsToLiveQueueWithoutReplacingConcurrentTail"): BEHAVIOR_CONTRACT,
    ("PlayerControllerQueueModelTest", "metadataOnlySetQueuePreservesHiddenShuffleOrder"): BEHAVIOR_CONTRACT,
    ("PlayerControllerQueueModelTest", "refreshQueueMetadataReplacesMatchingSongsInSpecialQueue"): BEHAVIOR_CONTRACT,
    ("PlayerControllerQueueModelTest", "removeSongByIdRemovesAllMatchingEntriesFromLiveQueue"): BEHAVIOR_CONTRACT,
    ("PlayerControllerQueueModelTest", "serviceQueueMirrorPrefersCompleteLibrarySong"): BEHAVIOR_CONTRACT,
    ("PlayerControllerQueueModelTest", "setQueueWithUnchangedPlaybackContentRefreshesQueueMetadata"): BEHAVIOR_CONTRACT,
})
def reviewed_category(suite: str, test: str):
    key = (suite, test)
    if key in AUTO_SYNC_TEST_CATEGORIES:
        return AUTO_SYNC_TEST_CATEGORIES[key], "manual-review:auto-sync semantic boundary"
    if key in REVIEWED_TEST_CATEGORIES:
        return REVIEWED_TEST_CATEGORIES[key], "manual-review:mixed-suite semantic boundary"
    if suite in AUTO_SYNC_SUITE_DEFAULTS:
        return AUTO_SYNC_SUITE_DEFAULTS[suite], "manual-review:auto-sync suite role"
    if suite in REVIEWED_SUITE_DEFAULTS:
        return REVIEWED_SUITE_DEFAULTS[suite], "manual-review:mixed-suite default"
    if suite in REVIEWED_SUITE_CATEGORIES:
        return REVIEWED_SUITE_CATEGORIES[suite], "manual-review:suite role"
    return None





# Review pass 5: residual high-confidence false promotions.
REVIEWED_SUITE_CATEGORIES.update({
    "ScanDirectoryCandidatesTest": IMPLEMENTATION_VERIFICATION,
    "StorageDiagnosticsTest": IMPLEMENTATION_VERIFICATION,
    "SafProviderDiscoveryBackoffTest": IMPLEMENTATION_VERIFICATION,
})

REVIEWED_TEST_CATEGORIES.update({
    # Scanner/query orchestration choices are replaceable; compatibility results remain contracts.
    ("MediaStoreScannerCompatibilityTest", "fullDiscoveryScanRunsFolderCasingReconciliation"): IMPLEMENTATION_VERIFICATION,
    ("MediaStoreScannerCompatibilityTest", "mediaStoreDurationClauseKeepsUnknownDurationForAppProbe"): IMPLEMENTATION_VERIFICATION,
    ("MediaStoreScannerCompatibilityTest", "targetedMetadataRefreshSkipsFolderCasingReconciliation"): IMPLEMENTATION_VERIFICATION,
    ("MediaStoreScannerCompatibilityTest", "targetedScopeSelectsOnlyRequestedObjectsFromTenThousandDrafts"): IMPLEMENTATION_VERIFICATION,
    ("MusicVideoMatcherTest", "tenThousandSongsAndVideosStayLinearAndUnderAllocationBudget"): IMPLEMENTATION_VERIFICATION,

    # SAF discovery cadence/backoff is an implementation policy. Scope isolation is a durable safety boundary.
    ("SafProviderDiscoveryBackoffTest", "sourceOrActivationScopeChangeDoesNotInheritOldProviderState"): BEHAVIOR_CONTRACT,

    # Remote sync must use a coherent credential/config snapshot and one publication authority.
    ("NavidromeSourceSyncTest", "wholeSyncUsesOneCredentialSnapshotAndPublishesOneSourceSnapshot"): ARCHITECTURE_CONTRACT,
})

# Review pass 6: distinguish externally stable behavior from internal cache/decoder strategy.
REVIEWED_SUITE_CATEGORIES.update({
    "NavidromeCatalogPagerTest": IMPLEMENTATION_VERIFICATION,
})

REVIEWED_TEST_CATEGORIES.update({
    # Library cache materialization is replaceable; persistence/data semantics remain contracts.
    ("LibraryRepositoryTest", "browseGroupsSurviveColdCacheRoundTrip"): IMPLEMENTATION_VERIFICATION,
    ("LibraryRepositoryTest", "browsePresentationOrderAndFastScrollIndexSurviveColdCacheRoundTrip"): IMPLEMENTATION_VERIFICATION,
    ("LibraryRepositoryTest", "cachedCatalogOmitsLyricsUntilRequested"): IMPLEMENTATION_VERIFICATION,
    ("LibraryRepositoryTest", "clearMakesCacheUnavailable"): IMPLEMENTATION_VERIFICATION,
    ("LibraryRepositoryTest", "songByIdLoadsSingleSongWithLyrics"): IMPLEMENTATION_VERIFICATION,

    # Product format support is durable, but the decoder/extractor provider is replaceable.
    ("PlaybackArchitectureTest", "alacIsSupportedViaFfmpeg"): IMPLEMENTATION_VERIFICATION,
    ("PlaybackArchitectureTest", "apeIsSupportedViaFfmpegForFileAndContentUris"): IMPLEMENTATION_VERIFICATION,
    ("PlaybackArchitectureTest", "commonFormatsAreSupportedByExo"): IMPLEMENTATION_VERIFICATION,
})

# Review pass 7: promote stable user-visible library/search/action semantics out of generic UI wiring.
REVIEWED_TEST_CATEGORIES.update({
    ("HomeScreenControllerTest", "createPlaylistEmptyNameFails"): BEHAVIOR_CONTRACT,
    ("HomeScreenControllerTest", "createPlaylistSuccess"): BEHAVIOR_CONTRACT,
    ("HomeScreenControllerTest", "deleteActivePlaylistNavigatesToSongs"): BEHAVIOR_CONTRACT,
    ("HomeScreenControllerTest", "deleteInactivePlaylistKeepsNavigation"): BEHAVIOR_CONTRACT,
    ("HomeScreenControllerTest", "playNextKeepsRemoteSongWithoutLocalLibraryLookup"): BEHAVIOR_CONTRACT,
    ("HomeScreenControllerTest", "removeFromPlaylistRequiresPlaylistContext"): BEHAVIOR_CONTRACT,

    ("HomeBrowseCatalogTest", "mergedBrowseSongsKeepsLocalFirstAndDeduplicatesStableIds"): BEHAVIOR_CONTRACT,
    ("HomeBrowseCatalogTest", "mergedBrowseSongsReturnsLocalListDirectlyWhenRemoteCatalogIsEmpty"): BEHAVIOR_CONTRACT,
    ("HomeBrowseCatalogTest", "remoteBrowseContentAllowsArtistAndAlbumRootsButNotFolders"): BEHAVIOR_CONTRACT,

    ("HomeRecentSongsTest", "appliesPresentationLimitAfterSorting"): BEHAVIOR_CONTRACT,
    ("HomeRecentSongsTest", "combinesLocalAndRemoteSongsByLastPlayedTime"): BEHAVIOR_CONTRACT,
    ("HomeRecentSongsTest", "duplicateIdsPreferFirstLocalProjection"): BEHAVIOR_CONTRACT,
    ("HomeRecentSongsTest", "zeroLimitReturnsEmptyList"): BEHAVIOR_CONTRACT,

    ("LibrarySearchPanelTest", "mergeSearchResultsKeepsLocalOrderThenRemoteAndDeduplicatesIds"): BEHAVIOR_CONTRACT,
    ("LibrarySearchPanelTest", "remoteSearchIndexMatchesTitleArtistAlbumAndFileName"): BEHAVIOR_CONTRACT,

    ("PlayerPlaybackControlsVisibilityTest", "defaultShowsAllFiveButtons"): BEHAVIOR_CONTRACT,
    ("PlayerPlaybackControlsVisibilityTest", "hidingButtonsRemovesOnlyThoseButtons"): BEHAVIOR_CONTRACT,
})

# Review pass 8: remote playlist persistence/commit boundaries and widget launch wiring.
REVIEWED_SUITE_CATEGORIES.update({
    "SmbPlaylistFlowTest": BEHAVIOR_CONTRACT,
    "WidgetPlayerLaunchTest": IMPLEMENTATION_VERIFICATION,
})

REVIEWED_TEST_CATEGORIES.update({
    # Playlist membership and remote descriptions must publish atomically.
    ("SmbPlaylistFlowTest", "failedPlaylistWriteRollsBackDescriptionsAsWell"): ARCHITECTURE_CONTRACT,
})
