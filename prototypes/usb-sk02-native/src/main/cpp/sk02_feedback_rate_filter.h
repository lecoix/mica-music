#pragma once

#include <algorithm>
#include <array>
#include <cstddef>

/**
 * Session-local SK02 explicit-feedback estimator.
 *
 * The raw endpoint value remains observable, but packet sizing consumes a five-sample median
 * followed by a quarter-step slew. Holding the nominal rate until three observations prevents a
 * startup sample from immediately steering the USB schedule.
 */
class Sk02FeedbackRateFilter {
public:
    explicit Sk02FeedbackRateFilter(const unsigned long nominal_q16)
        : trusted_q16_(nominal_q16) {}

    unsigned long ingest(const unsigned long raw_q16) {
        if (history_size_ < history_.size()) {
            history_[history_size_++] = raw_q16;
        } else {
            std::move(history_.begin() + 1, history_.end(), history_.begin());
            history_.back() = raw_q16;
        }
        if (history_size_ < 3) return trusted_q16_;

        std::array<unsigned long, 5> sorted{};
        std::copy_n(history_.begin(), history_size_, sorted.begin());
        std::sort(sorted.begin(), sorted.begin() + static_cast<std::ptrdiff_t>(history_size_));
        const unsigned long target = sorted[history_size_ / 2];
        if (target == trusted_q16_) return trusted_q16_;

        const bool increasing = target > trusted_q16_;
        const unsigned long difference = increasing ?
            target - trusted_q16_ : trusted_q16_ - target;
        const unsigned long step = std::max(1UL, (difference + 3UL) / 4UL);
        trusted_q16_ = increasing ? trusted_q16_ + step : trusted_q16_ - step;
        return trusted_q16_;
    }

    unsigned long trusted_q16() const { return trusted_q16_; }

private:
    std::array<unsigned long, 5> history_{};
    std::size_t history_size_ = 0;
    unsigned long trusted_q16_;
};
