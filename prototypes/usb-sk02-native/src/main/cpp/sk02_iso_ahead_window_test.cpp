#include "sk02_iso_ahead_window.h"

#include <iostream>

namespace {

constexpr std::uint64_t kObservedCompletionGapUs = 11'761;
constexpr std::uint64_t kOneTransferRefillMarginUs = 1'000;

bool production_window_covers_captured_gap() {
    const std::uint64_t production_window_us = sk02::iso::ahead_window_us(
        sk02::iso::kDataQueueDepth,
        sk02::iso::kDataPacketsPerTransfer,
        sk02::iso::kUsbIntervalsPerSecond);
    const std::uint32_t required_depth = sk02::iso::minimum_queue_depth_for_gap(
        kObservedCompletionGapUs,
        kOneTransferRefillMarginUs,
        sk02::iso::kDataPacketsPerTransfer,
        sk02::iso::kUsbIntervalsPerSecond);
    std::cout << "productionDepth=" << sk02::iso::kDataQueueDepth
              << " productionWindowUs=" << production_window_us
              << " observedGapUs=" << kObservedCompletionGapUs
              << " refillMarginUs=" << kOneTransferRefillMarginUs
              << " requiredDepth=" << required_depth << '\n';
    return sk02::iso::kDataQueueDepth >= required_depth;
}

bool reference_sized_window_covers_captured_gap() {
    constexpr std::uint32_t kReferenceDepth = 16;
    return sk02::iso::ahead_window_us(
               kReferenceDepth,
               sk02::iso::kDataPacketsPerTransfer,
               sk02::iso::kUsbIntervalsPerSecond) >=
        kObservedCompletionGapUs + kOneTransferRefillMarginUs;
}

}  // namespace

int main() {
    const bool production = production_window_covers_captured_gap();
    const bool reference = reference_sized_window_covers_captured_gap();
    std::cout << "productionCoverage=" << (production ? "pass" : "fail") << '\n'
              << "referenceCoverage=" << (reference ? "pass" : "fail") << '\n';
    return production && reference ? 0 : 1;
}
