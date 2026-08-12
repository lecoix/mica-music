#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>

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

constexpr std::uint64_t floor_q16_rate(
    const std::uint32_t sample_rate_hz,
    const std::uint32_t service_intervals_per_second) {
    return service_intervals_per_second == 0 ? 0 :
        (static_cast<std::uint64_t>(sample_rate_hz) * kQ16One) /
            service_intervals_per_second;
}

}  // namespace mica::usb::iso
