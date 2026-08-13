#include "usb_exact_carrier_activation.h"
#include "usb_iso_scheduler.h"
#include "usb_payload_policy.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <limits>
#include <vector>

namespace {

using mica::usb::activation::ArmDecision;
using mica::usb::activation::ExactCarrierActivationGate;
using mica::usb::activation::PrefillBoundError;
using mica::usb::activation::calculate_startup_prefill_bound;

void representative_prefill_bounds_are_geometry_derived_and_frame_aligned() {
    struct Case {
        std::uint64_t runtime_rate;
        std::uint64_t frame_bytes;
        std::uint64_t max_interval_bytes;
        std::uint64_t packets;
        std::uint64_t depth;
    };
    const std::array<Case, 5> cases{{
        {48'000, 4, 200, 8, 16},
        {96'000, 6, 300, 8, 16},
        {176'400, 6, 300, 8, 16},
        {352'800, 8, 512, 8, 16},
        {705'600, 8, 1'024, 8, 16},
    }};

    for (const auto& item : cases) {
        const std::uint64_t ring = item.runtime_rate * item.frame_bytes * 2;
        const auto bound = calculate_startup_prefill_bound(
            item.depth,
            item.packets,
            item.max_interval_bytes,
            item.frame_bytes,
            ring);
        assert(bound.valid);
        assert(bound.error == PrefillBoundError::None);
        assert(bound.required_bytes % item.frame_bytes == 0);
        assert(bound.required_frames * item.frame_bytes == bound.required_bytes);
        assert(bound.required_bytes >= item.depth * item.packets * item.max_interval_bytes);
        assert(bound.required_bytes <= ring);
    }
}

void non_frame_multiple_capacity_is_rounded_up_conservatively() {
    const auto bound = calculate_startup_prefill_bound(
        3,
        2,
        101,
        6,
        48'000 * 6ULL * 2ULL);
    assert(bound.valid);
    assert(bound.required_bytes == 606);
    assert(bound.required_frames == 101);
}

void invalid_overflow_and_too_small_ring_are_rejected() {
    const auto invalid = calculate_startup_prefill_bound(0, 8, 300, 6, 1'000'000);
    assert(!invalid.valid);
    assert(invalid.error == PrefillBoundError::InvalidGeometry);

    const auto overflow = calculate_startup_prefill_bound(
        std::numeric_limits<std::uint64_t>::max(),
        2,
        2,
        1,
        std::numeric_limits<std::uint64_t>::max());
    assert(!overflow.valid);
    assert(overflow.error == PrefillBoundError::Overflow);

    const auto too_small = calculate_startup_prefill_bound(16, 8, 300, 6, 30'000);
    assert(!too_small.valid);
    assert(too_small.error == PrefillBoundError::ExceedsRingCapacity);
}

void dormant_lifecycle_does_not_start_worker_or_source_flow() {
    assert(mica::usb::activation::worker_starts_on_construction(false));
    assert(!mica::usb::activation::worker_starts_on_construction(true));
    assert(!mica::usb::activation::source_flow_active(true, false, false));
    assert(!mica::usb::activation::source_flow_active(true, true, false));
    assert(mica::usb::activation::source_flow_active(true, false, true));
    assert(!mica::usb::activation::source_flow_active(false, false, true));
    assert(mica::usb::activation::source_flow_active(false, true, false));
}
void dormant_empty_and_insufficient_prefill_are_retryable_without_state_change() {
    const auto bound = calculate_startup_prefill_bound(16, 8, 300, 6, 2'116'800);
    assert(bound.valid);
    ExactCarrierActivationGate gate(bound);

    assert(gate.evaluate_arm(0, false, 0) == ArmDecision::RetryInsufficientPrefill);
    assert(!gate.is_armed());
    assert(gate.evaluate_arm(bound.required_bytes - 6, false, 0) ==
        ArmDecision::RetryInsufficientPrefill);
    assert(!gate.is_armed());
    assert(gate.evaluate_arm(bound.required_bytes, false, 0) == ArmDecision::Accepted);
    assert(!gate.is_armed());
    gate.mark_armed();
    assert(gate.is_armed());
    assert(gate.evaluate_arm(bound.required_bytes, false, 0) == ArmDecision::AlreadyArmed);
}

void stopped_or_failed_dormant_session_cannot_arm() {
    const auto bound = calculate_startup_prefill_bound(16, 8, 300, 6, 2'116'800);
    assert(bound.valid);
    ExactCarrierActivationGate gate(bound);
    assert(gate.evaluate_arm(bound.required_bytes, true, 0) == ArmDecision::StoppedOrFailed);
    assert(gate.evaluate_arm(bound.required_bytes, false, 10'005) == ArmDecision::StoppedOrFailed);
    assert(!gate.is_armed());
}

void failed_arm_does_not_advance_scheduler_phase() {
    const auto bound = calculate_startup_prefill_bound(16, 8, 300, 6, 2'116'800);
    ExactCarrierActivationGate gate(bound);
    assert(gate.evaluate_arm(bound.required_bytes - 6, false, 0) ==
        ArmDecision::RetryInsufficientPrefill);

    const mica::usb::iso::ExactNominalSchedulerConfig config{
        mica::usb::iso::ServicePeriod{1, 8'000},
        6,
        300,
    };
    mica::usb::iso::ExactNominalPacketScheduler baseline(config, 176'400);
    assert(baseline.valid());

    assert(gate.evaluate_arm(bound.required_bytes, false, 0) == ArmDecision::Accepted);
    gate.mark_armed();
    mica::usb::iso::ExactNominalPacketScheduler armed(config, 176'400);
    assert(armed.valid());

    for (int index = 0; index < 20'000; ++index) {
        const auto expected = baseline.next();
        const auto actual = armed.next();
        assert(expected.valid && actual.valid);
        assert(expected.capacity_limited == actual.capacity_limited);
        assert(expected.scheduled_runtime_frames == actual.scheduled_runtime_frames);
        assert(expected.scheduled_bytes == actual.scheduled_bytes);
    }
}

void dormant_exact_prefill_accepts_only_complete_runtime_frames() {
    const auto aligned = mica::usb::payload::validate_source_write(
        mica::usb::payload::Policy::ExactFramesOnly,
        24,
        6);
    assert(aligned.accepted);
    assert(aligned.stream_error_code == 0);

    const auto misaligned = mica::usb::payload::validate_source_write(
        mica::usb::payload::Policy::ExactFramesOnly,
        23,
        6);
    assert(!misaligned.accepted);
    assert(misaligned.stream_error_code == mica::usb::payload::kExactFramesMisalignedInputError);

    const auto pcm_misaligned = mica::usb::payload::validate_source_write(
        mica::usb::payload::Policy::PcmZeroFill,
        23,
        6);
    assert(!pcm_misaligned.accepted);
    assert(pcm_misaligned.stream_error_code == 0);
}
void armed_exact_shortage_stays_fail_closed_without_synthesis() {
    std::array<unsigned char, 12> buffer{
        0x05, 0x12, 0x34, 0xfa, 0x56, 0x78,
        0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e,
    };
    const auto before = buffer;
    const auto result = mica::usb::payload::finalize_scheduled_payload(
        mica::usb::payload::Policy::ExactFramesOnly,
        buffer.data(),
        buffer.size(),
        6,
        6);
    assert(!result.ready_for_submit);
    assert(result.stream_error_code == mica::usb::payload::kExactFramesUnderflowError);
    assert(result.synthesized_bytes == 0);
    assert(buffer == before);
}

void exact_activation_gate_has_no_pause_or_idle_generation_operation() {
    const auto bound = calculate_startup_prefill_bound(16, 8, 300, 6, 2'116'800);
    ExactCarrierActivationGate gate(bound);
    assert(gate.evaluate_arm(bound.required_bytes, false, 0) == ArmDecision::Accepted);
    gate.mark_armed();
    assert(gate.is_armed());
    // Deliberately no pause/resume/filler API: once armed, higher layers must supply valid carrier.
}

}  // namespace

int main() {
    representative_prefill_bounds_are_geometry_derived_and_frame_aligned();
    non_frame_multiple_capacity_is_rounded_up_conservatively();
    invalid_overflow_and_too_small_ring_are_rejected();
    dormant_lifecycle_does_not_start_worker_or_source_flow();
    dormant_empty_and_insufficient_prefill_are_retryable_without_state_change();
    stopped_or_failed_dormant_session_cannot_arm();
    failed_arm_does_not_advance_scheduler_phase();
    dormant_exact_prefill_accepts_only_complete_runtime_frames();
    armed_exact_shortage_stays_fail_closed_without_synthesis();
    exact_activation_gate_has_no_pause_or_idle_generation_operation();
    return 0;
}
