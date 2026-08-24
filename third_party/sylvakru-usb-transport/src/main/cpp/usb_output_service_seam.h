#pragma once

#include <mutex>
#include <utility>

// Serializes the complete output side-effect transaction: reserve an inactive transfer,
// consume its FIFO payload, and submit it.  Reserving and submitting under different locks
// lets a producer and the libusb callback select the same transfer and silently drop one batch.
class UsbOutputServiceSeam {
public:
    template <typename Action>
    decltype(auto) run(Action&& action) {
        std::lock_guard<std::mutex> lock(mutex_);
        return std::forward<Action>(action)();
    }

private:
    std::mutex mutex_;
};
