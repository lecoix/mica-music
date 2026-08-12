#include "usb_feedback_rate_filter.h"
#include "usb_iso_scheduler.h"

#include <array>
#include <cstdint>
#include <iostream>
#include <limits>

namespace {

constexpr std::uint64_t kSeed = 0x4d49434150344131ULL;  // "MICAP4A1"
constexpr std::uint32_t kIterationsPerScenario = 100'000;

class DeterministicRng {
public:
    explicit DeterministicRng(const std::uint64_t seed) : state_(seed == 0 ? 1 : seed) {}

    std::uint64_t next() {
        std::uint64_t value = state_;
        value ^= value << 13U;
        value ^= value >> 7U;
        value ^= value << 17U;
        state_ = value;
        return value;
    }

    std::int64_t symmetric(const std::uint64_t magnitude) {
        const std::uint64_t span = magnitude * 2ULL + 1ULL;
        return static_cast<std::int64_t>(next() % span) - static_cast<std::int64_t>(magnitude);
    }

private:
    std::uint64_t state_;
};

struct StressCase {
    const char* name;
    std::uint32_t sample_rate_hz;
    mica::usb::iso::SchedulerConfig config;
};

bool scheduler_stress(const StressCase& test, const std::uint64_t seed) {
    DeterministicRng rng(seed);
    mica::usb::iso::PacketScheduler scheduler(test.config);
    const std::uint64_t nominal = mica::usb::iso::floor_q16_rate(
        test.sample_rate_hz,
        test.config.service_intervals_per_second);
    std::uint64_t reference_q16 = 0;

    for (std::uint32_t iteration = 0; iteration < kIterationsPerScenario; ++iteration) {
        const bool correction_burst = iteration % 997U == 0;
        const std::int64_t jitter = rng.symmetric(correction_burst ? 2'048ULL : 32ULL);
        const std::int64_t candidate = static_cast<std::int64_t>(nominal) + jitter;
        if (candidate <= 0) {
            std::cerr << "seed=" << seed << " scenario=" << test.name
                      << " iteration=" << iteration << " invalidCandidate=" << candidate << '\n';
            return false;
        }
        const std::uint64_t rate_q16 = static_cast<std::uint64_t>(candidate);
        if (reference_q16 > std::numeric_limits<std::uint64_t>::max() - rate_q16) return false;
        reference_q16 += rate_q16;

        const auto scheduled = scheduler.next(rate_q16);
        const std::uint64_t expected_frames = reference_q16 >> 16U;
        const std::uint32_t expected_phase = static_cast<std::uint32_t>(reference_q16 & 0xffffULL);
        if (!scheduled.valid || scheduled.capacity_limited ||
            scheduler.requested_frames() != expected_frames ||
            scheduler.scheduled_frames() != expected_frames ||
            scheduler.phase_q16() != expected_phase ||
            scheduled.scheduled_bytes > test.config.max_packet_bytes) {
            std::cerr << "seed=" << seed << " scenario=" << test.name
                      << " iteration=" << iteration
                      << " rateQ16=" << rate_q16
                      << " requested=" << scheduled.requested_frames
                      << " scheduled=" << scheduled.scheduled_frames
                      << " bytes=" << scheduled.scheduled_bytes
                      << " phase=" << scheduler.phase_q16()
                      << " expectedFrames=" << expected_frames
                      << " expectedPhase=" << expected_phase
                      << " capacityLimited=" << scheduled.capacity_limited << '\n';
            return false;
        }
    }

    std::cout << "stress=" << test.name
              << " seed=" << seed
              << " iterations=" << kIterationsPerScenario
              << " packets=" << scheduler.scheduled_packets()
              << " frames=" << scheduler.scheduled_frames()
              << " capacityLimited=" << scheduler.capacity_limited_packets() << '\n';
    return true;
}

bool feedback_filter_stress(const std::uint64_t seed) {
    DeterministicRng rng(seed);
    constexpr std::uint64_t nominal = 393'216ULL;
    constexpr mica::usb::feedback::RateBounds bounds{
        nominal - 8'192ULL,
        nominal + 8'192ULL,
    };
    mica::usb::feedback::MedianSlewRateFilter filter(nominal);

    for (std::uint32_t iteration = 0; iteration < kIterationsPerScenario; ++iteration) {
        const bool isolated_spike = iteration % 1'009U == 0;
        const std::int64_t noise = rng.symmetric(isolated_spike ? 4'096ULL : 96ULL);
        const std::uint64_t raw = static_cast<std::uint64_t>(
            static_cast<std::int64_t>(nominal) + noise);
        if (!bounds.accepts(raw)) {
            std::cerr << "seed=" << seed << " feedback iteration=" << iteration
                      << " rawRejectedUnexpectedly=" << raw << '\n';
            return false;
        }
        const std::uint64_t before = filter.trusted_q16();
        const std::uint64_t trusted = filter.ingest(raw);
        if (!bounds.accepts(trusted)) {
            std::cerr << "seed=" << seed << " feedback iteration=" << iteration
                      << " trustedOutOfBounds=" << trusted << '\n';
            return false;
        }
        const std::uint64_t movement = trusted >= before ? trusted - before : before - trusted;
        const std::uint64_t raw_distance = raw >= before ? raw - before : before - raw;
        if (filter.history_size() >= 3 && movement > raw_distance + 8'192ULL) {
            std::cerr << "seed=" << seed << " feedback iteration=" << iteration
                      << " implausibleSlew movement=" << movement
                      << " rawDistance=" << raw_distance << '\n';
            return false;
        }
    }

    const bool rejects_zero = !bounds.accepts(0);
    const bool rejects_high = !bounds.accepts(bounds.maximum_q16 + 1ULL);
    std::cout << "feedbackStress seed=" << seed
              << " iterations=" << kIterationsPerScenario
              << " trustedQ16=" << filter.trusted_q16()
              << " rejectsZero=" << rejects_zero
              << " rejectsHigh=" << rejects_high << '\n';
    return rejects_zero && rejects_high;
}

}  // namespace

int main() {
    constexpr std::array<StressCase, 6> cases{{
        {"hs-44k1-pcm16", 44'100, {8'000, 4, 200}},
        {"hs-48k-pcm16", 48'000, {8'000, 4, 200}},
        {"hs-96k-pcm24", 96'000, {8'000, 6, 300}},
        {"hs-192k-pcm32", 192'000, {8'000, 8, 400}},
        {"hs-384k-pcm32", 384'000, {8'000, 8, 400}},
        {"fs-96k-pcm24", 96'000, {1'000, 6, 1023}},
    }};

    bool passed = true;
    for (std::size_t index = 0; index < cases.size(); ++index) {
        passed = scheduler_stress(cases[index], kSeed + index * 0x9e3779b97f4a7c15ULL) && passed;
    }
    passed = feedback_filter_stress(kSeed ^ 0xa5a5a5a5a5a5a5a5ULL) && passed;
    std::cout << "fixedSeedStress=" << (passed ? "pass" : "fail") << '\n';
    return passed ? 0 : 1;
}
