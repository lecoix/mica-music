#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>

#include "usb_underrun_accounting.h"

int main() {
    std::atomic<bool> playing{false};
    std::atomic<unsigned long long> underrun_bytes{0};
    std::mutex boundary_mutex;
    std::condition_variable boundary_condition;
    bool take_complete = false;
    bool release_accounting = false;

    std::thread worker([&]() {
        const SourceTakeResult take{0, playing.load(std::memory_order_acquire)};
        {
            std::unique_lock<std::mutex> guard(boundary_mutex);
            take_complete = true;
            boundary_condition.notify_all();
            boundary_condition.wait(guard, [&]() { return release_accounting; });
        }
        constexpr size_t kRequestedBytes = 768;
        if (should_count_underrun(
                take,
                kRequestedBytes)) {
            underrun_bytes.fetch_add(kRequestedBytes, std::memory_order_release);
        }
    });

    {
        std::unique_lock<std::mutex> guard(boundary_mutex);
        boundary_condition.wait(guard, [&]() { return take_complete; });
        playing.store(true, std::memory_order_release);
        release_accounting = true;
        boundary_condition.notify_all();
    }
    worker.join();

    const auto actual = underrun_bytes.load(std::memory_order_acquire);
    std::printf("pausedTakeAfterPlay underrunBytes=%llu expected=0\n", actual);
    if (actual != 0) return 1;

    constexpr size_t kRequestedBytes = 768;
    const SourceTakeResult real_short_take{384, true};
    const bool real_underrun_counted =
        should_count_underrun(real_short_take, kRequestedBytes);
    std::printf(
        "playingShortTake countUnderrun=%s expected=true\n",
        real_underrun_counted ? "true" : "false");
    return real_underrun_counted ? 0 : 2;
}
