#include "sk02_feedback_rate_filter.h"

#include <cstdio>

namespace {

constexpr unsigned long kNominal = 393'216UL;
constexpr unsigned long kObservedSpike = 395'182UL;

bool isolated_spike_does_not_steer_schedule() {
    Sk02FeedbackRateFilter filter(kNominal);
    for (int index = 0; index < 5; ++index) {
        if (filter.ingest(kNominal) != kNominal) return false;
    }
    if (filter.ingest(kObservedSpike) != kNominal) return false;
    return filter.ingest(kNominal) == kNominal;
}

bool sustained_rate_change_converges_without_one_sample_jump() {
    Sk02FeedbackRateFilter filter(kNominal);
    for (int index = 0; index < 5; ++index) filter.ingest(kNominal);
    const unsigned long target = kNominal + 400UL;
    filter.ingest(target);
    filter.ingest(target);
    const unsigned long first_change = filter.ingest(target);
    if (first_change <= kNominal || first_change >= target) return false;
    for (int index = 0; index < 40; ++index) filter.ingest(target);
    return filter.trusted_q16() == target;
}

bool startup_outlier_is_held_at_nominal() {
    Sk02FeedbackRateFilter filter(kNominal);
    if (filter.ingest(kObservedSpike) != kNominal) return false;
    if (filter.ingest(kNominal) != kNominal) return false;
    return filter.ingest(kNominal) == kNominal;
}

} // namespace

int main() {
    const bool isolated = isolated_spike_does_not_steer_schedule();
    const bool sustained = sustained_rate_change_converges_without_one_sample_jump();
    const bool startup = startup_outlier_is_held_at_nominal();
    std::printf(
        "isolated=%s sustained=%s startup=%s\n",
        isolated ? "pass" : "fail",
        sustained ? "pass" : "fail",
        startup ? "pass" : "fail");
    return isolated && sustained && startup ? 0 : 1;
}
