#include "usb_iso_scheduler.h"

#include <array>
#include <cstdint>
#include <iostream>
#include <limits>

namespace {

constexpr std::uint64_t kProjectionSeconds = 72ULL * 60ULL * 60ULL;

struct ProjectionCase {
    const char* name;
    std::uint32_t sample_rate_hz;
    mica::usb::iso::SchedulerConfig config;
};

bool seventy_two_hour_rational_projection_conserves_frames() {
    constexpr std::array<ProjectionCase, 7> cases{{
        {"hs-44k1-pcm16", 44'100, {8'000, 4, 200}},
        {"hs-48k-pcm16", 48'000, {8'000, 4, 200}},
        {"hs-96k-pcm24", 96'000, {8'000, 6, 300}},
        {"hs-192k-pcm32", 192'000, {8'000, 8, 400}},
        {"hs-384k-pcm32", 384'000, {8'000, 8, 400}},
        {"fs-44k1-pcm16", 44'100, {1'000, 4, 1023}},
        {"fs-96k-pcm24", 96'000, {1'000, 6, 1023}},
    }};

    for (const auto& test : cases) {
        const std::uint64_t intervals =
            kProjectionSeconds * test.config.service_intervals_per_second;
        const auto projection = mica::usb::iso::project_sample_rate(
            test.config,
            intervals,
            test.sample_rate_hz);
        const std::uint64_t expected_frames =
            kProjectionSeconds * static_cast<std::uint64_t>(test.sample_rate_hz);
        std::cout << "projection=" << test.name
                  << " intervals=" << intervals
                  << " frames=" << projection.total_frames
                  << " expected=" << expected_frames
                  << " maxFramesPerInterval=" << projection.maximum_frames_per_interval
                  << " capacityFrames=" << test.config.max_frames_per_packet()
                  << " finalPhase=" << projection.final_phase_numerator << '\n';
        if (!projection.valid || !projection.capacity_sufficient ||
            projection.total_frames != expected_frames ||
            projection.final_phase_numerator != 0) {
            return false;
        }
    }
    return true;
}

bool q16_projection_matches_packet_accumulator_exactly() {
    constexpr mica::usb::iso::SchedulerConfig config{8'000, 8, 400};
    const std::uint64_t intervals = kProjectionSeconds * config.service_intervals_per_second;
    const std::uint64_t rate_q16 = mica::usb::iso::floor_q16_rate(48'000, 8'000);
    const auto projection = mica::usb::iso::project_constant_q16_rate(
        config,
        intervals,
        rate_q16);
    const std::uint64_t expected_frames = kProjectionSeconds * 48'000ULL;
    return projection.valid && projection.capacity_sufficient &&
        projection.total_frames == expected_frames && projection.final_phase_q16 == 0;
}

bool fractional_q16_quantization_is_visible_not_hidden() {
    constexpr mica::usb::iso::SchedulerConfig config{8'000, 4, 200};
    const std::uint64_t intervals = kProjectionSeconds * config.service_intervals_per_second;
    const std::uint64_t rate_q16 = mica::usb::iso::floor_q16_rate(44'100, 8'000);
    const auto projection = mica::usb::iso::project_constant_q16_rate(
        config,
        intervals,
        rate_q16);
    const std::uint64_t ideal_frames = kProjectionSeconds * 44'100ULL;
    if (!projection.valid || projection.total_frames >= ideal_frames) return false;

    const std::uint64_t drift_frames = ideal_frames - projection.total_frames;
    const std::uint64_t worst_case_floor_bound = intervals / mica::usb::iso::kQ16One + 1ULL;
    std::cout << "q16Quantization sampleRate=44100 q16=" << rate_q16
              << " projectedFrames=" << projection.total_frames
              << " idealFrames=" << ideal_frames
              << " driftFrames=" << drift_frames
              << " conservativeBound=" << worst_case_floor_bound << '\n';
    return drift_frames > 0 && drift_frames <= worst_case_floor_bound;
}

bool insufficient_capacity_fails_projection_gate() {
    constexpr mica::usb::iso::SchedulerConfig too_small{8'000, 8, 300};
    const auto projection = mica::usb::iso::project_sample_rate(
        too_small,
        8'000,
        384'000);
    return projection.valid && !projection.capacity_sufficient &&
        projection.maximum_frames_per_interval == 48 &&
        too_small.max_frames_per_packet() == 37;
}

bool counter_overflow_fails_closed() {
    constexpr mica::usb::iso::SchedulerConfig config{8'000, 8, 400};
    const auto rational = mica::usb::iso::project_sample_rate(
        config,
        std::numeric_limits<std::uint64_t>::max(),
        384'000);
    const auto q16 = mica::usb::iso::project_constant_q16_rate(
        config,
        std::numeric_limits<std::uint64_t>::max(),
        mica::usb::iso::floor_q16_rate(384'000, 8'000));
    return !rational.valid && !q16.valid;
}

}  // namespace

int main() {
    const bool rational = seventy_two_hour_rational_projection_conserves_frames();
    const bool q16 = q16_projection_matches_packet_accumulator_exactly();
    const bool quantization = fractional_q16_quantization_is_visible_not_hidden();
    const bool capacity = insufficient_capacity_fails_projection_gate();
    const bool overflow = counter_overflow_fails_closed();
    std::cout << "rational72h=" << (rational ? "pass" : "fail") << '\n'
              << "q16Accumulator=" << (q16 ? "pass" : "fail") << '\n'
              << "q16Quantization=" << (quantization ? "pass" : "fail") << '\n'
              << "capacityGate=" << (capacity ? "pass" : "fail") << '\n'
              << "overflowGate=" << (overflow ? "pass" : "fail") << '\n';
    return rational && q16 && quantization && capacity && overflow ? 0 : 1;
}
