#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>

struct Sk02PacketScheduleMetrics {
    std::uint64_t total_packets = 0;
    std::uint64_t total_frames = 0;
    std::uint64_t total_requests = 0;
    std::uint64_t out_of_nominal_request_count = 0;
    std::uint64_t consecutive_out_of_nominal_requests = 0;
    std::uint64_t maximum_consecutive_out_of_nominal_requests = 0;
    std::uint32_t minimum_frames_per_packet = std::numeric_limits<std::uint32_t>::max();
    std::uint32_t maximum_frames_per_packet = 0;
    std::uint32_t maximum_packet_frame_step = 0;
    std::int64_t schedule_deviation_frames = 0;
    std::uint64_t maximum_absolute_schedule_deviation_frames = 0;

    void observe_request(
        const std::uint32_t* frames,
        const std::size_t packet_count,
        const std::uint32_t sample_rate_hz) {
        if (frames == nullptr || packet_count == 0 || sample_rate_hz == 0) return;
        std::uint64_t request_frames = 0;
        for (std::size_t index = 0; index < packet_count; ++index) {
            const std::uint32_t current = frames[index];
            minimum_frames_per_packet = std::min(minimum_frames_per_packet, current);
            maximum_frames_per_packet = std::max(maximum_frames_per_packet, current);
            if (has_previous_packet) {
                const std::uint32_t step = current >= previous_packet_frames ?
                    current - previous_packet_frames : previous_packet_frames - current;
                maximum_packet_frame_step = std::max(maximum_packet_frame_step, step);
            }
            has_previous_packet = true;
            previous_packet_frames = current;
            request_frames += current;
        }
        total_packets += packet_count;
        total_frames += request_frames;
        ++total_requests;

        const std::uint64_t nominal_numerator =
            static_cast<std::uint64_t>(sample_rate_hz) * packet_count;
        const std::uint64_t nominal_floor = nominal_numerator / 8'000U;
        const std::uint64_t nominal_ceil = (nominal_numerator + 7'999U) / 8'000U;
        const bool out_of_nominal = request_frames < nominal_floor || request_frames > nominal_ceil;
        if (out_of_nominal) {
            ++out_of_nominal_request_count;
            ++consecutive_out_of_nominal_requests;
            maximum_consecutive_out_of_nominal_requests = std::max(
                maximum_consecutive_out_of_nominal_requests,
                consecutive_out_of_nominal_requests);
        } else {
            consecutive_out_of_nominal_requests = 0;
        }

        const std::uint64_t nominal_total =
            (total_packets * static_cast<std::uint64_t>(sample_rate_hz)) / 8'000U;
        schedule_deviation_frames = static_cast<std::int64_t>(total_frames) -
            static_cast<std::int64_t>(nominal_total);
        const std::uint64_t absolute_deviation = schedule_deviation_frames < 0 ?
            static_cast<std::uint64_t>(-schedule_deviation_frames) :
            static_cast<std::uint64_t>(schedule_deviation_frames);
        maximum_absolute_schedule_deviation_frames = std::max(
            maximum_absolute_schedule_deviation_frames,
            absolute_deviation);
    }

    std::uint32_t published_minimum_frames_per_packet() const {
        return minimum_frames_per_packet == std::numeric_limits<std::uint32_t>::max() ?
            0 : minimum_frames_per_packet;
    }

private:
    bool has_previous_packet = false;
    std::uint32_t previous_packet_frames = 0;
};

struct Sk02PcmContinuityMetrics {
    std::uint64_t observed_frames = 0;
    std::uint64_t zero_frame_count = 0;
    std::uint64_t repeated_frame_count = 0;
    std::uint64_t maximum_consecutive_zero_frames = 0;
    std::uint64_t maximum_consecutive_repeated_frames = 0;
    std::uint64_t duplicate_request_count = 0;
    std::uint64_t maximum_consecutive_duplicate_requests = 0;
    std::uint64_t maximum_adjacent_sample_delta = 0;
    std::uint64_t maximum_request_boundary_sample_delta = 0;

    void observe_request(
        const unsigned char* data,
        const std::size_t byte_count,
        const int bytes_per_frame) {
        if (data == nullptr || byte_count == 0 ||
            (bytes_per_frame != 4 && bytes_per_frame != 8) ||
            byte_count % static_cast<std::size_t>(bytes_per_frame) != 0) return;

        const std::uint64_t request_hash = fnv1a(data, byte_count);
        if (has_previous_request && previous_request_bytes == byte_count &&
            previous_request_hash == request_hash) {
            ++duplicate_request_count;
            ++consecutive_duplicate_requests;
            maximum_consecutive_duplicate_requests = std::max(
                maximum_consecutive_duplicate_requests,
                consecutive_duplicate_requests);
        } else {
            consecutive_duplicate_requests = 0;
        }
        has_previous_request = true;
        previous_request_hash = request_hash;
        previous_request_bytes = byte_count;

        const std::size_t frame_count = byte_count / static_cast<std::size_t>(bytes_per_frame);
        for (std::size_t frame = 0; frame < frame_count; ++frame) {
            const unsigned char* current = data + frame * bytes_per_frame;
            const std::uint64_t frame_key = read_frame_key(current, bytes_per_frame);
            const bool zero = frame_key == 0;
            if (zero) {
                ++zero_frame_count;
                ++consecutive_zero_frames;
                maximum_consecutive_zero_frames = std::max(
                    maximum_consecutive_zero_frames,
                    consecutive_zero_frames);
            } else {
                consecutive_zero_frames = 0;
            }

            if (has_previous_frame && frame_key == previous_frame_key) {
                ++repeated_frame_count;
                ++consecutive_repeated_frames;
                maximum_consecutive_repeated_frames = std::max(
                    maximum_consecutive_repeated_frames,
                    consecutive_repeated_frames);
            } else {
                consecutive_repeated_frames = 0;
            }

            if (has_previous_frame) {
                const std::uint64_t delta = maximum_channel_delta(
                    previous_frame_key,
                    frame_key,
                    bytes_per_frame);
                maximum_adjacent_sample_delta = std::max(maximum_adjacent_sample_delta, delta);
                if (frame == 0) {
                    maximum_request_boundary_sample_delta = std::max(
                        maximum_request_boundary_sample_delta,
                        delta);
                }
            }
            has_previous_frame = true;
            previous_frame_key = frame_key;
            ++observed_frames;
        }
    }

private:
    static std::uint64_t fnv1a(const unsigned char* data, const std::size_t byte_count) {
        std::uint64_t hash = 14'695'981'039'346'656'037ULL;
        for (std::size_t index = 0; index < byte_count; ++index) {
            hash ^= data[index];
            hash *= 1'099'511'628'211ULL;
        }
        return hash;
    }

    static std::uint64_t read_frame_key(const unsigned char* frame, const int bytes_per_frame) {
        std::uint64_t value = 0;
        for (int index = 0; index < bytes_per_frame; ++index) {
            value |= static_cast<std::uint64_t>(frame[index]) << (index * 8);
        }
        return value;
    }

    static std::int64_t signed_sample(
        const std::uint64_t frame,
        const int channel,
        const int bytes_per_frame) {
        if (bytes_per_frame == 4) {
            const auto raw = static_cast<std::uint16_t>(frame >> (channel * 16));
            return static_cast<std::int16_t>(raw);
        }
        const auto raw = static_cast<std::uint32_t>(frame >> (channel * 32));
        return static_cast<std::int32_t>(raw);
    }

    static std::uint64_t maximum_channel_delta(
        const std::uint64_t previous,
        const std::uint64_t current,
        const int bytes_per_frame) {
        std::uint64_t maximum = 0;
        for (int channel = 0; channel < 2; ++channel) {
            const std::int64_t difference =
                signed_sample(current, channel, bytes_per_frame) -
                signed_sample(previous, channel, bytes_per_frame);
            const std::uint64_t absolute = difference < 0 ?
                static_cast<std::uint64_t>(-difference) :
                static_cast<std::uint64_t>(difference);
            maximum = std::max(maximum, absolute);
        }
        return maximum;
    }

    bool has_previous_frame = false;
    std::uint64_t previous_frame_key = 0;
    std::uint64_t consecutive_zero_frames = 0;
    std::uint64_t consecutive_repeated_frames = 0;
    bool has_previous_request = false;
    std::uint64_t previous_request_hash = 0;
    std::size_t previous_request_bytes = 0;
    std::uint64_t consecutive_duplicate_requests = 0;
};
