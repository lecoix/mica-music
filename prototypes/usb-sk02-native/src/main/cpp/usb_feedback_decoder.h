#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>

#include "usb_iso_timing.h"

namespace mica::usb::feedback {

struct FixedPointFormat {
    std::uint8_t byte_count = 0;
    std::uint8_t fractional_bits = 0;

    constexpr bool valid() const {
        return byte_count > 0 && byte_count <= 8 && fractional_bits < 64 &&
            fractional_bits <= byte_count * 8U;
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

enum class RawTimeUnit : std::uint8_t {
    FramesPerBusFrame = 0,
    FramesPerMicroframe = 1,
};

struct DecodeNormalizationProfile {
    FixedPointFormat fixed_point{};
    RawTimeUnit raw_time_unit = RawTimeUnit::FramesPerBusFrame;
    std::uint64_t raw_to_data_interval_numerator = 0;
    std::uint64_t raw_to_data_interval_denominator = 0;
    iso::ServicePeriod feedback_poll_period{};
    std::uint64_t required_zero_mask = 0;

    constexpr bool raw_time_unit_valid() const {
        return raw_time_unit == RawTimeUnit::FramesPerBusFrame ||
            raw_time_unit == RawTimeUnit::FramesPerMicroframe;
    }

    constexpr std::uint64_t payload_mask() const {
        if (fixed_point.byte_count == 0 || fixed_point.byte_count > 8) return 0;
        return fixed_point.byte_count == 8 ? std::numeric_limits<std::uint64_t>::max() :
            ((1ULL << (fixed_point.byte_count * 8U)) - 1ULL);
    }

    constexpr bool valid() const {
        return fixed_point.valid() && raw_time_unit_valid() &&
            raw_to_data_interval_numerator > 0 && raw_to_data_interval_denominator > 0 &&
            feedback_poll_period.valid() &&
            (required_zero_mask & ~payload_mask()) == 0;
    }
};

/** Exact frames per data service interval: numerator / denominator. */
struct NormalizedFeedbackRate {
    bool valid = false;
    std::uint64_t numerator = 0;
    std::uint64_t denominator = 0;
    RawTimeUnit raw_time_unit = RawTimeUnit::FramesPerBusFrame;
    iso::ServicePeriod feedback_poll_period{};
};

inline NormalizedFeedbackRate decode_and_normalize_unsigned_le(
    const unsigned char* const data,
    const std::size_t actual_length,
    const DecodeNormalizationProfile profile) {
    NormalizedFeedbackRate result{};
    if (!profile.valid()) return result;

    const DecodeResult decoded = decode_unsigned_le(data, actual_length, profile.fixed_point);
    if (!decoded.valid || (decoded.raw_value & profile.required_zero_mask) != 0) return result;

    const std::uint64_t fixed_denominator = 1ULL << decoded.fractional_bits;
    const std::uint64_t raw_scale_gcd =
        iso::gcd_u64(decoded.raw_value, profile.raw_to_data_interval_denominator);
    const std::uint64_t scale_fixed_gcd =
        iso::gcd_u64(profile.raw_to_data_interval_numerator, fixed_denominator);

    const std::uint64_t raw_reduced = decoded.raw_value / raw_scale_gcd;
    const std::uint64_t scale_denominator_reduced =
        profile.raw_to_data_interval_denominator / raw_scale_gcd;
    const std::uint64_t scale_numerator_reduced =
        profile.raw_to_data_interval_numerator / scale_fixed_gcd;
    const std::uint64_t fixed_denominator_reduced = fixed_denominator / scale_fixed_gcd;

    std::uint64_t numerator = 0;
    std::uint64_t denominator = 0;
    if (!iso::checked_multiply_u64(raw_reduced, scale_numerator_reduced, &numerator) ||
        !iso::checked_multiply_u64(
            fixed_denominator_reduced,
            scale_denominator_reduced,
            &denominator) ||
        denominator == 0) {
        return result;
    }

    const std::uint64_t divisor = iso::gcd_u64(numerator, denominator);
    result.valid = true;
    result.numerator = numerator / divisor;
    result.denominator = denominator / divisor;
    result.raw_time_unit = profile.raw_time_unit;
    result.feedback_poll_period = iso::reduced_period(profile.feedback_poll_period);
    return result;
}

struct FixedPointRateResult {
    bool valid = false;
    std::uint64_t value = 0;
    std::uint8_t fractional_bits = 0;
};

/** Convert an exact normalized rate without rounding. Non-representable values fail closed. */
inline FixedPointRateResult to_fixed_point_exact(
    const NormalizedFeedbackRate rate,
    const std::uint8_t target_fractional_bits) {
    FixedPointRateResult result{};
    if (!rate.valid || rate.denominator == 0 || target_fractional_bits >= 64) return result;

    const std::uint64_t scale = 1ULL << target_fractional_bits;
    const std::uint64_t divisor = iso::gcd_u64(rate.denominator, scale);
    const std::uint64_t denominator_reduced = rate.denominator / divisor;
    const std::uint64_t scale_reduced = scale / divisor;
    if (denominator_reduced == 0 || rate.numerator % denominator_reduced != 0) return result;

    std::uint64_t value = 0;
    if (!iso::checked_multiply_u64(
            rate.numerator / denominator_reduced,
            scale_reduced,
            &value)) {
        return result;
    }

    result.valid = true;
    result.value = value;
    result.fractional_bits = target_fractional_bits;
    return result;
}

}  // namespace mica::usb::feedback
