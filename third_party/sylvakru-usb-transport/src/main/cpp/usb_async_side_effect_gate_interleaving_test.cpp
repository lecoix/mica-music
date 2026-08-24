#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>
#include <vector>

#include "usb_async_side_effect_gate.h"

int main() {
    std::mutex side_effect_seam;
    std::mutex boundary_mutex;
    std::condition_variable boundary_condition;
    long long current_generation = 1;
    bool old_at_boundary = false;
    bool release_old = false;
    std::vector<int> submitted_payloads;

    std::thread old_writer([&]() {
        {
            std::unique_lock<std::mutex> boundary(boundary_mutex);
            old_at_boundary = true;
            boundary_condition.notify_all();
            boundary_condition.wait(boundary, [&]() { return release_old; });
        }
        runUsbSideEffectIfCurrent(
            side_effect_seam,
            [&]() { return current_generation == 1; },
            [&]() { submitted_payloads.push_back(1); });
    });

    {
        std::unique_lock<std::mutex> boundary(boundary_mutex);
        boundary_condition.wait(boundary, [&]() { return old_at_boundary; });
    }
    {
        std::lock_guard<std::mutex> seam(side_effect_seam);
        current_generation = 2;
    }
    runUsbSideEffectIfCurrent(
        side_effect_seam,
        [&]() { return current_generation == 2; },
        [&]() { submitted_payloads.push_back(2); });
    {
        std::lock_guard<std::mutex> boundary(boundary_mutex);
        release_old = true;
    }
    boundary_condition.notify_all();
    old_writer.join();

    const bool passed = submitted_payloads.size() == 1 && submitted_payloads[0] == 2;
    std::printf(
        "generationSideEffect submitted=%zu finalPayload=%d expectedOnly=2\n",
        submitted_payloads.size(),
        submitted_payloads.empty() ? -1 : submitted_payloads[0]);
    return passed ? 0 : 1;
}
