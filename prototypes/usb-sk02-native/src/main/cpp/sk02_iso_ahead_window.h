#pragma once

#include <cstdint>

namespace sk02::iso {

constexpr std::uint32_t kUsbIntervalsPerSecond = 8'000;
constexpr std::uint32_t kDataPacketsPerTransfer = 8;

// Current production policy. Kept here so the worker and the regression test share one seam.
constexpr std::uint32_t kDataQueueDepth = 16;

constexpr std::uint64_t ahead_window_us(
    const std::uint32_t queue_depth,
    const std::uint32_t packets_per_transfer,
    const std::uint32_t intervals_per_second) {
    return queue_depth == 0 || packets_per_transfer == 0 || intervals_per_second == 0 ? 0 :
        (static_cast<std::uint64_t>(queue_depth) * packets_per_transfer * 1'000'000ULL) /
            intervals_per_second;
}

constexpr std::uint32_t minimum_queue_depth_for_gap(
    const std::uint64_t completion_gap_us,
    const std::uint64_t refill_margin_us,
    const std::uint32_t packets_per_transfer,
    const std::uint32_t intervals_per_second) {
    if (packets_per_transfer == 0 || intervals_per_second == 0) return 0;
    const std::uint64_t required_intervals_numerator =
        (completion_gap_us + refill_margin_us) * intervals_per_second;
    const std::uint64_t intervals =
        (required_intervals_numerator + 999'999ULL) / 1'000'000ULL;
    return static_cast<std::uint32_t>(
        (intervals + packets_per_transfer - 1U) / packets_per_transfer);
}

}  // namespace sk02::iso
