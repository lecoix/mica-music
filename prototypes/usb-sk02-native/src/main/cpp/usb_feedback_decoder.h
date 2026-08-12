#pragma once

#include <cstddef>
#include <cstdint>

namespace mica::usb::feedback {

struct FixedPointFormat {
    std::uint8_t byte_count = 0;
    std::uint8_t fractional_bits = 0;

    constexpr bool valid() const {
        return byte_count > 0 && byte_count <= 8 && fractional_bits < 64;
    }
};

struct DecodeResult {
    bool valid = false;
    std::uint64_t raw_value = 0;
    std::uint8_t fractional_bits = 0;
};

/** Decode an unsigned little-endian USB feedback value without assuming UAC1/UAC2 semantics. */
inline DecodeResult decode_unsigned_le(
    const unsigned char* const data,
    const std::size_t actual_length,
    const FixedPointFormat format) {
    DecodeResult result{};
    if (data == nullptr || !format.valid() || actual_length != format.byte_count) return result;

    std::uint64_t raw = 0;
    for (std::size_t index = 0; index < format.byte_count; ++index) {
        raw |= static_cast<std::uint64_t>(data[index]) << (index * 8U);
    }
    result.valid = true;
    result.raw_value = raw;
    result.fractional_bits = format.fractional_bits;
    return result;
}

}  // namespace mica::usb::feedback
