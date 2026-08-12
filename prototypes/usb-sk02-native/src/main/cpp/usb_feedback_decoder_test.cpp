#include "sk02_feedback_profile.h"
#include "usb_feedback_decoder.h"

#include <array>
#include <cstdint>
#include <iostream>

namespace {

bool decodes_four_byte_q16_little_endian() {
    constexpr mica::usb::feedback::FixedPointFormat format{4, 16};
    constexpr std::array<unsigned char, 4> bytes{0x00, 0x00, 0x06, 0x00};
    const auto result = mica::usb::feedback::decode_unsigned_le(bytes.data(), bytes.size(), format);
    return result.valid && result.raw_value == 393'216ULL && result.fractional_bits == 16;
}

bool decoder_is_not_hardcoded_to_sk02_packet_shape() {
    constexpr mica::usb::feedback::FixedPointFormat format{3, 14};
    constexpr std::array<unsigned char, 3> bytes{0x00, 0x80, 0x01};
    const auto result = mica::usb::feedback::decode_unsigned_le(bytes.data(), bytes.size(), format);
    return result.valid && result.raw_value == 98'304ULL && result.fractional_bits == 14;
}

bool sk02_profile_preserves_current_q16_behavior() {
    constexpr std::array<unsigned char, 4> bytes{0x00, 0x00, 0x06, 0x00};
    const auto normalized = mica::usb::feedback::decode_and_normalize_unsigned_le(
        bytes.data(), bytes.size(), sk02::feedback::kProfile);
    const auto q16 = mica::usb::feedback::to_fixed_point_exact(normalized, 16);
    return normalized.valid && normalized.numerator == 6 && normalized.denominator == 1 &&
        normalized.raw_time_unit == mica::usb::feedback::RawTimeUnit::FramesPerMicroframe &&
        normalized.feedback_poll_period.numerator == 1 &&
        normalized.feedback_poll_period.denominator == 1'000 &&
        q16.valid && q16.value == 393'216ULL;
}

bool data_interval_scaling_is_explicit() {
    constexpr std::array<unsigned char, 4> bytes{0x00, 0x00, 0x06, 0x00};
    constexpr mica::usb::feedback::DecodeNormalizationProfile four_microframe_data_interval{
        {4, 16},
        mica::usb::feedback::RawTimeUnit::FramesPerMicroframe,
        4,
        1,
        {8, 8'000},
        0,
    };
    const auto normalized = mica::usb::feedback::decode_and_normalize_unsigned_le(
        bytes.data(), bytes.size(), four_microframe_data_interval);
    const auto q16 = mica::usb::feedback::to_fixed_point_exact(normalized, 16);
    return normalized.valid && normalized.numerator == 24 && normalized.denominator == 1 &&
        q16.valid && q16.value == 24ULL * 65'536ULL;
}

bool feedback_poll_period_does_not_change_raw_rate_unit() {
    constexpr std::array<unsigned char, 4> bytes{0x00, 0x00, 0x06, 0x00};
    constexpr mica::usb::feedback::DecodeNormalizationProfile poll_every_eight_microframes{
        {4, 16},
        mica::usb::feedback::RawTimeUnit::FramesPerMicroframe,
        4,
        1,
        {8, 8'000},
        0,
    };
    constexpr mica::usb::feedback::DecodeNormalizationProfile poll_every_sixteen_microframes{
        {4, 16},
        mica::usb::feedback::RawTimeUnit::FramesPerMicroframe,
        4,
        1,
        {16, 8'000},
        0,
    };
    const auto first = mica::usb::feedback::decode_and_normalize_unsigned_le(
        bytes.data(), bytes.size(), poll_every_eight_microframes);
    const auto second = mica::usb::feedback::decode_and_normalize_unsigned_le(
        bytes.data(), bytes.size(), poll_every_sixteen_microframes);
    return first.valid && second.valid &&
        first.numerator == second.numerator && first.denominator == second.denominator &&
        first.feedback_poll_period.denominator != second.feedback_poll_period.denominator;
}

bool malformed_length_reserved_bits_and_profiles_fail_closed() {
    constexpr std::array<unsigned char, 4> normal{0x00, 0x00, 0x06, 0x00};
    constexpr std::array<unsigned char, 4> reserved_set{0x00, 0x00, 0x06, 0x80};
    constexpr mica::usb::feedback::DecodeNormalizationProfile reserved_top_bit{
        {4, 16},
        mica::usb::feedback::RawTimeUnit::FramesPerMicroframe,
        1,
        1,
        {8, 8'000},
        0x8000'0000ULL,
    };
    constexpr mica::usb::feedback::DecodeNormalizationProfile zero_scale{
        {4, 16},
        mica::usb::feedback::RawTimeUnit::FramesPerMicroframe,
        0,
        1,
        {8, 8'000},
        0,
    };
    constexpr mica::usb::feedback::DecodeNormalizationProfile mask_outside_payload{
        {3, 14},
        mica::usb::feedback::RawTimeUnit::FramesPerBusFrame,
        1,
        1,
        {1, 1'000},
        0x0100'0000ULL,
    };
    const auto short_packet = mica::usb::feedback::decode_and_normalize_unsigned_le(
        normal.data(), 3, reserved_top_bit);
    const auto reserved = mica::usb::feedback::decode_and_normalize_unsigned_le(
        reserved_set.data(), reserved_set.size(), reserved_top_bit);
    const auto bad_scale = mica::usb::feedback::decode_and_normalize_unsigned_le(
        normal.data(), normal.size(), zero_scale);
    const auto bad_mask = mica::usb::feedback::decode_and_normalize_unsigned_le(
        normal.data(), 3, mask_outside_payload);
    const auto invalid_fractional_width = mica::usb::feedback::decode_unsigned_le(
        normal.data(), normal.size(), mica::usb::feedback::FixedPointFormat{4, 33});
    return !short_packet.valid && !reserved.valid && !bad_scale.valid && !bad_mask.valid &&
        !invalid_fractional_width.valid;
}

}  // namespace

int main() {
    const bool q16 = decodes_four_byte_q16_little_endian();
    const bool generic = decoder_is_not_hardcoded_to_sk02_packet_shape();
    const bool sk02 = sk02_profile_preserves_current_q16_behavior();
    const bool scale = data_interval_scaling_is_explicit();
    const bool poll = feedback_poll_period_does_not_change_raw_rate_unit();
    const bool reject = malformed_length_reserved_bits_and_profiles_fail_closed();
    std::cout << "q16le=" << (q16 ? "pass" : "fail") << '\n'
              << "genericShape=" << (generic ? "pass" : "fail") << '\n'
              << "sk02Profile=" << (sk02 ? "pass" : "fail") << '\n'
              << "dataIntervalScale=" << (scale ? "pass" : "fail") << '\n'
              << "pollCadenceIndependent=" << (poll ? "pass" : "fail") << '\n'
              << "failClosed=" << (reject ? "pass" : "fail") << '\n';
    return q16 && generic && sk02 && scale && poll && reject ? 0 : 1;
}
