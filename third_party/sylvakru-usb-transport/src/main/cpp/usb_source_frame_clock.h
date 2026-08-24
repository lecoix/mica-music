#pragma once

#include <cstdint>

/**
 * Generation-scoped count of source frames whose USB transfer completed.
 * Synthetic pre-roll, pause filler and tail padding use generation 0 and never advance it.
 */
class UsbSourceFrameClock {
public:
    long long beginTimeline() {
        ++timeline_generation_;
        completed_frames_ = 0;
        return timeline_generation_;
    }

    long long timelineGeneration() const { return timeline_generation_; }

    void complete(long long timeline_generation, long long frames) {
        if (timeline_generation == timeline_generation_ && frames > 0) {
            completed_frames_ += frames;
        }
    }

    long long completedFrames() const { return completed_frames_; }

    void reset() {
        timeline_generation_ = 0;
        completed_frames_ = 0;
    }

private:
    long long timeline_generation_ = 0;
    long long completed_frames_ = 0;
};
