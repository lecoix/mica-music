/*
 * Derived in whole or in part from the SylvaKru USB-exclusive implementation
 * (https://github.com/huya688zdx/sylvakru), Apache License 2.0.
 * Modified/adapted for Mica; see third_party/sylvakru-usb-transport/NOTICE.
 */
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/eventfd.h>
#include <sys/poll.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "usb_async_side_effect_gate.h"
#include "libusb_stream_engine.h"

namespace {

constexpr const char* kTag = "SylvakruUsbExclusive";
constexpr int kMaxIsoPacketsPerUrb = 16;
constexpr int kOutputSlotCount = 32;
constexpr int kFeedbackSlotCount = 4;
constexpr int kProducerWaitTimeoutMs = 1000;
constexpr int kControlWaitTimeoutMs = 2000;

enum class SlotState {
    Free,
    Filled,
    Submitted,
    CancelPending,
};

struct TransferSlot {
    usbdevfs_urb* urb;
    uint8_t* buffer;
    int capacity;
    int length;
    int packets;
    std::array<unsigned int, kMaxIsoPacketsPerUrb> packet_lengths;
    bool feedback;
    SlotState state;
    long long generation;
};

std::mutex g_mutex;
std::mutex g_usb_action_mutex;
std::mutex g_lifecycle_mutex;
std::condition_variable g_slot_available;
std::condition_variable g_control_complete;
std::atomic<long long> g_active_epoch{0};
long long g_next_session_id = 0;
long long g_session_epoch = 0;
long long g_session_id = 0;
std::string g_last_error;
int g_fd = -1;
int g_interface_number = -1;
int g_target_alt_setting = 0;
bool g_streaming_alt_active = false;
int g_endpoint_address = -1;
int g_max_packet_size = 0;
int g_iso_packet_size = 0;
int g_feedback_endpoint_address = 0;
int g_feedback_packet_size = 0;
int g_feedback_frames_per_packet_q16 = 0;
int g_feedback_log_count = 0;
int g_write_log_count = 0;
long long g_total_bytes = 0;
long long g_total_urbs = 0;
long long g_total_iso_packets = 0;
long long g_last_stats_ms = 0;
long long g_iso_error_count = 0;
long long g_io_generation = 0;
int g_wake_fd = -1;
std::thread g_event_thread;
bool g_event_thread_running = false;
bool g_stop_requested = false;
bool g_flush_requested = false;
bool g_transport_failed = false;
std::vector<TransferSlot> g_output_slots;
std::vector<TransferSlot> g_feedback_slots;
LibusbStreamEngine g_libusb_stream;

long long monotonicMillis() {
    timespec now = {};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<long long>(now.tv_sec) * 1000LL + now.tv_nsec / 1000000LL;
}

std::string errorMessage(const char* action) {
    return std::string(action) + " failed: " + strerror(errno);
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

jstring nullableError(JNIEnv* env, const std::string& error) {
    if (error.empty()) {
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_WARN, kTag, "%s", error.c_str());
    return toJString(env, error);
}

void freeTransferSlot(TransferSlot& slot) {
    free(slot.buffer);
    free(slot.urb);
    slot = TransferSlot{nullptr, nullptr, 0, 0, 0, {}, false, SlotState::Free, 0};
}

bool isCurrentLocked(long long epoch, long long session_id) {
    return epoch > 0 &&
        epoch == g_active_epoch.load(std::memory_order_acquire) &&
        epoch == g_session_epoch &&
        session_id > 0 &&
        session_id == g_session_id &&
        !g_stop_requested &&
        !g_transport_failed;
}

bool isOwnedSessionLocked(long long epoch, long long session_id) {
    return epoch > 0 && epoch == g_session_epoch && session_id > 0 && session_id == g_session_id;
}

std::string staleSessionError() {
    return "USB exclusive epoch/session is stale.";
}

bool allocateTransferSlot(
    TransferSlot* slot,
    bool feedback,
    int packet_capacity,
    int byte_capacity) {
    const size_t urb_size =
        sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc) * packet_capacity;
    auto* urb = static_cast<usbdevfs_urb*>(calloc(1, urb_size));
    auto* buffer = static_cast<uint8_t*>(calloc(1, byte_capacity));
    if (urb == nullptr || buffer == nullptr) {
        free(urb);
        free(buffer);
        return false;
    }
    *slot = TransferSlot{
        urb,
        buffer,
        byte_capacity,
        0,
        0,
        {},
        feedback,
        SlotState::Free,
        0,
    };
    return true;
}

void freeAllSlotsLocked() {
    for (auto& slot : g_output_slots) freeTransferSlot(slot);
    for (auto& slot : g_feedback_slots) freeTransferSlot(slot);
    g_output_slots.clear();
    g_feedback_slots.clear();
}

bool allocateTransferPoolsLocked() {
    freeAllSlotsLocked();
    const int output_capacity = std::max(1, g_max_packet_size) * kMaxIsoPacketsPerUrb;
    g_output_slots.reserve(kOutputSlotCount);
    for (int i = 0; i < kOutputSlotCount; ++i) {
        TransferSlot slot{};
        if (!allocateTransferSlot(&slot, false, kMaxIsoPacketsPerUrb, output_capacity)) {
            freeAllSlotsLocked();
            return false;
        }
        g_output_slots.push_back(slot);
    }
    if (g_feedback_endpoint_address != 0 && g_feedback_packet_size > 0) {
        const int feedback_capacity = std::min(4, std::max(3, g_feedback_packet_size));
        g_feedback_slots.reserve(kFeedbackSlotCount);
        for (int i = 0; i < kFeedbackSlotCount; ++i) {
            TransferSlot slot{};
            if (!allocateTransferSlot(&slot, true, 1, feedback_capacity)) {
                freeAllSlotsLocked();
                return false;
            }
            g_feedback_slots.push_back(slot);
        }
    }
    return true;
}

// Caller holds g_mutex so the eventfd cannot be closed/reused between load and write.
void signalWorkerLocked() {
    const int wake_fd = g_wake_fd;
    if (wake_fd < 0) return;
    const uint64_t one = 1;
    const ssize_t ignored = write(wake_fd, &one, sizeof(one));
    (void)ignored;
}

void logCompletedUrb(const TransferSlot& slot) {
    if (slot.feedback) return;
    if (slot.urb->status != 0) {
        ++g_iso_error_count;
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "URB completed with status=%d length=%d packets=%d",
            slot.urb->status,
            slot.length,
            slot.packets);
    }
    for (int i = 0; i < slot.packets; ++i) {
        if (slot.urb->iso_frame_desc[i].status != 0) {
            ++g_iso_error_count;
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "iso frame status=%d actual=%u requested=%u index=%d/%d",
                slot.urb->iso_frame_desc[i].status,
                slot.urb->iso_frame_desc[i].actual_length,
                slot.urb->iso_frame_desc[i].length,
                i,
                slot.packets);
        }
    }
}

void handleFeedbackUrb(const TransferSlot& slot) {
    if (slot.urb->status != 0 || slot.packets <= 0) return;
    const auto& frame = slot.urb->iso_frame_desc[0];
    if (frame.status != 0 || frame.actual_length < 3) return;
    const int actual = std::min<int>(frame.actual_length, slot.length);
    int raw = 0;
    for (int i = 0; i < std::min(actual, 4); ++i) {
        raw |= static_cast<int>(slot.buffer[i]) << (i * 8);
    }
    const int q16 = actual >= 4 ? raw : raw << 2;
    if (q16 > 0) g_feedback_frames_per_packet_q16 = q16;
    if (g_feedback_log_count < 12) {
        ++g_feedback_log_count;
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "USB feedback actual=%d raw=0x%x framesPerPacketQ16=%d approxFrames=%.6f",
            actual,
            raw,
            q16,
            static_cast<double>(q16) / 65536.0);
    }
}

TransferSlot* findSlotLocked(void* urb_pointer) {
    for (auto& slot : g_output_slots) {
        if (slot.urb == urb_pointer) return &slot;
    }
    for (auto& slot : g_feedback_slots) {
        if (slot.urb == urb_pointer) return &slot;
    }
    return nullptr;
}

bool allOutputFreeLocked() {
    return std::all_of(
        g_output_slots.begin(),
        g_output_slots.end(),
        [](const TransferSlot& slot) { return slot.state == SlotState::Free; });
}

void prepareUrbLocked(TransferSlot& slot) {
    memset(
        slot.urb,
        0,
        sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc) *
            (slot.feedback ? 1 : kMaxIsoPacketsPerUrb));
    slot.urb->type = USBDEVFS_URB_TYPE_ISO;
    slot.urb->endpoint = static_cast<unsigned char>(
        slot.feedback ? g_feedback_endpoint_address : g_endpoint_address);
    slot.urb->flags = USBDEVFS_URB_ISO_ASAP;
    slot.urb->buffer = slot.buffer;
    slot.urb->buffer_length = slot.length;
    slot.urb->number_of_packets = slot.packets;
    if (slot.feedback) {
        memset(slot.buffer, 0, slot.length);
        slot.urb->iso_frame_desc[0].length = slot.length;
    } else {
        for (int i = 0; i < slot.packets; ++i) {
            slot.urb->iso_frame_desc[i].length = slot.packet_lengths[i];
        }
    }
}

bool submitOneSlot(TransferSlot* slot, long long generation) {
    int submit_result = -1;
    const bool performed = runUsbSideEffectIfCurrent(
        g_usb_action_mutex,
        [&]() {
            std::lock_guard<std::mutex> lock(g_mutex);
            return slot->state == SlotState::Submitted &&
                slot->generation == generation &&
                generation == g_io_generation &&
                g_streaming_alt_active &&
                isCurrentLocked(g_session_epoch, g_session_id);
        },
        [&]() { submit_result = ioctl(g_fd, USBDEVFS_SUBMITURB, slot->urb); });
    if (!performed) {
        std::lock_guard<std::mutex> lock(g_mutex);
        slot->state = SlotState::Free;
        g_slot_available.notify_all();
        return false;
    }
    if (submit_result == 0) {
        if (!slot->feedback) {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_total_bytes += slot->length;
            g_total_urbs += 1;
            g_total_iso_packets += slot->packets;
        }
        return true;
    }
    const std::string error = errorMessage(
        slot->feedback ? "USBDEVFS_SUBMITURB feedback" : "USBDEVFS_SUBMITURB");
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        slot->state = SlotState::Free;
        g_last_error = error;
        g_transport_failed = true;
        g_stop_requested = true;
        g_slot_available.notify_all();
        g_control_complete.notify_all();
    }
    __android_log_print(ANDROID_LOG_WARN, kTag, "%s", error.c_str());
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        signalWorkerLocked();
    }
    return false;
}

void usbEventLoop() {
    const int priority_result = setpriority(PRIO_PROCESS, 0, -19);
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "USB event thread started outputSlots=%d feedbackSlots=%zu priorityResult=%d errno=%d",
        kOutputSlotCount,
        g_feedback_slots.size(),
        priority_result,
        priority_result == 0 ? 0 : errno);

    while (true) {
        std::vector<TransferSlot*> to_submit;
        std::vector<TransferSlot*> to_discard;
        bool stop = false;
        int usb_fd = -1;
        int wake_fd = -1;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            stop = g_stop_requested ||
                g_session_epoch != g_active_epoch.load(std::memory_order_acquire);
            usb_fd = g_fd;
            wake_fd = g_wake_fd;

            if (g_flush_requested || stop) {
                for (auto& slot : g_output_slots) {
                    if (slot.state == SlotState::Filled) {
                        slot.state = SlotState::Free;
                    } else if (slot.state == SlotState::Submitted) {
                        slot.state = SlotState::CancelPending;
                        to_discard.push_back(&slot);
                    }
                }
                if (stop) {
                    for (auto& slot : g_feedback_slots) {
                        if (slot.state == SlotState::Submitted) {
                            slot.state = SlotState::CancelPending;
                            to_discard.push_back(&slot);
                        }
                    }
                }
                g_slot_available.notify_all();
            }

            if (!stop && !g_flush_requested && g_streaming_alt_active) {
                for (auto& slot : g_output_slots) {
                    if (slot.state == SlotState::Filled && slot.generation == g_io_generation) {
                        prepareUrbLocked(slot);
                        slot.state = SlotState::Submitted;
                        to_submit.push_back(&slot);
                    }
                }
                for (auto& slot : g_feedback_slots) {
                    if (slot.state == SlotState::Free) {
                        slot.length = slot.capacity;
                        slot.packets = 1;
                        slot.generation = g_io_generation;
                        prepareUrbLocked(slot);
                        slot.state = SlotState::Submitted;
                        to_submit.push_back(&slot);
                    }
                }
            }
        }

        for (auto* slot : to_discard) {
            std::lock_guard<std::mutex> action(g_usb_action_mutex);
            if (usb_fd >= 0) ioctl(usb_fd, USBDEVFS_DISCARDURB, slot->urb);
        }

        if (stop) {
            std::lock_guard<std::mutex> action(g_usb_action_mutex);
            if (usb_fd >= 0) {
                if (g_interface_number >= 0) {
                    ioctl(usb_fd, USBDEVFS_RELEASEINTERFACE, &g_interface_number);
                }
                close(usb_fd);
            }
            {
                std::lock_guard<std::mutex> lock(g_mutex);
                g_fd = -1;
                for (auto& slot : g_output_slots) slot.state = SlotState::Free;
                for (auto& slot : g_feedback_slots) slot.state = SlotState::Free;
                g_slot_available.notify_all();
                g_control_complete.notify_all();
            }
            __android_log_print(ANDROID_LOG_INFO, kTag, "USB event thread stopped");
            return;
        }

        for (auto* slot : to_submit) {
            submitOneSlot(slot, slot->generation);
        }

        pollfd descriptors[2] = {
            {usb_fd, POLLIN | POLLOUT | POLLERR | POLLHUP, 0},
            {wake_fd, POLLIN, 0},
        };
        const int poll_result = poll(descriptors, 2, 50);
        if (poll_result < 0 && errno != EINTR) {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_last_error = errorMessage("poll USB event thread");
            g_transport_failed = true;
            g_stop_requested = true;
            continue;
        }
        if (wake_fd >= 0 && (descriptors[1].revents & POLLIN) != 0) {
            uint64_t ignored = 0;
            while (read(wake_fd, &ignored, sizeof(ignored)) > 0) {}
        }

        while (usb_fd >= 0) {
            void* completed = nullptr;
            int reap_result = -1;
            {
                std::lock_guard<std::mutex> action(g_usb_action_mutex);
                reap_result = ioctl(usb_fd, USBDEVFS_REAPURBNDELAY, &completed);
            }
            if (reap_result < 0) {
                if (errno == EAGAIN || errno == EINTR) break;
                std::lock_guard<std::mutex> lock(g_mutex);
                if (!g_stop_requested) {
                    g_last_error = errorMessage("USBDEVFS_REAPURBNDELAY");
                    g_transport_failed = true;
                    g_stop_requested = true;
                }
                break;
            }
            std::lock_guard<std::mutex> lock(g_mutex);
            TransferSlot* slot = findSlotLocked(completed);
            if (slot == nullptr) {
                g_last_error = "USBDEVFS_REAPURBNDELAY returned an unknown URB.";
                g_transport_failed = true;
                g_stop_requested = true;
                break;
            }
            if (slot->feedback) {
                if (slot->state != SlotState::CancelPending &&
                    slot->generation == g_io_generation) {
                    handleFeedbackUrb(*slot);
                }
            } else if (slot->state != SlotState::CancelPending &&
                       slot->generation == g_io_generation) {
                logCompletedUrb(*slot);
            }
            slot->state = SlotState::Free;
            g_slot_available.notify_all();
        }

        {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (g_flush_requested && allOutputFreeLocked()) {
                g_flush_requested = false;
                g_control_complete.notify_all();
            }
        }
    }
}

bool startEventThreadLocked() {
    if (g_event_thread_running) return true;
    g_wake_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (g_wake_fd < 0) {
        g_last_error = errorMessage("eventfd");
        return false;
    }
    g_stop_requested = false;
    g_flush_requested = false;
    g_transport_failed = false;
    g_event_thread_running = true;
    try {
        g_event_thread = std::thread(usbEventLoop);
    } catch (...) {
        g_event_thread_running = false;
        close(g_wake_fd);
        g_wake_fd = -1;
        g_last_error = "Failed to create USB event thread.";
        return false;
    }
    return true;
}

void resetSessionFieldsLocked() {
    if (g_wake_fd >= 0) close(g_wake_fd);
    g_wake_fd = -1;
    freeAllSlotsLocked();
    g_interface_number = -1;
    g_target_alt_setting = 0;
    g_streaming_alt_active = false;
    g_endpoint_address = -1;
    g_max_packet_size = 0;
    g_iso_packet_size = 0;
    g_feedback_endpoint_address = 0;
    g_feedback_packet_size = 0;
    g_feedback_frames_per_packet_q16 = 0;
    g_feedback_log_count = 0;
    g_write_log_count = 0;
    g_total_bytes = 0;
    g_total_urbs = 0;
    g_total_iso_packets = 0;
    g_last_stats_ms = 0;
    g_iso_error_count = 0;
    g_io_generation = 0;
    g_stop_requested = false;
    g_flush_requested = false;
    g_transport_failed = false;
    g_event_thread_running = false;
    g_session_epoch = 0;
    g_session_id = 0;
}

void shutdownSession() {
    g_libusb_stream.close();
    {
        std::lock_guard<std::mutex> action(g_usb_action_mutex);
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_event_thread_running) {
            if (g_fd >= 0) {
                if (g_interface_number >= 0) {
                    ioctl(g_fd, USBDEVFS_RELEASEINTERFACE, &g_interface_number);
                }
                close(g_fd);
                g_fd = -1;
            }
            resetSessionFieldsLocked();
            return;
        }
        g_stop_requested = true;
        ++g_io_generation;
        g_slot_available.notify_all();
        signalWorkerLocked();
    }
    if (g_event_thread.joinable() && g_event_thread.get_id() != std::this_thread::get_id()) {
        g_event_thread.join();
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    resetSessionFieldsLocked();
}

std::string claimInterfaceLocked(long long epoch) {
    if (epoch != g_active_epoch.load(std::memory_order_acquire)) return staleSessionError();
    usbdevfs_disconnect_claim disconnect_claim = {};
    disconnect_claim.interface = static_cast<unsigned int>(g_interface_number);

    if (ioctl(g_fd, USBDEVFS_DISCONNECT_CLAIM, &disconnect_claim) == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "USBDEVFS_DISCONNECT_CLAIM ok interface=%d",
            g_interface_number);
        return {};
    }

    const int disconnect_claim_errno = errno;
    __android_log_print(
        ANDROID_LOG_WARN,
        kTag,
        "USBDEVFS_DISCONNECT_CLAIM failed interface=%d: %s",
        g_interface_number,
        strerror(disconnect_claim_errno));

    if (epoch != g_active_epoch.load(std::memory_order_acquire)) return staleSessionError();
    if (ioctl(g_fd, USBDEVFS_CLAIMINTERFACE, &g_interface_number) == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "USBDEVFS_CLAIMINTERFACE ok interface=%d",
            g_interface_number);
        return {};
    }

    return errorMessage("USBDEVFS_CLAIMINTERFACE");
}

std::string enqueueIsoPackets(
    long long epoch,
    long long session_id,
    const uint8_t* data,
    int length,
    const int* packet_lengths,
    int packet_count) {
    if (data == nullptr || length <= 0 || packet_lengths == nullptr || packet_count <= 0) {
        return {};
    }
    const int packets = std::min(packet_count, kMaxIsoPacketsPerUrb);
    int described_length = 0;
    std::unique_lock<std::mutex> lock(g_mutex);
    if (!isCurrentLocked(epoch, session_id)) return staleSessionError();
    if (g_fd < 0) {
        return "USB exclusive device is not open.";
    }
    for (int i = 0; i < packets; ++i) {
        if (packet_lengths[i] <= 0 || packet_lengths[i] > g_max_packet_size) {
            return "USB exclusive iso packet length is invalid.";
        }
        described_length += packet_lengths[i];
    }
    if (described_length != length) {
        return "USB exclusive iso packet lengths do not match payload length.";
    }
    const auto free_slot = []() -> TransferSlot* {
        for (auto& slot : g_output_slots) {
            if (slot.state == SlotState::Free) return &slot;
        }
        return nullptr;
    };
    const bool ready = g_slot_available.wait_for(
        lock,
        std::chrono::milliseconds(kProducerWaitTimeoutMs),
        [&] {
            return free_slot() != nullptr || !isCurrentLocked(epoch, session_id);
        });
    if (!ready) return "USB exclusive output queue stalled for 1000 ms.";
    if (!isCurrentLocked(epoch, session_id)) return staleSessionError();
    TransferSlot* slot = free_slot();
    if (slot == nullptr || length > slot->capacity) {
        return "USB exclusive output batch exceeds its fixed transfer slot.";
    }
    memcpy(slot->buffer, data, length);
    slot->length = length;
    slot->packets = packets;
    slot->generation = g_io_generation;
    for (int i = 0; i < packets; ++i) {
        slot->packet_lengths[i] = static_cast<unsigned int>(packet_lengths[i]);
    }
    slot->state = SlotState::Filled;
    signalWorkerLocked();
    lock.unlock();
    return {};
}

std::string enqueueIsoChunk(long long epoch, long long session_id, const uint8_t* data, int length) {
    int iso_packet_size = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) return staleSessionError();
        iso_packet_size =
            g_iso_packet_size > 0 ? std::min(g_iso_packet_size, g_max_packet_size) : g_max_packet_size;
    }
    const int packets = std::max(
        1,
        std::min(kMaxIsoPacketsPerUrb, (length + iso_packet_size - 1) / iso_packet_size));
    int remaining = length;
    int packet_lengths[kMaxIsoPacketsPerUrb] = {};
    for (int i = 0; i < packets; ++i) {
        packet_lengths[i] = std::min(iso_packet_size, remaining);
        remaining -= packet_lengths[i];
    }
    return enqueueIsoPackets(epoch, session_id, data, length, packet_lengths, packets);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_publishActiveEpoch(
    JNIEnv*, jobject, jlong epoch) {
    long long invalid_generation = 0;
    {
        std::lock_guard<std::mutex> action(g_usb_action_mutex);
        long long observed = g_active_epoch.load(std::memory_order_relaxed);
        while (epoch > observed && !g_active_epoch.compare_exchange_weak(
            observed,
            epoch,
            std::memory_order_release,
            std::memory_order_relaxed)) {}
    }
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_session_epoch > 0 && epoch > g_session_epoch) {
            invalid_generation = ++g_io_generation;
        }
        g_slot_available.notify_all();
        signalWorkerLocked();
    }
    if (invalid_generation > 0) g_libusb_stream.invalidate(invalid_generation);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_isCurrent(
    JNIEnv*, jobject, jlong epoch, jlong session_id) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return isCurrentLocked(epoch, session_id) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_lastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_last_error.empty() ? nullptr : env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_openRaw(
    JNIEnv* env,
    jobject,
    jlong epoch,
    jint fd,
    jint interface_number,
    jint alternate_setting,
    jint endpoint_address,
    jint max_packet_size,
    jint feedback_endpoint_address,
    jint feedback_max_packet_size,
    jboolean interface_already_claimed,
    jboolean defer_target_alt_until_configured,
    jboolean reset_alt_before_configured) {
    std::lock_guard<std::mutex> lifecycle(g_lifecycle_mutex);
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (epoch <= 0 || epoch != g_active_epoch.load(std::memory_order_acquire)) {
            g_last_error = staleSessionError();
            return 0;
        }
    }
    shutdownSession();
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error.clear();
    if (epoch <= 0 || epoch != g_active_epoch.load(std::memory_order_acquire)) {
        g_last_error = staleSessionError();
        return 0;
    }
    const auto fail_open = [&](const std::string& error) -> jlong {
        g_libusb_stream.close();
        if (g_fd >= 0) {
            if (g_interface_number >= 0) {
                ioctl(g_fd, USBDEVFS_RELEASEINTERFACE, &g_interface_number);
            }
            close(g_fd);
            g_fd = -1;
        }
        resetSessionFieldsLocked();
        g_last_error = error;
        return 0;
    };

    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "open requested fd=%d interface=%d alt=%d endpoint=0x%x maxPacket=%d",
        fd,
        interface_number,
        alternate_setting,
        endpoint_address,
        max_packet_size);

    const int duplicated = dup(fd);
    if (duplicated < 0) {
        g_last_error = errorMessage("dup");
        return 0;
    }

    g_fd = duplicated;
    g_interface_number = interface_number;
    g_target_alt_setting = alternate_setting;
    g_streaming_alt_active = false;
    g_endpoint_address = endpoint_address;
    g_max_packet_size = max_packet_size;
    g_feedback_endpoint_address = feedback_endpoint_address;
    g_feedback_packet_size = feedback_max_packet_size;

    if (interface_already_claimed == JNI_TRUE) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "USB interface already claimed by UsbDeviceConnection interface=%d",
            g_interface_number);
    } else {
        const auto claim_error = claimInterfaceLocked(epoch);
        if (!claim_error.empty()) {
            return fail_open(claim_error);
        }
    }

    const bool defer_target_alt = defer_target_alt_until_configured == JNI_TRUE;
    const bool reset_alt_before_config = reset_alt_before_configured == JNI_TRUE;
    if (reset_alt_before_config && !defer_target_alt) {
        return fail_open("USB reset-alt requires deferred target-alt activation.");
    }
    if (epoch != g_active_epoch.load(std::memory_order_acquire)) {
        return fail_open(staleSessionError());
    }
    if (!defer_target_alt || reset_alt_before_config) {
        const int initial_alt_setting = defer_target_alt ? 0 : alternate_setting;
        usbdevfs_setinterface set_interface = {};
        set_interface.interface = interface_number;
        set_interface.altsetting = initial_alt_setting;
        if (ioctl(g_fd, USBDEVFS_SETINTERFACE, &set_interface) < 0) {
            const auto error = errorMessage("USBDEVFS_SETINTERFACE");
            return fail_open(error);
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "USBDEVFS_SETINTERFACE ok interface=%d alt=%d stage=%s",
            interface_number,
            initial_alt_setting,
            defer_target_alt ? "preconfigure-reset" : "active");
    } else {
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "target alt deferred pending clock/config interface=%d targetAlt=%d stage=preconfigure-defer",
            interface_number,
            alternate_setting);
    }

    if (epoch != g_active_epoch.load(std::memory_order_acquire)) {
        return fail_open(staleSessionError());
    }
    g_session_epoch = epoch;
    g_session_id = ++g_next_session_id;
    g_io_generation = 1;
    g_streaming_alt_active = !defer_target_alt;
    std::string libusb_error;
    if (!g_libusb_stream.open(
            g_fd,
            g_endpoint_address,
            g_max_packet_size,
            g_feedback_endpoint_address,
            g_feedback_packet_size,
            g_io_generation,
            &libusb_error)) {
        return fail_open(libusb_error);
    }

    if (defer_target_alt) {
        if (reset_alt_before_config) {
            __android_log_print(
                ANDROID_LOG_INFO,
                kTag,
                "streaming interface parked at alt=0 pending clock/config interface=%d targetAlt=%d session=%lld",
                interface_number,
                alternate_setting,
                g_session_id);
        }
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "libusb async engine ready outputTransfers=%d activeTransfers=%d packetsPerTransfer=%d feedbackTransfers=%d",
        16,
        15,
        kMaxIsoPacketsPerUrb,
        g_feedback_endpoint_address != 0 ? 4 : 0);
    return static_cast<jlong>(g_session_id);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_activateConfiguredAlt(
    JNIEnv* env,
    jobject,
    jlong epoch,
    jlong session_id,
    jint alternate_setting) {
    std::lock_guard<std::mutex> action(g_usb_action_mutex);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!isCurrentLocked(epoch, session_id)) {
        return nullableError(env, staleSessionError());
    }
    if (g_fd < 0 || g_interface_number < 0) {
        return nullableError(env, "USB exclusive device is not open.");
    }
    if (alternate_setting != g_target_alt_setting) {
        return nullableError(env, "USB exclusive target alternate setting changed during configuration.");
    }
    if (!g_streaming_alt_active) {
        usbdevfs_setinterface set_interface = {};
        set_interface.interface = g_interface_number;
        set_interface.altsetting = alternate_setting;
        if (ioctl(g_fd, USBDEVFS_SETINTERFACE, &set_interface) < 0) {
            return nullableError(env, errorMessage("USBDEVFS_SETINTERFACE activate configured alt"));
        }
        g_streaming_alt_active = true;
    }
    std::string activation_error;
    if (!g_libusb_stream.activate(g_io_generation, &activation_error)) {
        g_last_error = activation_error;
        g_transport_failed = true;
        return nullableError(env, activation_error);
    }
    const auto libusb_telemetry = g_libusb_stream.telemetry();
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "USBDEVFS_SETINTERFACE ok interface=%d alt=%d stage=post-config-activate session=%lld libusbActiveTransfers=%lld",
        g_interface_number,
        alternate_setting,
        g_session_id,
        libusb_telemetry.active_output_transfers);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_configureOutputStream(
    JNIEnv* env,
    jobject,
    jlong epoch,
    jlong session_id,
    jint sample_rate,
    jint packets_per_second,
    jint bytes_per_frame,
    jint target_buffer_ms) {
    long long generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) return nullableError(env, staleSessionError());
        generation = g_io_generation;
    }
    std::string error;
    if (!g_libusb_stream.configure(
            sample_rate,
            packets_per_second,
            bytes_per_frame,
            target_buffer_ms,
            generation,
            &error)) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = error;
        return nullableError(env, error);
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "libusb stream configured sampleRate=%d packetsPerSecond=%d bytesPerFrame=%d targetBufferMs=%d",
        sample_rate,
        packets_per_second,
        bytes_per_frame,
        target_buffer_ms);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_writeFrames(
    JNIEnv* env,
    jobject,
    jlong epoch,
    jlong session_id,
    jbyteArray bytes,
    jint length) {
    if (bytes == nullptr || length <= 0) return nullptr;
    const int safe_length = std::min<int>(length, env->GetArrayLength(bytes));
    auto* input = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(bytes, nullptr));
    if (input == nullptr) return nullableError(env, "Failed to access USB frame buffer.");
    long long generation = 0;
    std::string error;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) error = staleSessionError();
        generation = g_io_generation;
    }
    if (error.empty()) {
        g_libusb_stream.enqueue(input, safe_length, generation, 0, &error);
    }
    env->ReleaseByteArrayElements(bytes, reinterpret_cast<jbyte*>(input), JNI_ABORT);
    return nullableError(env, error);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_beginSourceTimeline(
    JNIEnv*, jobject, jlong epoch, jlong session_id) {
    long long generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) return -1;
        generation = g_io_generation;
    }
    std::string error;
    const long long timeline = g_libusb_stream.beginSourceTimeline(generation, &error);
    if (timeline < 0) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = error;
    }
    return timeline;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_writeSourceFrames(
    JNIEnv* env,
    jobject,
    jlong epoch,
    jlong session_id,
    jlong source_timeline_generation,
    jbyteArray bytes,
    jint length) {
    if (bytes == nullptr || length <= 0) return nullptr;
    const int safe_length = std::min<int>(length, env->GetArrayLength(bytes));
    auto* input = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(bytes, nullptr));
    if (input == nullptr) return nullableError(env, "Failed to access USB source frame buffer.");
    long long generation = 0;
    std::string error;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) error = staleSessionError();
        generation = g_io_generation;
    }
    if (error.empty()) {
        g_libusb_stream.enqueue(
            input,
            safe_length,
            generation,
            source_timeline_generation,
            &error);
    }
    env->ReleaseByteArrayElements(bytes, reinterpret_cast<jbyte*>(input), JNI_ABORT);
    return nullableError(env, error);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_consumedSourceFrames(
    JNIEnv*,
    jobject,
    jlong epoch,
    jlong session_id,
    jlong source_timeline_generation) {
    long long generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) return -1;
        generation = g_io_generation;
    }
    return g_libusb_stream.consumedSourceFrames(generation, source_timeline_generation);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_reserveOutputTailPaddingFrames(
    JNIEnv*, jobject, jlong epoch, jlong session_id) {
    long long generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) {
            g_last_error = staleSessionError();
            return -2;
        }
        generation = g_io_generation;
    }
    std::string error;
    const int result = g_libusb_stream.reserveTailPaddingFrames(generation, &error);
    if (result == -2) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = error;
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_commitOutputTailPadding(
    JNIEnv* env, jobject, jlong epoch, jlong session_id) {
    long long generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) return nullableError(env, staleSessionError());
        generation = g_io_generation;
    }
    std::string error;
    if (!g_libusb_stream.commitTailPadding(generation, &error)) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = error;
        return nullableError(env, error);
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_writePcm(
    JNIEnv* env,
    jobject,
    jlong epoch,
    jlong session_id,
    jbyteArray bytes,
    jint length) {
    if (bytes == nullptr || length <= 0) {
        return nullptr;
    }

    const jsize array_length = env->GetArrayLength(bytes);
    const int safe_length = std::min<int>(length, array_length);
    auto* input = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(bytes, nullptr));
    if (input == nullptr) {
        return nullableError(env, "Failed to access PCM buffer.");
    }

    std::string error;
    int offset = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) {
            error = staleSessionError();
        }
    }
    int max_chunk = 1;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const int iso_packet_size =
            g_iso_packet_size > 0 ? std::min(g_iso_packet_size, g_max_packet_size) : g_max_packet_size;
        max_chunk = std::max(1, iso_packet_size * kMaxIsoPacketsPerUrb);
    }
    while (offset < safe_length && error.empty()) {
        const int chunk = std::min(max_chunk, safe_length - offset);
        error = enqueueIsoChunk(epoch, session_id, input + offset, chunk);
        offset += chunk;
    }

    env->ReleaseByteArrayElements(bytes, reinterpret_cast<jbyte*>(input), JNI_ABORT);
    bool log_write = false;
    int endpoint = 0;
    int packet_size = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        log_write = error.empty() && g_write_log_count < 5;
        if (log_write) ++g_write_log_count;
        endpoint = g_endpoint_address;
        packet_size = g_iso_packet_size;
    }
    if (log_write) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "writePcm queued %d bytes to endpoint=0x%x isoPacket=%d",
            safe_length,
            endpoint,
            packet_size);
    }
    return nullableError(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_writeIsoPackets(
    JNIEnv* env,
    jobject,
    jlong epoch,
    jlong session_id,
    jbyteArray bytes,
    jintArray packet_lengths,
    jint packet_count) {
    if (bytes == nullptr || packet_lengths == nullptr || packet_count <= 0) {
        return nullptr;
    }

    const jsize array_length = env->GetArrayLength(bytes);
    const jsize lengths_length = env->GetArrayLength(packet_lengths);
    const int safe_packet_count = std::min<int>(
        std::min<int>(packet_count, lengths_length),
        kMaxIsoPacketsPerUrb);
    if (safe_packet_count <= 0) {
        return nullptr;
    }

    int safe_length = 0;
    jint stack_lengths[kMaxIsoPacketsPerUrb] = {};
    env->GetIntArrayRegion(packet_lengths, 0, safe_packet_count, stack_lengths);
    for (int i = 0; i < safe_packet_count; ++i) {
        if (stack_lengths[i] <= 0) {
            return nullableError(env, "USB exclusive iso packet length is invalid.");
        }
        safe_length += stack_lengths[i];
    }
    if (safe_length > array_length) {
        return nullableError(env, "USB exclusive iso packet data is shorter than packet lengths.");
    }

    auto* input = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(bytes, nullptr));
    if (input == nullptr) {
        return nullableError(env, "Failed to access PCM buffer.");
    }

    const std::string error =
        enqueueIsoPackets(epoch, session_id, input, safe_length, stack_lengths, safe_packet_count);

    env->ReleaseByteArrayElements(bytes, reinterpret_cast<jbyte*>(input), JNI_ABORT);
    bool log_write = false;
    int endpoint = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        log_write = error.empty() && g_write_log_count < 5;
        if (log_write) ++g_write_log_count;
        endpoint = g_endpoint_address;
    }
    if (log_write) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "writeIsoPackets queued %d bytes packets=%d endpoint=0x%x",
            safe_length,
            safe_packet_count,
            endpoint);
    }
    return nullableError(env, error);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_feedbackFramesPerPacketQ16(
    JNIEnv*, jobject, jlong epoch, jlong session_id) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!isCurrentLocked(epoch, session_id)) return 0;
    return g_libusb_stream.feedbackFramesPerPacketQ16();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_transportTelemetry(
    JNIEnv* env, jobject, jlong epoch, jlong session_id) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!isCurrentLocked(epoch, session_id)) {
        const jlong stale_values[] = {-1, -1, -1, -1};
        jlongArray stale_result = env->NewLongArray(4);
        if (stale_result != nullptr) env->SetLongArrayRegion(stale_result, 0, 4, stale_values);
        return stale_result;
    }
    const auto telemetry = g_libusb_stream.telemetry();
    const long long pending_iso_packets =
        telemetry.buffered_packets + telemetry.active_output_transfers * kMaxIsoPacketsPerUrb;

    const jlong values[] = {
        static_cast<jlong>(pending_iso_packets),
        static_cast<jlong>(telemetry.submitted_packets),
        static_cast<jlong>(telemetry.active_output_transfers),
        static_cast<jlong>(telemetry.underruns),
    };
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 4, values);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_setIsoPacketSize(
    JNIEnv* env,
    jobject,
    jlong epoch,
    jlong session_id,
    jint packet_size) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!isCurrentLocked(epoch, session_id)) return nullableError(env, staleSessionError());
    g_iso_packet_size = std::max(0, std::min(static_cast<int>(packet_size), g_max_packet_size));
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "iso packet size set to %d bytes",
        g_iso_packet_size);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_flushOutput(
    JNIEnv* env, jobject, jlong epoch, jlong session_id) {
    long long next_generation = 0;
    {
        std::lock_guard<std::mutex> action(g_usb_action_mutex);
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isCurrentLocked(epoch, session_id)) return nullableError(env, staleSessionError());
        next_generation = ++g_io_generation;
        g_slot_available.notify_all();
    }
    std::string error;
    if (!g_libusb_stream.flush(next_generation, &error)) return nullableError(env, error);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!isCurrentLocked(epoch, session_id)) return nullableError(env, staleSessionError());
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_afalphy_sylvakru_UsbExclusiveNative_close(
    JNIEnv* env, jobject, jlong epoch, jlong session_id) {
    std::lock_guard<std::mutex> lifecycle(g_lifecycle_mutex);
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!isOwnedSessionLocked(epoch, session_id)) return nullableError(env, staleSessionError());
    }
    shutdownSession();
    return nullptr;
}
