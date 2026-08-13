#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>

namespace mica::usb::activation {

enum class PrefillBoundError {
    None,
    InvalidGeometry,
    Overflow,
    ExceedsRingCapacity,
};

struct StartupPrefillBound {
    bool valid;
    std::uint64_t required_bytes;
    std::uint64_t required_frames;
    PrefillBoundError error;
};

inline bool checked_multiply_u64(
    const std::uint64_t left,
    const std::uint64_t right,
    std::uint64_t* const result) {
    if (result == nullptr) return false;
    if (left != 0 && right > std::numeric_limits<std::uint64_t>::max() / left) return false;
    *result = left * right;
    return true;
}

inline StartupPrefillBound calculate_startup_prefill_bound(
    const std::uint64_t data_queue_depth,
    const std::uint64_t packets_per_transfer,
    const std::uint64_t max_bytes_per_service_interval,
    const std::uint64_t bytes_per_runtime_frame,
    const std::uint64_t ring_capacity_bytes) {
    if (data_queue_depth == 0 || packets_per_transfer == 0 ||
        max_bytes_per_service_interval == 0 || bytes_per_runtime_frame == 0 ||
        ring_capacity_bytes == 0 || ring_capacity_bytes % bytes_per_runtime_frame != 0) {
        return {false, 0, 0, PrefillBoundError::InvalidGeometry};
    }

    std::uint64_t request_capacity = 0;
    std::uint64_t queue_capacity = 0;
    if (!checked_multiply_u64(
            packets_per_transfer,
            max_bytes_per_service_interval,
            &request_capacity) ||
        !checked_multiply_u64(data_queue_depth, request_capacity, &queue_capacity)) {
        return {false, 0, 0, PrefillBoundError::Overflow};
    }

    const std::uint64_t remainder = queue_capacity % bytes_per_runtime_frame;
    std::uint64_t aligned_required = queue_capacity;
    if (remainder != 0) {
        const std::uint64_t padding = bytes_per_runtime_frame - remainder;
        if (aligned_required > std::numeric_limits<std::uint64_t>::max() - padding) {
            return {false, 0, 0, PrefillBoundError::Overflow};
        }
        aligned_required += padding;
    }
    if (aligned_required == 0 || aligned_required > ring_capacity_bytes) {
        return {false, 0, 0, PrefillBoundError::ExceedsRingCapacity};
    }
    return {
        true,
        aligned_required,
        aligned_required / bytes_per_runtime_frame,
        PrefillBoundError::None,
    };
}

inline bool worker_starts_on_construction(const bool exact_frames_only) {
    return !exact_frames_only;
}

inline bool source_flow_active(
    const bool exact_frames_only,
    const bool pcm_playing,
    const bool exact_armed) {
    return exact_frames_only ? exact_armed : pcm_playing;
}
enum class ArmDecision {
    Accepted,
    RetryInsufficientPrefill,
    AlreadyArmed,
    StoppedOrFailed,
};

class ExactCarrierActivationGate {
public:
    explicit ExactCarrierActivationGate(const StartupPrefillBound bound_value)
        : bound(bound_value) {}

    ArmDecision evaluate_arm(
        const std::uint64_t buffered_bytes,
        const bool stopped,
        const int stream_error_code) const {
        if (!bound.valid || stopped || stream_error_code != 0) {
            return ArmDecision::StoppedOrFailed;
        }
        if (armed) return ArmDecision::AlreadyArmed;
        return buffered_bytes >= bound.required_bytes ?
            ArmDecision::Accepted : ArmDecision::RetryInsufficientPrefill;
    }

    void mark_armed() {
        armed = true;
    }

    bool is_armed() const {
        return armed;
    }

    const StartupPrefillBound bound;

private:
    bool armed = false;
};

}  // namespace mica::usb::activation
