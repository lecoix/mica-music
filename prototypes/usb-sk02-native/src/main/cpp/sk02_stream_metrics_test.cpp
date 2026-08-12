#include "sk02_stream_metrics.h"

#include <array>
#include <cstdint>
#include <iostream>

namespace {

bool packet_schedule_captures_correction_burst() {
    Sk02PacketScheduleMetrics metrics;
    const std::array<std::uint32_t, 8> nominal{6, 6, 6, 6, 6, 6, 6, 6};
    const std::array<std::uint32_t, 8> corrected{6, 6, 6, 7, 6, 6, 6, 6};
    metrics.observe_request(nominal.data(), nominal.size(), 48'000);
    metrics.observe_request(corrected.data(), corrected.size(), 48'000);
    return metrics.total_packets == 16 &&
        metrics.total_frames == 97 &&
        metrics.published_minimum_frames_per_packet() == 6 &&
        metrics.maximum_frames_per_packet == 7 &&
        metrics.maximum_packet_frame_step == 1 &&
        metrics.out_of_nominal_request_count == 1 &&
        metrics.maximum_consecutive_out_of_nominal_requests == 1 &&
        metrics.schedule_deviation_frames == 1 &&
        metrics.maximum_absolute_schedule_deviation_frames == 1;
}

void write_pcm32_frame(
    std::array<unsigned char, 32>& bytes,
    const std::size_t frame,
    const std::int32_t left,
    const std::int32_t right) {
    const std::uint32_t samples[2]{
        static_cast<std::uint32_t>(left),
        static_cast<std::uint32_t>(right),
    };
    for (int channel = 0; channel < 2; ++channel) {
        for (int index = 0; index < 4; ++index) {
            bytes[frame * 8 + channel * 4 + index] =
                static_cast<unsigned char>(samples[channel] >> (index * 8));
        }
    }
}

bool pcm_continuity_captures_zero_repeat_duplicate_and_boundary() {
    Sk02PcmContinuityMetrics metrics;
    std::array<unsigned char, 32> first{};
    write_pcm32_frame(first, 0, 0, 0);
    write_pcm32_frame(first, 1, 0, 0);
    write_pcm32_frame(first, 2, 100, -100);
    write_pcm32_frame(first, 3, 100, -100);
    metrics.observe_request(first.data(), first.size(), 8);
    metrics.observe_request(first.data(), first.size(), 8);

    std::array<unsigned char, 32> second{};
    write_pcm32_frame(second, 0, 1'000, -1'000);
    write_pcm32_frame(second, 1, 1'100, -1'100);
    write_pcm32_frame(second, 2, 1'200, -1'200);
    write_pcm32_frame(second, 3, 1'300, -1'300);
    metrics.observe_request(second.data(), second.size(), 8);

    return metrics.observed_frames == 12 &&
        metrics.zero_frame_count == 4 &&
        metrics.maximum_consecutive_zero_frames == 2 &&
        metrics.repeated_frame_count == 4 &&
        metrics.maximum_consecutive_repeated_frames == 1 &&
        metrics.duplicate_request_count == 1 &&
        metrics.maximum_consecutive_duplicate_requests == 1 &&
        metrics.maximum_adjacent_sample_delta == 900 &&
        metrics.maximum_request_boundary_sample_delta == 900;
}

}  // namespace

int main() {
    const bool packet = packet_schedule_captures_correction_burst();
    const bool pcm = pcm_continuity_captures_zero_repeat_duplicate_and_boundary();
    std::cout << "packet=" << (packet ? "pass" : "fail") << '\n'
              << "pcm=" << (pcm ? "pass" : "fail") << '\n';
    return packet && pcm ? 0 : 1;
}
