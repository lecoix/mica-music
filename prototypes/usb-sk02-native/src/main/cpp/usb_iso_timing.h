#pragma once

#include <cstdint>
#include <limits>

namespace mica::usb::iso {

/** Exact duration in seconds: numerator / denominator. */
struct ServicePeriod {
    std::uint64_t numerator = 0;
    std::uint64_t denominator = 0;

    constexpr bool valid() const {
        return numerator > 0 && denominator > 0;
    }
};

inline bool checked_multiply_u64(
    const std::uint64_t left,
    const std::uint64_t right,
    std::uint64_t* const result) {
    if (result == nullptr) return false;
    if (left != 0 && right > std::numeric_limits<std::uint64_t>::max() / left) return false;
    *result = left * right;
    return true;
}

inline bool checked_add_u64(
    const std::uint64_t left,
    const std::uint64_t right,
    std::uint64_t* const result) {
    if (result == nullptr || right > std::numeric_limits<std::uint64_t>::max() - left) return false;
    *result = left + right;
    return true;
}

constexpr std::uint64_t gcd_u64(std::uint64_t left, std::uint64_t right) {
    while (right != 0) {
        const std::uint64_t remainder = left % right;
        left = right;
        right = remainder;
    }
    return left;
}

inline ServicePeriod reduced_period(const ServicePeriod period) {
    if (!period.valid()) return {};
    const std::uint64_t divisor = gcd_u64(period.numerator, period.denominator);
    return {period.numerator / divisor, period.denominator / divisor};
}

}  // namespace mica::usb::iso
