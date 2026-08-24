#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>
#include <vector>

#include "usb_output_service_seam.h"

int main() {
    UsbOutputServiceSeam service_seam;
    std::mutex boundary_mutex;
    std::condition_variable boundary_changed;
    bool first_reserved = false;
    bool release_first = false;
    int next_payload = 1;
    std::vector<int> submitted;

    std::thread callback_thread([&]() {
        service_seam.run([&]() {
            const int payload = next_payload++;
            {
                std::unique_lock<std::mutex> boundary(boundary_mutex);
                first_reserved = true;
                boundary_changed.notify_all();
                boundary_changed.wait(boundary, [&]() { return release_first; });
            }
            submitted.push_back(payload);
        });
    });

    {
        std::unique_lock<std::mutex> boundary(boundary_mutex);
        boundary_changed.wait(boundary, [&]() { return first_reserved; });
    }
    std::thread producer_thread([&]() {
        service_seam.run([&]() {
            const int payload = next_payload++;
            submitted.push_back(payload);
        });
    });
    {
        std::lock_guard<std::mutex> boundary(boundary_mutex);
        release_first = true;
    }
    boundary_changed.notify_all();

    callback_thread.join();
    producer_thread.join();
    const bool passed = submitted.size() == 2 && submitted[0] == 1 && submitted[1] == 2;
    std::printf(
        "outputService submitted=%zu first=%d second=%d expected=1,2\n",
        submitted.size(),
        submitted.empty() ? -1 : submitted[0],
        submitted.size() < 2 ? -1 : submitted[1]);
    return passed ? 0 : 1;
}
