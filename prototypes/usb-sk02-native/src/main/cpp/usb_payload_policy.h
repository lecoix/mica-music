#pragma once

#include <algorithm>
#include <cerrno>
#include <cstddef>

namespace mica::usb::payload {

enum class Policy : int {
    PcmZeroFill = 0,
    ExactFramesOnly = 1,
};

// Stable non-errno codes reserved for producer/session contract violations in exact-carrier mode.
constexpr int kExactFramesUnderflowError = 10'004;
constexpr int kExactFramesMisalignedInputError = 10'005;

inline bool is_valid_policy_value(const int value) {
    return value == static_cast<int>(Policy::PcmZeroFill) ||
        value == static_cast<int>(Policy::ExactFramesOnly);
}

struct SourceWriteValidation {
    bool accepted;
    int stream_error_code;
};

inline SourceWriteValidation validate_source_write(
    const Policy policy,
    const std::size_t length,
    const std::size_t bytes_per_runtime_frame) {
    if (bytes_per_runtime_frame == 0 || length == 0) return {false, 0};
    if (length % bytes_per_runtime_frame == 0) return {true, 0};
    return policy == Policy::ExactFramesOnly ?
        SourceWriteValidation{false, kExactFramesMisalignedInputError} :
        SourceWriteValidation{false, 0};
}

struct FillResult {
    bool ready_for_submit;
    std::size_t synthesized_bytes;
    int stream_error_code;
};

/**
 * Finalizes one already-scheduled request after complete source frames have been copied into
 * [target]. PCM retains historical zero-fill semantics. Exact-frame payloads never synthesize
 * bytes: a shortage is a stream error and the caller must fail before USBDEVFS_SUBMITURB.
 */
inline FillResult finalize_scheduled_payload(
    const Policy policy,
    unsigned char* target,
    const std::size_t scheduled_bytes,
    const std::size_t supplied_bytes,
    const std::size_t bytes_per_runtime_frame) {
    if (target == nullptr || bytes_per_runtime_frame == 0 || scheduled_bytes == 0 ||
        scheduled_bytes % bytes_per_runtime_frame != 0 || supplied_bytes > scheduled_bytes ||
        supplied_bytes % bytes_per_runtime_frame != 0) {
        return {
            false,
            0,
            policy == Policy::ExactFramesOnly ? kExactFramesMisalignedInputError : EINVAL,
        };
    }

    if (supplied_bytes == scheduled_bytes) return {true, 0, 0};

    if (policy == Policy::ExactFramesOnly) {
        return {false, 0, kExactFramesUnderflowError};
    }

    std::fill(target + supplied_bytes, target + scheduled_bytes, static_cast<unsigned char>(0));
    return {true, scheduled_bytes - supplied_bytes, 0};
}

inline bool observes_pcm_continuity_metrics(const Policy policy) {
    return policy == Policy::PcmZeroFill;
}

}  // namespace mica::usb::payload
