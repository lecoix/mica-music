#include "usb_source_frame_clock.h"

#include <cassert>

int main() {
    UsbSourceFrameClock clock;
    const long long old_timeline = clock.beginTimeline();
    clock.complete(old_timeline, 96);
    assert(clock.completedFrames() == 96);

    const long long new_timeline = clock.beginTimeline();
    assert(clock.completedFrames() == 0);

    // An old in-flight transfer completes after seek. It must not move the new timeline.
    clock.complete(old_timeline, 96);
    assert(clock.completedFrames() == 0);

    // Synthetic frames are deliberately unowned (generation 0).
    clock.complete(0, 480);
    assert(clock.completedFrames() == 0);

    clock.complete(new_timeline, 192);
    assert(clock.completedFrames() == 192);
    return 0;
}
