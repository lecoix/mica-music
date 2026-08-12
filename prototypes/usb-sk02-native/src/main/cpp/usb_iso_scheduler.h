#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>

#include "usb_iso_timing.h"

namespace mica::usb::iso {

constexpr std::uint64_t kQ16One = 65'536ULL;

struct SchedulerConfig {
    std::uint32_t service_intervals_per_second = 0;
    std::uint32_t bytes_per_frame = 0;
    std::uint32_t max_packet_bytes = 0;

    constexpr bool valid() const {
        return service_intervals_per_second > 0 &&
            bytes_per_frame > 0 &&
            max_packet_bytes >= bytes_per_frame;
    }

    constexpr std::uint32_t max_frames_per_packet() const {
        return bytes_per_frame == 0 ? 0 : max_packet_bytes / bytes_per_frame;
    }
};

struct PacketScheduleResult {
    bool valid = false;
    bool capacity_limited = false;
    std::uint32_t requested_frames = 0;
    std::uint32_t scheduled_frames = 0;
    std::uint32_t scheduled_bytes = 0;
    std::uint32_t phase_q16 = 0;
};

/**
 * Payload-agnostic isochronous packet scheduler.
 *
 * rate_q16 is expressed as audio frames per USB service interval in unsigned 16.16 form. The
 * scheduler preserves the fractional phase between packets and exposes capacity limiting instead
 * of hiding it so host tests can fail a transport configuration that cannot conserve frames.
 */
class PacketScheduler {
public:
    explicit PacketScheduler(const SchedulerConfig config, const std::uint32_t initial_phase_q16 = 0)
        : config_(config), phase_q16_(initial_phase_q16 & 0xffffU) {}

    PacketScheduleResult next(const std::uint64_t rate_q16) {
        PacketScheduleResult result{};
        if (!config_.valid() || rate_q16 > std::numeric_limits<std::uint64_t>::max() - phase_q16_) {
            return result;
        }

        const std::uint64_t accumulated = rate_q16 + phase_q16_;
        const std::uint64_t requested_frames64 = accumulated >> 16U;
        if (requested_frames64 > std::numeric_limits<std::uint32_t>::max()) return result;

        result.valid = true;
        result.requested_frames = static_cast<std::uint32_t>(requested_frames64);
        result.scheduled_frames = std::min(
            result.requested_frames,
            config_.max_frames_per_packet());
        result.capacity_limited = result.scheduled_frames != result.requested_frames;
        result.scheduled_bytes = result.scheduled_frames * config_.bytes_per_frame;
        phase_q16_ = static_cast<std::uint32_t>(accumulated & 0xffffULL);
        result.phase_q16 = phase_q16_;

        ++scheduled_packets_;
        requested_frames_ += result.requested_frames;
        scheduled_frames_ += result.scheduled_frames;
        if (result.capacity_limited) ++capacity_limited_packets_;
        return result;
    }

    std::uint32_t phase_q16() const { return phase_q16_; }
    std::uint64_t scheduled_packets() const { return scheduled_packets_; }
    std::uint64_t requested_frames() const { return requested_frames_; }
    std::uint64_t scheduled_frames() const { return scheduled_frames_; }
    std::uint64_t capacity_limited_packets() const { return capacity_limited_packets_; }

private:
    SchedulerConfig config_;
    std::uint32_t phase_q16_ = 0;
    std::uint64_t scheduled_packets_ = 0;
    std::uint64_t requested_frames_ = 0;
    std::uint64_t scheduled_frames_ = 0;
    std::uint64_t capacity_limited_packets_ = 0;
};

struct ConstantRateProjection {
    bool valid = false;
    bool capacity_sufficient = false;
    std::uint64_t interval_count = 0;
    std::uint64_t total_frames = 0;
    std::uint32_t final_phase_q16 = 0;
    std::uint64_t maximum_frames_per_interval = 0;
};

inline bool checked_multiply(
    const std::uint64_t left,
    const std::uint64_t right,
    std::uint64_t* const result) {
    if (result == nullptr) return false;
    if (left != 0 && right > std::numeric_limits<std::uint64_t>::max() / left) return false;
    *result = left * right;
    return true;
}

inline bool checked_add(
    const std::uint64_t left,
    const std::uint64_t right,
    std::uint64_t* const result) {
    if (result == nullptr || right > std::numeric_limits<std::uint64_t>::max() - left) return false;
    *result = left + right;
    return true;
}

/** Constant-time projection of the exact same 16.16 accumulator used by PacketScheduler. */
inline ConstantRateProjection project_constant_q16_rate(
    const SchedulerConfig config,
    const std::uint64_t interval_count,
    const std::uint64_t rate_q16,
    const std::uint32_t initial_phase_q16 = 0) {
    ConstantRateProjection projection{};
    projection.interval_count = interval_count;
    if (!config.valid()) return projection;

    const std::uint64_t whole_frames = rate_q16 >> 16U;
    const std::uint64_t fractional_q16 = rate_q16 & 0xffffULL;
    std::uint64_t base_frames = 0;
    std::uint64_t fractional_total = 0;
    if (!checked_multiply(interval_count, whole_frames, &base_frames) ||
        !checked_multiply(interval_count, fractional_q16, &fractional_total) ||
        !checked_add(fractional_total, initial_phase_q16 & 0xffffU, &fractional_total)) {
        return projection;
    }

    std::uint64_t total_frames = 0;
    if (!checked_add(base_frames, fractional_total >> 16U, &total_frames)) return projection;

    projection.valid = true;
    projection.total_frames = total_frames;
    projection.final_phase_q16 = static_cast<std::uint32_t>(fractional_total & 0xffffULL);
    projection.maximum_frames_per_interval = whole_frames + (fractional_q16 == 0 ? 0ULL : 1ULL);
    projection.capacity_sufficient =
        projection.maximum_frames_per_interval <= config.max_frames_per_packet();
    return projection;
}

struct RationalRateProjection {
    bool valid = false;
    bool capacity_sufficient = false;
    std::uint64_t interval_count = 0;
    std::uint64_t total_frames = 0;
    std::uint32_t final_phase_numerator = 0;
    std::uint64_t maximum_frames_per_interval = 0;
};

/**
 * Constant-time ideal projection for fixed/adaptive/synchronous modes that schedule directly from
 * sampleRateHz/serviceIntervalsPerSecond rather than a quantized feedback value.
 */
inline RationalRateProjection project_sample_rate(
    const SchedulerConfig config,
    const std::uint64_t interval_count,
    const std::uint32_t sample_rate_hz,
    const std::uint32_t initial_phase_numerator = 0) {
    RationalRateProjection projection{};
    projection.interval_count = interval_count;
    if (!config.valid() || sample_rate_hz == 0 ||
        initial_phase_numerator >= config.service_intervals_per_second) {
        return projection;
    }

    const std::uint64_t whole_frames = sample_rate_hz / config.service_intervals_per_second;
    const std::uint64_t remainder = sample_rate_hz % config.service_intervals_per_second;
    std::uint64_t base_frames = 0;
    std::uint64_t remainder_total = 0;
    if (!checked_multiply(interval_count, whole_frames, &base_frames) ||
        !checked_multiply(interval_count, remainder, &remainder_total) ||
        !checked_add(remainder_total, initial_phase_numerator, &remainder_total)) {
        return projection;
    }

    std::uint64_t total_frames = 0;
    if (!checked_add(
            base_frames,
            remainder_total / config.service_intervals_per_second,
            &total_frames)) {
        return projection;
    }

    projection.valid = true;
    projection.total_frames = total_frames;
    projection.final_phase_numerator = static_cast<std::uint32_t>(
        remainder_total % config.service_intervals_per_second);
    projection.maximum_frames_per_interval = whole_frames + (remainder == 0 ? 0ULL : 1ULL);
    projection.capacity_sufficient =
        projection.maximum_frames_per_interval <= config.max_frames_per_packet();
    return projection;
}

struct ExactNominalSchedulerConfig {
    ServicePeriod data_service_period{};
    std::uint32_t bytes_per_runtime_frame = 0;
    std::uint32_t max_bytes_per_data_service_interval = 0;

    constexpr bool valid() const {
        return data_service_period.valid() &&
            bytes_per_runtime_frame > 0 &&
            max_bytes_per_data_service_interval >= bytes_per_runtime_frame;
    }

    constexpr std::uint32_t max_runtime_frames_per_interval() const {
        return bytes_per_runtime_frame == 0 ? 0 :
            max_bytes_per_data_service_interval / bytes_per_runtime_frame;
    }
};

struct ExactNominalPacketResult {
    bool valid = false;
    bool capacity_limited = false;
    std::uint32_t requested_runtime_frames = 0;
    std::uint32_t scheduled_runtime_frames = 0;
    std::uint32_t scheduled_bytes = 0;
    std::uint64_t phase_numerator = 0;
};

/**
 * Runtime-capable exact scheduler for modes without device feedback.
 *
 * The service period is an exact rational number of seconds. Each data service interval adds
 * nominalRuntimeFrameRateHz * period.numerator to a phase whose denominator is period.denominator.
 * This avoids converting the nominal clock through a floored fixed-point rate.
 */
class ExactNominalPacketScheduler {
public:
    ExactNominalPacketScheduler(
        const ExactNominalSchedulerConfig config,
        const std::uint64_t nominal_runtime_frame_rate_hz,
        const std::uint64_t initial_phase_numerator = 0)
        : config_(config),
          nominal_runtime_frame_rate_hz_(nominal_runtime_frame_rate_hz),
          phase_numerator_(initial_phase_numerator) {
        valid_ = config_.valid() && nominal_runtime_frame_rate_hz_ > 0 &&
            phase_numerator_ < config_.data_service_period.denominator &&
            checked_multiply_u64(
                nominal_runtime_frame_rate_hz_,
                config_.data_service_period.numerator,
                &step_numerator_);
    }

    ExactNominalPacketResult next() {
        ExactNominalPacketResult result{};
        std::uint64_t accumulated = 0;
        if (!valid_ || !checked_add_u64(step_numerator_, phase_numerator_, &accumulated)) {
            return result;
        }

        const std::uint64_t requested64 = accumulated / config_.data_service_period.denominator;
        if (requested64 > std::numeric_limits<std::uint32_t>::max()) return result;

        result.valid = true;
        result.requested_runtime_frames = static_cast<std::uint32_t>(requested64);
        result.scheduled_runtime_frames = std::min(
            result.requested_runtime_frames,
            config_.max_runtime_frames_per_interval());
        result.capacity_limited =
            result.requested_runtime_frames != result.scheduled_runtime_frames;
        result.scheduled_bytes =
            result.scheduled_runtime_frames * config_.bytes_per_runtime_frame;
        phase_numerator_ = accumulated % config_.data_service_period.denominator;
        result.phase_numerator = phase_numerator_;
        return result;
    }

    bool valid() const { return valid_; }
    std::uint64_t phase_numerator() const { return phase_numerator_; }

private:
    ExactNominalSchedulerConfig config_{};
    std::uint64_t nominal_runtime_frame_rate_hz_ = 0;
    std::uint64_t step_numerator_ = 0;
    std::uint64_t phase_numerator_ = 0;
    bool valid_ = false;
};

struct ExactNominalProjection {
    bool valid = false;
    bool capacity_sufficient = false;
    std::uint64_t interval_count = 0;
    std::uint64_t total_runtime_frames = 0;
    std::uint64_t final_phase_numerator = 0;
    std::uint64_t maximum_runtime_frames_per_interval = 0;
};

inline ExactNominalProjection project_nominal_runtime_rate(
    const ExactNominalSchedulerConfig config,
    const std::uint64_t interval_count,
    const std::uint64_t nominal_runtime_frame_rate_hz,
    const std::uint64_t initial_phase_numerator = 0) {
    ExactNominalProjection projection{};
    projection.interval_count = interval_count;
    if (!config.valid() || nominal_runtime_frame_rate_hz == 0 ||
        initial_phase_numerator >= config.data_service_period.denominator) {
        return projection;
    }

    std::uint64_t step_numerator = 0;
    std::uint64_t total_numerator = 0;
    if (!checked_multiply_u64(
            nominal_runtime_frame_rate_hz,
            config.data_service_period.numerator,
            &step_numerator) ||
        !checked_multiply_u64(interval_count, step_numerator, &total_numerator) ||
        !checked_add_u64(total_numerator, initial_phase_numerator, &total_numerator)) {
        return projection;
    }

    projection.valid = true;
    projection.total_runtime_frames = total_numerator / config.data_service_period.denominator;
    projection.final_phase_numerator = total_numerator % config.data_service_period.denominator;
    projection.maximum_runtime_frames_per_interval =
        step_numerator / config.data_service_period.denominator +
        (step_numerator % config.data_service_period.denominator == 0 ? 0ULL : 1ULL);
    projection.capacity_sufficient =
        projection.maximum_runtime_frames_per_interval <= config.max_runtime_frames_per_interval();
    return projection;
}

constexpr std::uint64_t floor_q16_rate(
    const std::uint32_t sample_rate_hz,
    const std::uint32_t service_intervals_per_second) {
    return service_intervals_per_second == 0 ? 0 :
        (static_cast<std::uint64_t>(sample_rate_hz) * kQ16One) /
            service_intervals_per_second;
}

}  // namespace mica::usb::iso
