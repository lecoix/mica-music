#!/usr/bin/env python3
"""Generate Mica's test taxonomy manifest.

Classification is intentionally conservative: a test is promoted to a long-lived
contract only when its name/domain clearly expresses product, persistence,
protocol, safety, or architecture semantics. Everything else remains an
implementation verification and may evolve with refactors.
"""
from __future__ import annotations
import csv
import pathlib
import re
import subprocess
from test_classification_reviewed import reviewed_category

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "testing" / "TEST_CLASSIFICATION.csv"
TEST_RE = re.compile(r"@Test\b(?:(?!@Test).){0,1200}?\bfun\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\s*\(", re.S)

ARCH_FILES = {
    "DataLayerDependencyStructureTest",
    "PlaybackStackArchitectureStructureTest",
    "PlaybackArchitectureTest",
    "PlaybackTestPackageStructureTest",
    "PlayerPageArchitectureStructureTest",
}
LIBRARY_CONTRACT_FILES = {
    "LibraryMembershipDecisionPolicyTest", "SafAutoProbePlannerTest",
    "SafAutoSyncPublicationPlannerTest", "DeviceAutoProbePlannerTest",
    "DeviceAutoSyncPublicationPlannerTest", "SafUnknownFingerprintDebtPlannerTest",
    "SafShadowRetryPlannerTest", "DeviceShadowRetryPlannerTest",
    "AutoSyncWakePolicyTest", "LibraryScanSideEffectPolicyTest",
    "SafMassDeletionConfirmationPlannerTest", "LibraryFollowupProtocolTest",
    "ObjectObservationValidationTest", "SafProviderDiscoveryBackoffTest",
    "SafAutoSyncTargetedHintTest", "SafMissingProofRestartTest",
}
LIBRARY_IMPL_WORDS = (
    "Executor", "Scheduler", "Observer", "Tracker", "Projector", "Loader",
    "Auditor", "ScaleGate", "ProbeExecutor", "CanonicalProjection",
)
SCANNER_IMPL_FILES = {"ScanProfilerTest", "DeviceAutoSyncShadowTest", "VideoCoverPosterPrefetcherTest"}
REMOTE_IMPL_FILES = {"RemoteSourceManagerTest", "RemoteSourceOwnerTest"}
DATA_IMPL_FILES = {"PlayerLowerLayoutConfigTest", "LibraryPresentationBuilderTest", "HiResBadgeStyleTest", "PlayerInfoSegmentsTest"}
PLAYBACK_IMPL_WORDS = ("Coordinator", "ControllerQueueModel", "MediaControllerQueueSync")
USB_BEHAVIOR_FILES = {
    "AndroidUsbIdentityProbeTest", "AndroidUsbPermissionLifetimeTest",
    "UsbOutputStateMachineTest", "UsbExactPcmPolicyTest",
    "UsbSharedQuiescencePolicyResolverTest", "UsbAudioTargetSelectorTest",
    "UsbStableIdentityDigestTest", "DsdPrerollGateTest",
}
USB_IMPL_FILES = {
    "UsbHybridPcmAudioSinkTest", "UsbHybridSessionOwnerTest",
    "UsbHybridSettingsPresentationTest", "UsbHybridDiagnosticsReportTest",
    "UsbOutputPresentationTest",
}
MEDIA_BEHAVIOR_FILES = {
    "ExternalLyricsProjectionTest", "NotificationLyricsTest",
    "NotificationLyricsSongCacheTest", "ExternalLyricsSessionCommandsTest",
    "ExternalMediaItemCodecTest", "SongMediaItemCodecTest",
    "TrustedMediaItemResolverTest", "PlaybackRouteInferenceTest",
    "PlaybackShuffleSessionCommandTest", "MicaMediaServiceNoisyReceiverTest",
    "ServicePlaybackStateStoreTest", "ServicePlaybackRequestStateTest",
    "PlaybackBoundarySessionEventTest", "LyriconLyricsMapperTest",
    "SystemMediaArtworkResolverTest",
}

# Mixed-suite promotion keywords: only semantics that should survive a rewrite.
EXECUTOR_CONTRACT = re.compile(
    r"clear|cancel|release|repopulate|resurrect|atomic|rollback|"
    r"latestResultWins|honorsPersistentUserExclusion|MassDeletion|"
    r"permissionLoss|sourceSwitch|preservesPlayStats|differentSource|"
    r"RoomAndMemory|StoreCommit|FinalCommit|PreventsLate|KeepsPreviousRow|"
    r"AllowsStorageLocation|InvalidatesInFlight|DropsObservation|DropsCandidate|"
    r"CleansPending|WithoutClearingLibrary|PromotesStagingAndBindingAtomically",
    re.I,
)
PLAYER_CONTRACT = re.compile(
    r"autoPlayOnLaunch|unsupportedSelection|requestedTuning|dsdPlayback|"
    r"superseded|restor|coldStart|disconnectedController|playCount|listenSeconds|"
    r"repeatEvidence|automaticNext|seekFromEnd|shuffle.*(order|next|off|restore)|"
    r"nextOnSingleItem|KeepsProgress|ResetsProgress|playerError|rapidNext|"
    r"targetMediaId|jitter",
    re.I,
)
SCHEDULER_CONTRACT = re.compile(
    r"failedAutoPassRequeues|cancelledAutoPassDoesNotRevive|"
    r"thousandDirtySignalsCoalesce|dirtyCannotAutoPopulate",
    re.I,
)


def git_test_files() -> list[str]:
    files = subprocess.check_output(["git", "ls-files", "*.kt"], cwd=ROOT, text=True).splitlines()
    return [p for p in files if "/src/test/" in p or "/src/androidTest/" in p]


def classify(path: str, suite: str, test: str) -> tuple[str, str, str]:
    reviewed = reviewed_category(suite, test)
    if reviewed is not None:
        category, rationale = reviewed
        return category, rationale, "reviewed"
    if "/media/usbprototype/" in path:
        return "OBSOLETE", "orphaned-prototype", "high"
    if suite in ARCH_FILES:
        return "ARCHITECTURE_CONTRACT", "explicit-architecture-boundary", "high"

    if suite == "LibraryOperationExecutorTest":
        if EXECUTOR_CONTRACT.search(test):
            return "BEHAVIOR_CONTRACT", "manual-core:safety/publication invariant", "reviewed"
        return "IMPLEMENTATION_VERIFICATION", "manual-core:executor/stage mechanism", "reviewed"
    if suite == "PlayerControllerBoundaryTest":
        if PLAYER_CONTRACT.search(test):
            return "BEHAVIOR_CONTRACT", "manual-core:playback/session invariant", "reviewed"
        return "IMPLEMENTATION_VERIFICATION", "manual-core:controller/service mechanism", "reviewed"
    if suite == "LibrarySyncSchedulerTest":
        if SCHEDULER_CONTRACT.search(test):
            return "BEHAVIOR_CONTRACT", "manual-core:scheduler safety invariant", "reviewed"
        return "IMPLEMENTATION_VERIFICATION", "manual-core:debounce/cooldown strategy", "reviewed"

    if suite.endswith(("ContractTest", "MigrationTest", "PolicyTest", "StateMachineTest")):
        return "BEHAVIOR_CONTRACT", "explicit-contract-name", "high"
    if "/src/androidTest/" in path:
        return "IMPLEMENTATION_VERIFICATION", "device-flow/profile wiring", "high"
    if path.startswith("third_party/"):
        return "BEHAVIOR_CONTRACT", "vendored protocol/transport semantics", "high"

    if "/data/preferences/" in path:
        return "IMPLEMENTATION_VERIFICATION", "preference storage/wiring", "high"
    if "/data/library/" in path:
        if suite in LIBRARY_CONTRACT_FILES:
            return "BEHAVIOR_CONTRACT", "library safety/policy semantics", "high"
        if any(word in suite for word in LIBRARY_IMPL_WORDS):
            return "IMPLEMENTATION_VERIFICATION", "current auto-sync mechanism", "high"
        return "IMPLEMENTATION_VERIFICATION", "conservative library default", "medium"
    if "/data/scanner/" in path:
        if suite in SCANNER_IMPL_FILES:
            return "IMPLEMENTATION_VERIFICATION", "scanner mechanism/perf", "high"
        return "BEHAVIOR_CONTRACT", "format/discovery compatibility", "high"
    if "/data/remote/" in path:
        if suite in REMOTE_IMPL_FILES:
            return "IMPLEMENTATION_VERIFICATION", "remote owner/manager wiring", "high"
        return "BEHAVIOR_CONTRACT", "remote protocol/storage semantics", "high"
    if "/data/local/" in path:
        return "BEHAVIOR_CONTRACT", "persistence/model semantics", "high"
    if "/data/" in path:
        if suite in DATA_IMPL_FILES:
            return "IMPLEMENTATION_VERIFICATION", "presentation/layout model", "high"
        return "BEHAVIOR_CONTRACT", "data-domain semantics", "medium"

    if "/playback/" in path:
        if any(word in suite for word in PLAYBACK_IMPL_WORDS):
            return "IMPLEMENTATION_VERIFICATION", "current playback mechanism", "high"
        return "BEHAVIOR_CONTRACT", "playback-domain semantics", "medium"

    if "/media/usbhybrid/" in path:
        if suite in USB_BEHAVIOR_FILES:
            return "BEHAVIOR_CONTRACT", "USB boundary/protocol semantics", "high"
        if suite in USB_IMPL_FILES:
            return "IMPLEMENTATION_VERIFICATION", "current USB transport/wiring", "high"
        return "IMPLEMENTATION_VERIFICATION", "conservative USB default", "medium"
    if "/media/" in path:
        if suite in MEDIA_BEHAVIOR_FILES or any(x in suite for x in ("ReaderTest", "ParserTest", "CodecTest", "ResolverTest")):
            return "BEHAVIOR_CONTRACT", "media boundary/codec semantics", "high"
        return "IMPLEMENTATION_VERIFICATION", "current media pipeline/wiring", "medium"

    if "/ui/" in path:
        if suite == "LibraryAccessibilityTest":
            return "BEHAVIOR_CONTRACT", "accessibility contract", "high"
        return "IMPLEMENTATION_VERIFICATION", "UI layout/interaction/current design", "high"
    if "/imaging/" in path:
        return "IMPLEMENTATION_VERIFICATION", "current imaging pipeline", "medium"
    return "IMPLEMENTATION_VERIFICATION", "conservative default", "medium"


def main() -> None:
    rows = []
    for rel in git_test_files():
        file = ROOT / rel
        if not file.exists():
            continue
        text = file.read_text(encoding="utf-8", errors="replace")
        suite = file.stem
        for match in TEST_RE.finditer(text):
            test = match.group(1).strip("`")
            category, rationale, confidence = classify(rel, suite, test)
            rows.append((category, rel, suite, test, rationale, confidence))
    rows.sort(key=lambda row: (row[0], row[1], row[3]))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["category", "file", "suite", "test", "rationale", "confidence"])
        writer.writerows(rows)
    print(f"wrote {len(rows)} tests to {OUT.relative_to(ROOT)}")

if __name__ == "__main__":
    main()
