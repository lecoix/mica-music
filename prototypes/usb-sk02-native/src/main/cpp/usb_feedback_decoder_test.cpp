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

bool rejects_short_and_invalid_formats() {
    constexpr std::array<unsigned char, 4> bytes{0, 0, 6, 0};
    const auto short_read = mica::usb::feedback::decode_unsigned_le(
        bytes.data(),
        3,
        mica::usb::feedback::FixedPointFormat{4, 16});
    const auto zero_length_format = mica::usb::feedback::decode_unsigned_le(
        bytes.data(),
        bytes.size(),
        mica::usb::feedback::FixedPointFormat{0, 16});
    const auto oversized_format = mica::usb::feedback::decode_unsigned_le(
        bytes.data(),
        bytes.size(),
        mica::usb::feedback::FixedPointFormat{9, 16});
    const auto null_data = mica::usb::feedback::decode_unsigned_le(
        nullptr,
        bytes.size(),
        mica::usb::feedback::FixedPointFormat{4, 16});
    return !short_read.valid && !zero_length_format.valid && !oversized_format.valid && !null_data.valid;
}

}  // namespace

int main() {
    const bool q16 = decodes_four_byte_q16_little_endian();
    const bool generic = decoder_is_not_hardcoded_to_sk02_packet_shape();
    const bool reject = rejects_short_and_invalid_formats();
    std::cout << "q16le=" << (q16 ? "pass" : "fail") << '\n'
              << "genericShape=" << (generic ? "pass" : "fail") << '\n'
              << "rejectInvalid=" << (reject ? "pass" : "fail") << '\n';
    return q16 && generic && reject ? 0 : 1;
}
