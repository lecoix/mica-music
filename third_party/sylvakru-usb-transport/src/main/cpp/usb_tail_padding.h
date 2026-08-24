#pragma once

#include <algorithm>

inline int usbTailPaddingFrames(long long fifo_frames, int next_transfer_frames) {
    if (fifo_frames <= 0 || next_transfer_frames <= 0) return 0;
    if (fifo_frames >= next_transfer_frames) return -1;
    return next_transfer_frames - static_cast<int>(fifo_frames);
}
