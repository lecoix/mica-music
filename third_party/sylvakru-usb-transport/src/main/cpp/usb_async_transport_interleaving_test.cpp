#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>
#include <vector>

#include "usb_async_side_effect_gate.h"
#include "usb_frame_fifo.h"

int main() {
    std::mutex owner_seam;
    std::mutex boundary_mutex;
    std::condition_variable boundary_condition;
    UsbFrameFifo fifo;
    fifo.reset(16, 1);
    const uint8_t old_payload = 1;
    fifo.write(&old_payload, 1);
    long long generation = 1;
    bool old_at_refill_boundary = false;
    bool release_old = false;
    std::vector<int> submitted_payloads;

    std::thread old_callback([&]() {
        {
            std::unique_lock<std::mutex> boundary(boundary_mutex);
            old_at_refill_boundary = true;
            boundary_condition.notify_all();
            boundary_condition.wait(boundary, [&]() { return release_old; });
        }
        runUsbSideEffectIfCurrent(
            owner_seam,
            [&]() { return generation == 1; },
            [&]() {
                uint8_t value = 0;
                if (fifo.read(&value, 1)) submitted_payloads.push_back(value);
            });
    });

    {
        std::unique_lock<std::mutex> boundary(boundary_mutex);
        boundary_condition.wait(boundary, [&]() { return old_at_refill_boundary; });
    }
    {
        std::lock_guard<std::mutex> owner(owner_seam);
        generation = 2;
        fifo.clear();
        const uint8_t new_payload = 2;
        fifo.write(&new_payload, 1);
    }
    runUsbSideEffectIfCurrent(
        owner_seam,
        [&]() { return generation == 2; },
        [&]() {
            uint8_t value = 0;
            if (fifo.read(&value, 1)) submitted_payloads.push_back(value);
        });
    {
        std::lock_guard<std::mutex> boundary(boundary_mutex);
        release_old = true;
    }
    boundary_condition.notify_all();
    old_callback.join();

    const bool passed = submitted_payloads.size() == 1 &&
        submitted_payloads[0] == 2 && fifo.size() == 0;
    std::printf(
        "transportGeneration submitted=%zu finalPayload=%d fifoBytes=%zu expectedOnly=2\n",
        submitted_payloads.size(),
        submitted_payloads.empty() ? -1 : submitted_payloads[0],
        fifo.size());
    return passed ? 0 : 1;
}

