#pragma once

#include <cstddef>

struct SourceTakeResult {
    size_t bytes;
    bool playing_when_taken;
};

inline bool should_count_underrun(
    const SourceTakeResult& take,
    const size_t requested) {
    return take.bytes < requested && take.playing_when_taken;
}
