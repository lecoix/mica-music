#include <cstdio>
#include <mutex>

#include "usb_output_service_seam.h"
#include "usb_tail_padding.h"

int main() {
    UsbOutputServiceSeam owner_seam;
    long long generation = 1;
    bool reserved = false;
    int fifo_frames = 77;
    constexpr int transfer_frames = 88;
    int submitted_frames = 0;

    const int padding = owner_seam.run([&]() {
        const int needed = usbTailPaddingFrames(fifo_frames, transfer_frames);
        reserved = needed > 0;
        return needed;
    });
    if (padding != 11 || !reserved) return 1;

    // A stale generation must not be allowed to commit its reserved padding after replacement.
    generation = 2;
    fifo_frames = transfer_frames;
    const bool stale_committed = owner_seam.run([&]() {
        if (generation != 1 || !reserved) return false;
        submitted_frames = fifo_frames;
        return true;
    });
    reserved = false;

    const bool new_committed = owner_seam.run([&]() {
        if (generation != 2 || fifo_frames != transfer_frames) return false;
        submitted_frames = fifo_frames;
        return true;
    });
    std::printf(
        "tailPadding padding=%d staleCommitted=%d newCommitted=%d submittedFrames=%d\n",
        padding,
        stale_committed,
        new_committed,
        submitted_frames);
    return !stale_committed && new_committed && submitted_frames == transfer_frames ? 0 : 1;
}
