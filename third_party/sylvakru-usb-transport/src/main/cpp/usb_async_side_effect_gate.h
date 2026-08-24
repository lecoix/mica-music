#pragma once

#include <mutex>

// Serializes request-currentness validation with the actual usbfs side effect.
// Cancellation alone is not correctness: callers must advance their token through
// the same seam before an obsolete submit/discard can pass this gate.
template <typename IsCurrent, typename SideEffect>
bool runUsbSideEffectIfCurrent(
    std::mutex& seam,
    IsCurrent&& is_current,
    SideEffect&& side_effect) {
    std::lock_guard<std::mutex> guard(seam);
    if (!is_current()) return false;
    side_effect();
    return true;
}
