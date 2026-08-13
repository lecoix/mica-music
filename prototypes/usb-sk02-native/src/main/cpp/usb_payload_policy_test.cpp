#include "usb_payload_policy.h"
#include "usb_iso_scheduler.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

using mica::usb::payload::Policy;

void pcm_full_buffer_is_unchanged() {
    std::array<unsigned char, 12> buffer{
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
        0x20, 0x21, 0x22, 0x23, 0x24, 0x25,
    };
    const auto before = buffer;
    const auto result = mica::usb::payload::finalize_scheduled_payload(
        Policy::PcmZeroFill,
        buffer.data(),
        buffer.size(),
        buffer.size(),
        6);
    assert(result.ready_for_submit);
    assert(result.synthesized_bytes == 0);
    assert(result.stream_error_code == 0);
    assert(buffer == before);
}

void pcm_shortage_zero_fills_exactly_as_before() {
    std::array<unsigned char, 12> buffer{
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
        0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e,
    };
    const auto result = mica::usb::payload::finalize_scheduled_payload(
        Policy::PcmZeroFill,
        buffer.data(),
        buffer.size(),
        6,
        6);
    assert(result.ready_for_submit);
    assert(result.synthesized_bytes == 6);
    assert(result.stream_error_code == 0);
    for (std::size_t index = 0; index < 6; ++index) {
        assert(buffer[index] == static_cast<unsigned char>(0x10 + index));
    }
    for (std::size_t index = 6; index < buffer.size(); ++index) {
        assert(buffer[index] == 0);
    }
}

void exact_full_buffer_is_byte_identical_and_frame_aligned() {
    std::array<unsigned char, 12> buffer{
        0x05, 0xaa, 0xbb, 0xfa, 0xcc, 0xdd,
        0x05, 0x01, 0x02, 0xfa, 0x03, 0x04,
    };
    const auto before = buffer;
    const auto result = mica::usb::payload::finalize_scheduled_payload(
        Policy::ExactFramesOnly,
        buffer.data(),
        buffer.size(),
        buffer.size(),
        6);
    assert(result.ready_for_submit);
    assert(result.synthesized_bytes == 0);
    assert(result.stream_error_code == 0);
    assert(buffer == before);
    assert(buffer.size() % 6 == 0);
}

void exact_shortage_fails_without_zero_or_replay_synthesis() {
    std::array<unsigned char, 12> buffer{
        0x05, 0xaa, 0xbb, 0xfa, 0xcc, 0xdd,
        0x7e, 0x7e, 0x7e, 0x7e, 0x7e, 0x7e,
    };
    const auto before = buffer;
    const auto result = mica::usb::payload::finalize_scheduled_payload(
        Policy::ExactFramesOnly,
        buffer.data(),
        buffer.size(),
        6,
        6);
    assert(!result.ready_for_submit);
    assert(result.synthesized_bytes == 0);
    assert(result.stream_error_code == mica::usb::payload::kExactFramesUnderflowError);
    assert(buffer == before);
}

void exact_misaligned_source_write_is_a_stable_stream_error() {
    const auto exact = mica::usb::payload::validate_source_write(
        Policy::ExactFramesOnly,
        7,
        6);
    assert(!exact.accepted);
    assert(exact.stream_error_code == mica::usb::payload::kExactFramesMisalignedInputError);

    const auto pcm = mica::usb::payload::validate_source_write(
        Policy::PcmZeroFill,
        7,
        6);
    assert(!pcm.accepted);
    assert(pcm.stream_error_code == 0);
}

void exact_payload_never_enters_pcm_continuity_metrics() {
    assert(mica::usb::payload::observes_pcm_continuity_metrics(Policy::PcmZeroFill));
    assert(!mica::usb::payload::observes_pcm_continuity_metrics(Policy::ExactFramesOnly));
}

void scheduler_projection_is_identical_between_payload_policies() {
    const mica::usb::iso::ExactNominalSchedulerConfig config{
        mica::usb::iso::ServicePeriod{1, 8'000},
        6,
        300,
    };
    mica::usb::iso::ExactNominalPacketScheduler pcm_scheduler(config, 176'400);
    mica::usb::iso::ExactNominalPacketScheduler exact_scheduler(config, 176'400);
    assert(pcm_scheduler.valid());
    assert(exact_scheduler.valid());

    std::uint64_t pcm_frames = 0;
    std::uint64_t exact_frames = 0;
    for (int index = 0; index < 8'000; ++index) {
        const auto pcm = pcm_scheduler.next();
        const auto exact = exact_scheduler.next();
        assert(pcm.valid && exact.valid);
        assert(!pcm.capacity_limited && !exact.capacity_limited);
        assert(pcm.scheduled_runtime_frames == exact.scheduled_runtime_frames);
        assert(pcm.scheduled_bytes == exact.scheduled_bytes);

        std::vector<unsigned char> pcm_buffer(pcm.scheduled_bytes, 0x33);
        std::vector<unsigned char> exact_buffer(exact.scheduled_bytes, 0x33);
        const auto pcm_fill = mica::usb::payload::finalize_scheduled_payload(
            Policy::PcmZeroFill,
            pcm_buffer.data(),
            pcm_buffer.size(),
            pcm_buffer.size(),
            6);
        const auto exact_fill = mica::usb::payload::finalize_scheduled_payload(
            Policy::ExactFramesOnly,
            exact_buffer.data(),
            exact_buffer.size(),
            exact_buffer.size(),
            6);
        assert(pcm_fill.ready_for_submit);
        assert(exact_fill.ready_for_submit);
        assert(pcm_buffer == exact_buffer);
        pcm_frames += pcm.scheduled_runtime_frames;
        exact_frames += exact.scheduled_runtime_frames;
    }
    assert(pcm_frames == exact_frames);
    assert(pcm_frames == 176'400);
}

}  // namespace

int main() {
    pcm_full_buffer_is_unchanged();
    pcm_shortage_zero_fills_exactly_as_before();
    exact_full_buffer_is_byte_identical_and_frame_aligned();
    exact_shortage_fails_without_zero_or_replay_synthesis();
    exact_misaligned_source_write_is_a_stable_stream_error();
    exact_payload_never_enters_pcm_continuity_metrics();
    scheduler_projection_is_identical_between_payload_policies();
    return 0;
}
