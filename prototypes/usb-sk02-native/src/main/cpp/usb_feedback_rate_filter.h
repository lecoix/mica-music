#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>

namespace mica::usb::feedback {

struct RateBounds {
    std::uint64_t minimum_q16 = 0;
    std::uint64_t maximum_q16 = 0;

    constexpr bool valid() const {
        return minimum_q16 > 0 && maximum_q16 >= minimum_q16;
    }

    constexpr bool accepts(const std::uint64_t rate_q16) const {
        return valid() && rate_q16 >= minimum_q16 && rate_q16 <= maximum_q16;
    }
};

/**
 * Device-agnostic five-sample median plus quarter-step slew estimator.
 *
 * This remains diagnostics/counterfactual policy for the current SK02 path; raw explicit feedback
 * is still authoritative there. P4 host stress uses the same estimator so later P3 transport work
 * can change that policy without duplicating the math.
 */
class MedianSlewRateFilter {
public:
    explicit MedianSlewRateFilter(const std::uint64_t nominal_q16)
        : trusted_q16_(nominal_q16) {}

    std::uint64_t ingest(const std::uint64_t raw_q16) {
        if (history_size_ < history_.size()) {
            history_[history_size_++] = raw_q16;
        } else {
            std::move(history_.begin() + 1, history_.end(), history_.begin());
            history_.back() = raw_q16;
        }
        if (history_size_ < 3) return trusted_q16_;

        std::array<std::uint64_t, 5> sorted{};
        std::copy_n(history_.begin(), history_size_, sorted.begin());
        std::sort(sorted.begin(), sorted.begin() + static_cast<std::ptrdiff_t>(history_size_));
        const std::uint64_t target = sorted[history_size_ / 2];
        if (target == trusted_q16_) return trusted_q16_;

        const bool increasing = target > trusted_q16_;
        const std::uint64_t difference = increasing ?
            target - trusted_q16_ : trusted_q16_ - target;
        const std::uint64_t step = std::max<std::uint64_t>(1ULL, (difference + 3ULL) / 4ULL);
        trusted_q16_ = increasing ? trusted_q16_ + step : trusted_q16_ - step;
        return trusted_q16_;
    }

    std::uint64_t trusted_q16() const { return trusted_q16_; }
    std::size_t history_size() const { return history_size_; }

private:
    std::array<std::uint64_t, 5> history_{};
    std::size_t history_size_ = 0;
    std::uint64_t trusted_q16_ = 0;
};

}  // namespace mica::usb::feedback
