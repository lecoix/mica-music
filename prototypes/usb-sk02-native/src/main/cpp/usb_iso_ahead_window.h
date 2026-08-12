#pragma once

#include <cstdint>
#include <limits>

#include "usb_iso_timing.h"

namespace mica::usb::iso {

constexpr std::uint64_t ahead_window_us(
    const std::uint32_t queue_depth,
    const std::uint32_t packets_per_transfer,
    const std::uint32_t service_intervals_per_second) {
    return queue_depth == 0 || packets_per_transfer == 0 || service_intervals_per_second == 0 ? 0 :
        (static_cast<std::uint64_t>(queue_depth) * packets_per_transfer * 1'000'000ULL) /
            service_intervals_per_second;
}

constexpr std::uint32_t minimum_queue_depth_for_gap(
    const std::uint64_t completion_gap_us,
    const std::uint64_t refill_margin_us,
    const std::uint32_t packets_per_transfer,
    const std::uint32_t service_intervals_per_second) {
    if (packets_per_transfer == 0 || service_intervals_per_second == 0) return 0;
    const std::uint64_t required_intervals_numerator =
        (completion_gap_us + refill_margin_us) * service_intervals_per_second;
    const std::uint64_t intervals =
        (required_intervals_numerator + 999'999ULL) / 1'000'000ULL;
    return static_cast<std::uint32_t>(
        (intervals + packets_per_transfer - 1U) / packets_per_transfer);
}

inline ServicePeriod ahead_window_period(
    const std::uint32_t queue_depth,
    const std::uint32_t packets_per_transfer,
    const ServicePeriod data_service_period) {
    if (queue_depth == 0 || packets_per_transfer == 0 || !data_service_period.valid()) return {};

    std::uint64_t interval_count = 0;
    std::uint64_t numerator = 0;
    if (!checked_multiply_u64(queue_depth, packets_per_transfer, &interval_count) ||
        !checked_multiply_u64(interval_count, data_service_period.numerator, &numerator)) {
        return {};
    }
    return reduced_period({numerator, data_service_period.denominator});
}

struct MinimumQueueDepthResult {
    bool valid = false;
    std::uint32_t queue_depth = 0;
};

inline MinimumQueueDepthResult minimum_queue_depth_for_gap_exact(
    const std::uint64_t completion_gap_us,
    const std::uint64_t refill_margin_us,
    const std::uint32_t packets_per_transfer,
    const ServicePeriod data_service_period) {
    MinimumQueueDepthResult result{};
    if (packets_per_transfer == 0 || !data_service_period.valid()) return result;

    std::uint64_t required_us = 0;
    std::uint64_t required_numerator = 0;
    std::uint64_t coverage_denominator = 0;
    std::uint64_t packet_period_numerator = 0;
    if (!checked_add_u64(completion_gap_us, refill_margin_us, &required_us) ||
        !checked_multiply_u64(required_us, data_service_period.denominator, &required_numerator) ||
        !checked_multiply_u64(
            packets_per_transfer,
            data_service_period.numerator,
            &packet_period_numerator) ||
        !checked_multiply_u64(1'000'000ULL, packet_period_numerator, &coverage_denominator) ||
        coverage_denominator == 0) {
        return result;
    }

    const std::uint64_t quotient = required_numerator / coverage_denominator;
    const std::uint64_t remainder = required_numerator % coverage_denominator;
    const std::uint64_t depth = quotient + (remainder == 0 ? 0ULL : 1ULL);
    if (depth > std::numeric_limits<std::uint32_t>::max()) return result;

    result.valid = true;
    result.queue_depth = static_cast<std::uint32_t>(depth);
    return result;
}

}  // namespace mica::usb::iso
