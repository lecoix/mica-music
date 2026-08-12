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

bool exact_service_period_matches_sk02_window() {
    const auto window = mica::usb::iso::ahead_window_period(
        sk02::iso::kDataQueueDepth,
        sk02::iso::kDataPacketsPerTransfer,
        {1, 8'000});
    const auto required = mica::usb::iso::minimum_queue_depth_for_gap_exact(
        kObservedCompletionGapUs,
        kOneTransferRefillMarginUs,
        sk02::iso::kDataPacketsPerTransfer,
        {1, 8'000});
    return window.valid() && window.numerator == 2 && window.denominator == 125 &&
        required.valid && required.queue_depth == 13;
}

bool exact_window_handles_non_integer_interval_frequency() {
    // 16 ms data interval, five packets per transfer, three queued transfers => 240 ms exact window.
    const auto window = mica::usb::iso::ahead_window_period(3, 5, {2, 125});
    const auto required = mica::usb::iso::minimum_queue_depth_for_gap_exact(
        215'000,
        25'000,
        5,
        {2, 125});
    return window.valid() && window.numerator == 6 && window.denominator == 25 &&
        required.valid && required.queue_depth == 3;
}

}  // namespace

int main() {
    const bool production = production_window_covers_captured_gap();
    const bool reference = reference_sized_window_covers_captured_gap();
    const bool exact_sk02 = exact_service_period_matches_sk02_window();
    const bool exact_non_integer = exact_window_handles_non_integer_interval_frequency();
    std::cout << "productionCoverage=" << (production ? "pass" : "fail") << '\n'
              << "referenceCoverage=" << (reference ? "pass" : "fail") << '\n'
              << "exactSk02Window=" << (exact_sk02 ? "pass" : "fail") << '\n'
              << "exactNonIntegerWindow=" << (exact_non_integer ? "pass" : "fail") << '\n';
    return production && reference && exact_sk02 && exact_non_integer ? 0 : 1;
}
