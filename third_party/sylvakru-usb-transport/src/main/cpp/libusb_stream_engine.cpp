#include "libusb_stream_engine.h"

#include <android/log.h>
#include <libusb.h>
#include <sys/resource.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "usb_frame_fifo.h"
#include "usb_output_service_seam.h"
#include "usb_source_frame_clock.h"
#include "usb_tail_padding.h"

namespace {

constexpr const char* kTag = "SylvakruUsbExclusive";
constexpr int kPacketsPerTransfer = 16;
constexpr int kOutputTransferCount = 16;
constexpr int kActiveOutputTransferTarget = 15;
constexpr int kFeedbackTransferCount = 4;
constexpr int kProducerWaitMs = 1000;
constexpr int kFlushWaitMs = 2000;

std::string libusbError(const char* action, int result) {
    return std::string(action) + " failed: " + libusb_error_name(result);
}

}  // namespace

struct LibusbStreamEngine::Impl {
    struct SourceSpan {
        long long timeline_generation = 0;
        long long frames = 0;
    };

    struct Slot {
        Impl* owner = nullptr;
        libusb_transfer* transfer = nullptr;
        std::vector<uint8_t> buffer;
        bool feedback = false;
        bool active = false;
        long long generation = 0;
        int requested_bytes = 0;
        std::vector<SourceSpan> source_spans;
    };

    mutable std::mutex mutex;
    std::mutex usb_action_mutex;
    UsbOutputServiceSeam output_service_seam;
    std::condition_variable fifo_changed;
    std::condition_variable state_changed;
    libusb_context* context = nullptr;
    libusb_device_handle* handle = nullptr;
    std::thread event_thread;
    bool event_thread_started = false;
    bool stopping = false;
    bool configured = false;
    bool activated = false;
    bool flushing = false;
    bool failed = false;
    long long generation = 0;
    int output_endpoint = 0;
    int output_max_packet_size = 0;
    int feedback_endpoint = 0;
    int feedback_packet_size = 0;
    int sample_rate = 0;
    int packets_per_second = 0;
    int bytes_per_frame = 0;
    long long nominal_remainder = 0;
    long long feedback_remainder_q16 = 0;
    std::atomic<int> feedback_q16{0};
    UsbFrameFifo fifo;
    std::deque<SourceSpan> fifo_source_spans;
    UsbSourceFrameClock source_frame_clock;
    std::vector<std::unique_ptr<Slot>> output_slots;
    std::vector<std::unique_ptr<Slot>> feedback_slots;
    std::string last_error;
    long long submitted_bytes = 0;
    long long submitted_transfers = 0;
    long long submitted_packets = 0;
    long long underruns = 0;
    int active_output = 0;
    int active_feedback = 0;
    bool tail_padding_reserved = false;
    long long tail_padding_generation = 0;
    long long tail_padding_target_frames = 0;

    static void transferCallback(libusb_transfer* transfer) {
        auto* slot = static_cast<Slot*>(transfer->user_data);
        if (slot == nullptr || slot->owner == nullptr) return;
        slot->owner->onTransferComplete(*slot);
    }

    int nextPacketFramesLocked() {
        const int feedback = feedback_q16.load(std::memory_order_relaxed);
        const long long nominal_q16 =
            (static_cast<long long>(sample_rate) << 16) / packets_per_second;
        if (feedback > nominal_q16 - nominal_q16 / 8 &&
            feedback < nominal_q16 + nominal_q16 / 2) {
            feedback_remainder_q16 += feedback;
            const int frames = static_cast<int>(feedback_remainder_q16 >> 16);
            feedback_remainder_q16 &= 0xffff;
            if (frames > 0) return frames;
        }
        nominal_remainder += sample_rate;
        const int frames = static_cast<int>(nominal_remainder / packets_per_second);
        nominal_remainder %= packets_per_second;
        return std::max(1, frames);
    }

    bool prepareOutputLocked(Slot& slot) {
        std::array<int, kPacketsPerTransfer> lengths{};
        int total = 0;
        const long long saved_nominal = nominal_remainder;
        const long long saved_feedback = feedback_remainder_q16;
        for (int index = 0; index < kPacketsPerTransfer; ++index) {
            const int length = nextPacketFramesLocked() * bytes_per_frame;
            if (length <= 0 || length > output_max_packet_size) {
                last_error = "Computed USB packet exceeds endpoint max packet size.";
                failed = true;
                nominal_remainder = saved_nominal;
                feedback_remainder_q16 = saved_feedback;
                return false;
            }
            lengths[index] = length;
            total += length;
        }
        if (fifo.size() < static_cast<std::size_t>(total)) {
            nominal_remainder = saved_nominal;
            feedback_remainder_q16 = saved_feedback;
            return false;
        }
        if (!fifo.read(slot.buffer.data(), static_cast<std::size_t>(total))) {
            nominal_remainder = saved_nominal;
            feedback_remainder_q16 = saved_feedback;
            last_error = "USB frame FIFO read failed.";
            failed = true;
            return false;
        }
        slot.source_spans.clear();
        long long remaining_frames = total / bytes_per_frame;
        while (remaining_frames > 0 && !fifo_source_spans.empty()) {
            SourceSpan& front = fifo_source_spans.front();
            const long long taken = std::min(remaining_frames, front.frames);
            if (taken > 0 && front.timeline_generation > 0) {
                if (!slot.source_spans.empty() &&
                    slot.source_spans.back().timeline_generation == front.timeline_generation) {
                    slot.source_spans.back().frames += taken;
                } else {
                    slot.source_spans.push_back({front.timeline_generation, taken});
                }
            }
            front.frames -= taken;
            remaining_frames -= taken;
            if (front.frames == 0) fifo_source_spans.pop_front();
        }
        if (remaining_frames != 0) {
            last_error = "USB source provenance FIFO diverged from the frame FIFO.";
            failed = true;
            return false;
        }
        libusb_fill_iso_transfer(
            slot.transfer,
            handle,
            static_cast<unsigned char>(output_endpoint),
            slot.buffer.data(),
            total,
            kPacketsPerTransfer,
            transferCallback,
            &slot,
            1000);
        for (int index = 0; index < kPacketsPerTransfer; ++index) {
            slot.transfer->iso_packet_desc[index].length = lengths[index];
        }
        slot.requested_bytes = total;
        slot.generation = generation;
        fifo_changed.notify_all();
        return true;
    }

    bool submitPrepared(Slot& slot, long long expected_generation) {
        std::lock_guard<std::mutex> action(usb_action_mutex);
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (stopping || failed || flushing || !activated || slot.active ||
                generation != expected_generation || slot.generation != expected_generation) {
                return false;
            }
            slot.active = true;
            ++active_output;
        }
        const int result = libusb_submit_transfer(slot.transfer);
        if (result == LIBUSB_SUCCESS) {
            std::lock_guard<std::mutex> lock(mutex);
            submitted_bytes += slot.requested_bytes;
            ++submitted_transfers;
            submitted_packets += kPacketsPerTransfer;
            return true;
        }
        std::lock_guard<std::mutex> lock(mutex);
        slot.active = false;
        --active_output;
        failed = true;
        last_error = libusbError("libusb_submit_transfer output", result);
        state_changed.notify_all();
        fifo_changed.notify_all();
        return false;
    }

    bool submitFeedback(Slot& slot) {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (stopping || failed || !activated || slot.active) return false;
            std::memset(slot.buffer.data(), 0, slot.buffer.size());
            libusb_fill_iso_transfer(
                slot.transfer,
                handle,
                static_cast<unsigned char>(feedback_endpoint),
                slot.buffer.data(),
                static_cast<int>(slot.buffer.size()),
                1,
                transferCallback,
                &slot,
                1000);
            libusb_set_iso_packet_lengths(slot.transfer, static_cast<unsigned int>(slot.buffer.size()));
            slot.generation = generation;
            slot.active = true;
            ++active_feedback;
        }
        std::lock_guard<std::mutex> action(usb_action_mutex);
        const int result = libusb_submit_transfer(slot.transfer);
        if (result == LIBUSB_SUCCESS) return true;
        std::lock_guard<std::mutex> lock(mutex);
        slot.active = false;
        --active_feedback;
        failed = true;
        last_error = libusbError("libusb_submit_transfer feedback", result);
        state_changed.notify_all();
        return false;
    }

    void onTransferComplete(Slot& slot) {
        bool resubmit_feedback = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!slot.active) return;
            slot.active = false;
            if (slot.feedback) {
                --active_feedback;
                if (slot.transfer->status == LIBUSB_TRANSFER_COMPLETED &&
                    slot.transfer->iso_packet_desc[0].status == LIBUSB_TRANSFER_COMPLETED) {
                    const int actual = std::min<int>(
                        slot.transfer->iso_packet_desc[0].actual_length,
                        static_cast<int>(slot.buffer.size()));
                    if (actual >= 3) {
                        int raw = 0;
                        for (int index = 0; index < std::min(actual, 4); ++index) {
                            raw |= static_cast<int>(slot.buffer[index]) << (index * 8);
                        }
                        const int q16 = actual >= 4 ? raw : raw << 2;
                        feedback_q16.store(q16, std::memory_order_relaxed);
                    }
                }
                resubmit_feedback = !stopping && !failed && activated;
            } else {
                --active_output;
                if (slot.transfer->status == LIBUSB_TRANSFER_COMPLETED) {
                    for (const SourceSpan& span : slot.source_spans) {
                        source_frame_clock.complete(span.timeline_generation, span.frames);
                    }
                }
                slot.source_spans.clear();
                if (slot.transfer->status != LIBUSB_TRANSFER_COMPLETED &&
                    slot.transfer->status != LIBUSB_TRANSFER_CANCELLED) {
                    failed = true;
                    last_error = std::string("libusb output completion failed: status=") +
                        std::to_string(slot.transfer->status);
                }
            }
            state_changed.notify_all();
            fifo_changed.notify_all();
        }
        if (resubmit_feedback) submitFeedback(slot);
    }

    void serviceOutput() {
        output_service_seam.run([&]() {
            while (true) {
                Slot* candidate = nullptr;
                long long expected_generation = 0;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (stopping || failed || flushing || !activated || tail_padding_reserved ||
                        active_output >= kActiveOutputTransferTarget) return;
                    for (const auto& owned : output_slots) {
                        if (!owned->active) {
                            candidate = owned.get();
                            break;
                        }
                    }
                    if (candidate == nullptr || !prepareOutputLocked(*candidate)) {
                        if (!failed && active_output == 0) ++underruns;
                        return;
                    }
                    expected_generation = generation;
                }
                if (!submitPrepared(*candidate, expected_generation)) return;
            }
        });
    }

    void eventLoop() {
        const int priority_result = setpriority(PRIO_PROCESS, 0, -19);
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "libusb event thread started outputTransfers=%d activeTarget=%d packetsPerTransfer=%d priorityResult=%d",
            kOutputTransferCount,
            kActiveOutputTransferTarget,
            kPacketsPerTransfer,
            priority_result);
        while (true) {
            serviceOutput();
            timeval timeout{0, 5000};
            const int result = libusb_handle_events_timeout_completed(context, &timeout, nullptr);
            if (result != LIBUSB_SUCCESS && result != LIBUSB_ERROR_INTERRUPTED) {
                std::lock_guard<std::mutex> lock(mutex);
                if (!stopping) {
                    failed = true;
                    last_error = libusbError("libusb_handle_events", result);
                }
            }
            std::lock_guard<std::mutex> lock(mutex);
            if (stopping && active_output == 0 && active_feedback == 0) break;
        }
        __android_log_print(ANDROID_LOG_INFO, kTag, "libusb event thread stopped");
    }
};

bool LibusbStreamEngine::open(
    int fd,
    int output_endpoint,
    int output_max_packet_size,
    int feedback_endpoint,
    int feedback_packet_size,
    long long generation,
    std::string* error) {
    close();
    auto impl = std::make_unique<Impl>();
    const auto release_allocated_transfers = [&]() {
        for (const auto& slot : impl->output_slots) {
            if (slot->transfer != nullptr) libusb_free_transfer(slot->transfer);
        }
        for (const auto& slot : impl->feedback_slots) {
            if (slot->transfer != nullptr) libusb_free_transfer(slot->transfer);
        }
        impl->output_slots.clear();
        impl->feedback_slots.clear();
    };
    impl->generation = generation;
    impl->output_endpoint = output_endpoint;
    impl->output_max_packet_size = output_max_packet_size;
    impl->feedback_endpoint = feedback_endpoint;
    impl->feedback_packet_size = std::min(4, std::max(3, feedback_packet_size));
    int result = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (result != LIBUSB_SUCCESS) {
        if (error) *error = libusbError("libusb_set_option NO_DEVICE_DISCOVERY", result);
        return false;
    }
    result = libusb_init(&impl->context);
    if (result != LIBUSB_SUCCESS) {
        if (error) *error = libusbError("libusb_init", result);
        return false;
    }
    result = libusb_wrap_sys_device(impl->context, static_cast<intptr_t>(fd), &impl->handle);
    if (result != LIBUSB_SUCCESS || impl->handle == nullptr) {
        if (error) *error = libusbError("libusb_wrap_sys_device", result);
        libusb_exit(impl->context);
        return false;
    }
    for (int index = 0; index < kOutputTransferCount; ++index) {
        auto slot = std::make_unique<Impl::Slot>();
        slot->owner = impl.get();
        slot->buffer.resize(output_max_packet_size * kPacketsPerTransfer);
        slot->transfer = libusb_alloc_transfer(kPacketsPerTransfer);
        if (slot->transfer == nullptr) {
            if (error) *error = "libusb_alloc_transfer output failed.";
            release_allocated_transfers();
            libusb_close(impl->handle);
            libusb_exit(impl->context);
            return false;
        }
        impl->output_slots.push_back(std::move(slot));
    }
    if (feedback_endpoint != 0 && feedback_packet_size > 0) {
        for (int index = 0; index < kFeedbackTransferCount; ++index) {
            auto slot = std::make_unique<Impl::Slot>();
            slot->owner = impl.get();
            slot->feedback = true;
            slot->buffer.resize(impl->feedback_packet_size);
            slot->transfer = libusb_alloc_transfer(1);
            if (slot->transfer == nullptr) {
                if (error) *error = "libusb_alloc_transfer feedback failed.";
                release_allocated_transfers();
                libusb_close(impl->handle);
                libusb_exit(impl->context);
                return false;
            }
            impl->feedback_slots.push_back(std::move(slot));
        }
    }
    impl_ = impl.release();
    impl_->event_thread_started = true;
    impl_->event_thread = std::thread([this]() { impl_->eventLoop(); });
    return true;
}

bool LibusbStreamEngine::configure(
    int sample_rate,
    int packets_per_second,
    int bytes_per_frame,
    int target_buffer_ms,
    long long generation,
    std::string* error) {
    if (impl_ == nullptr) {
        if (error) *error = "libusb stream is not open.";
        return false;
    }
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->generation != generation || impl_->stopping) {
        if (error) *error = "USB exclusive generation is stale.";
        return false;
    }
    if (sample_rate <= 0 || packets_per_second <= 0 || bytes_per_frame <= 0) {
        if (error) *error = "USB stream configuration is invalid.";
        return false;
    }
    const long long capacity_frames = std::max<long long>(
        sample_rate / 20,
        (static_cast<long long>(sample_rate) * target_buffer_ms + 999) / 1000);
    const long long capacity_bytes = capacity_frames * bytes_per_frame;
    if (capacity_bytes <= 0 || capacity_bytes > 64LL * 1024LL * 1024LL) {
        if (error) *error = "USB stream FIFO capacity is invalid.";
        return false;
    }
    impl_->sample_rate = sample_rate;
    impl_->packets_per_second = packets_per_second;
    impl_->bytes_per_frame = bytes_per_frame;
    impl_->fifo.reset(static_cast<std::size_t>(capacity_bytes), bytes_per_frame);
    impl_->fifo_source_spans.clear();
    impl_->source_frame_clock.reset();
    impl_->source_frame_clock.beginTimeline();
    impl_->nominal_remainder = 0;
    impl_->feedback_remainder_q16 = 0;
    impl_->tail_padding_reserved = false;
    impl_->tail_padding_generation = 0;
    impl_->tail_padding_target_frames = 0;
    impl_->configured = true;
    return true;
}

bool LibusbStreamEngine::activate(long long generation, std::string* error) {
    if (impl_ == nullptr) {
        if (error) *error = "libusb stream is not open.";
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (impl_->generation != generation || !impl_->configured || impl_->stopping) {
            if (error) *error = "USB stream activation is stale or unconfigured.";
            return false;
        }
        impl_->activated = true;
    }
    for (const auto& slot : impl_->feedback_slots) impl_->submitFeedback(*slot);
    impl_->serviceOutput();
    const auto telemetry = this->telemetry();
    if (telemetry.active_output_transfers <= 0) {
        if (error) *error = "USB stream activation has insufficient FIFO prefill.";
        return false;
    }
    return true;
}

bool LibusbStreamEngine::enqueue(
    const uint8_t* data,
    int length,
    long long generation,
    long long source_timeline_generation,
    std::string* error) {
    if (impl_ == nullptr || data == nullptr || length <= 0) return true;
    int offset = 0;
    std::unique_lock<std::mutex> lock(impl_->mutex);
    while (offset < length) {
        if (impl_->generation != generation || impl_->stopping || impl_->failed) {
            if (error) *error = impl_->failed ? impl_->last_error : "USB exclusive generation is stale.";
            return false;
        }
        if (source_timeline_generation > 0 &&
            source_timeline_generation != impl_->source_frame_clock.timelineGeneration()) {
            if (error) *error = "USB source timeline generation is stale.";
            return false;
        }
        if (length % impl_->bytes_per_frame != 0) {
            if (error) *error = "USB frame write is not frame aligned.";
            return false;
        }
        const int available = static_cast<int>(impl_->fifo.available());
        const int remaining = length - offset;
        const int writable = std::min(remaining, available) / impl_->bytes_per_frame * impl_->bytes_per_frame;
        if (writable > 0) {
            impl_->fifo.write(data + offset, static_cast<std::size_t>(writable));
            const long long written_frames = writable / impl_->bytes_per_frame;
            if (!impl_->fifo_source_spans.empty() &&
                impl_->fifo_source_spans.back().timeline_generation == source_timeline_generation) {
                impl_->fifo_source_spans.back().frames += written_frames;
            } else {
                impl_->fifo_source_spans.push_back({source_timeline_generation, written_frames});
            }
            offset += writable;
            lock.unlock();
            impl_->serviceOutput();
            lock.lock();
            continue;
        }
        const bool ready = impl_->fifo_changed.wait_for(
            lock,
            std::chrono::milliseconds(kProducerWaitMs),
            [&]() {
                return impl_->fifo.available() >= static_cast<std::size_t>(impl_->bytes_per_frame) ||
                    impl_->generation != generation || impl_->stopping || impl_->failed;
            });
        if (!ready) {
            if (error) *error = "USB exclusive frame FIFO stalled for 1000 ms.";
            return false;
        }
    }
    return true;
}

long long LibusbStreamEngine::beginSourceTimeline(long long generation, std::string* error) {
    if (impl_ == nullptr) {
        if (error) *error = "libusb stream is not open.";
        return -1;
    }
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->generation != generation || impl_->stopping || impl_->failed) {
        if (error) *error = impl_->failed ? impl_->last_error : "USB exclusive generation is stale.";
        return -1;
    }
    return impl_->source_frame_clock.beginTimeline();
}

long long LibusbStreamEngine::consumedSourceFrames(
    long long generation,
    long long source_timeline_generation) const {
    if (impl_ == nullptr) return -1;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->generation != generation || impl_->stopping || impl_->failed ||
        impl_->source_frame_clock.timelineGeneration() != source_timeline_generation) return -1;
    return impl_->source_frame_clock.completedFrames();
}

int LibusbStreamEngine::reserveTailPaddingFrames(long long generation, std::string* error) {
    if (impl_ == nullptr) {
        if (error) *error = "libusb stream is not open.";
        return -2;
    }
    return impl_->output_service_seam.run([&]() {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (impl_->generation != generation || impl_->stopping || impl_->failed ||
            !impl_->configured || !impl_->activated) {
            if (error) {
                *error = impl_->failed ? impl_->last_error : "USB exclusive generation is stale.";
            }
            return -2;
        }
        if (impl_->tail_padding_reserved) {
            if (error) *error = "USB output tail padding is already reserved.";
            return -2;
        }
        if (impl_->active_output > 0) return -1;

        const long long fifo_frames =
            static_cast<long long>(impl_->fifo.size()) / impl_->bytes_per_frame;
        if (fifo_frames == 0) return 0;

        const long long saved_nominal = impl_->nominal_remainder;
        const long long saved_feedback = impl_->feedback_remainder_q16;
        int transfer_frames = 0;
        for (int index = 0; index < kPacketsPerTransfer; ++index) {
            transfer_frames += impl_->nextPacketFramesLocked();
        }
        impl_->nominal_remainder = saved_nominal;
        impl_->feedback_remainder_q16 = saved_feedback;

        const int padding_frames = usbTailPaddingFrames(fifo_frames, transfer_frames);
        if (padding_frames < 0) return -1;
        if (padding_frames == 0) return 0;
        impl_->tail_padding_reserved = true;
        impl_->tail_padding_generation = generation;
        impl_->tail_padding_target_frames = transfer_frames;
        return padding_frames;
    });
}

bool LibusbStreamEngine::commitTailPadding(long long generation, std::string* error) {
    if (impl_ == nullptr) {
        if (error) *error = "libusb stream is not open.";
        return false;
    }
    const bool committed = impl_->output_service_seam.run([&]() {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (impl_->generation != generation || impl_->stopping || impl_->failed ||
            !impl_->tail_padding_reserved || impl_->tail_padding_generation != generation) {
            if (error) {
                *error = impl_->failed ? impl_->last_error :
                    "USB output tail padding reservation is stale.";
            }
            return false;
        }
        const long long fifo_frames =
            static_cast<long long>(impl_->fifo.size()) / impl_->bytes_per_frame;
        if (fifo_frames != impl_->tail_padding_target_frames) {
            if (error) *error = "USB output tail padding did not complete exactly one transfer.";
            return false;
        }
        impl_->tail_padding_reserved = false;
        impl_->tail_padding_generation = 0;
        impl_->tail_padding_target_frames = 0;
        return true;
    });
    if (committed) impl_->serviceOutput();
    return committed;
}

bool LibusbStreamEngine::flush(long long next_generation, std::string* error) {
    if (impl_ == nullptr) return true;
    std::vector<libusb_transfer*> to_cancel;
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->generation = next_generation;
        impl_->flushing = true;
        impl_->fifo.clear();
        impl_->fifo_source_spans.clear();
        impl_->source_frame_clock.beginTimeline();
        impl_->nominal_remainder = 0;
        impl_->feedback_remainder_q16 = 0;
        impl_->tail_padding_reserved = false;
        impl_->tail_padding_generation = 0;
        impl_->tail_padding_target_frames = 0;
        for (const auto& slot : impl_->output_slots) {
            if (slot->active) to_cancel.push_back(slot->transfer);
        }
        impl_->fifo_changed.notify_all();
    }
    {
        std::lock_guard<std::mutex> action(impl_->usb_action_mutex);
        for (auto* transfer : to_cancel) libusb_cancel_transfer(transfer);
    }
    std::unique_lock<std::mutex> lock(impl_->mutex);
    const bool drained = impl_->state_changed.wait_for(
        lock,
        std::chrono::milliseconds(kFlushWaitMs),
        [&]() { return impl_->active_output == 0 || impl_->stopping; });
    impl_->flushing = false;
    if (!drained) {
        if (error) *error = "libusb output flush timed out.";
        return false;
    }
    return true;
}

void LibusbStreamEngine::invalidate(long long generation) {
    if (impl_ == nullptr) return;
    std::vector<libusb_transfer*> to_cancel;
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (generation <= impl_->generation) return;
        impl_->generation = generation;
        impl_->activated = false;
        impl_->fifo.clear();
        impl_->fifo_source_spans.clear();
        impl_->source_frame_clock.beginTimeline();
        impl_->tail_padding_reserved = false;
        impl_->tail_padding_generation = 0;
        impl_->tail_padding_target_frames = 0;
        for (const auto& slot : impl_->output_slots) if (slot->active) to_cancel.push_back(slot->transfer);
        for (const auto& slot : impl_->feedback_slots) if (slot->active) to_cancel.push_back(slot->transfer);
        impl_->fifo_changed.notify_all();
    }
    std::lock_guard<std::mutex> action(impl_->usb_action_mutex);
    for (auto* transfer : to_cancel) libusb_cancel_transfer(transfer);
}

void LibusbStreamEngine::close() {
    Impl* impl = impl_;
    if (impl == nullptr) return;
    std::vector<libusb_transfer*> to_cancel;
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        impl->stopping = true;
        impl->activated = false;
        impl->fifo.clear();
        impl->fifo_source_spans.clear();
        for (const auto& slot : impl->output_slots) if (slot->active) to_cancel.push_back(slot->transfer);
        for (const auto& slot : impl->feedback_slots) if (slot->active) to_cancel.push_back(slot->transfer);
        impl->fifo_changed.notify_all();
    }
    {
        std::lock_guard<std::mutex> action(impl->usb_action_mutex);
        for (auto* transfer : to_cancel) libusb_cancel_transfer(transfer);
    }
    if (impl->event_thread.joinable()) impl->event_thread.join();
    for (const auto& slot : impl->output_slots) libusb_free_transfer(slot->transfer);
    for (const auto& slot : impl->feedback_slots) libusb_free_transfer(slot->transfer);
    if (impl->handle != nullptr) libusb_close(impl->handle);
    if (impl->context != nullptr) libusb_exit(impl->context);
    impl_ = nullptr;
    delete impl;
}

int LibusbStreamEngine::feedbackFramesPerPacketQ16() const {
    return impl_ == nullptr ? 0 : impl_->feedback_q16.load(std::memory_order_relaxed);
}

LibusbStreamTelemetry LibusbStreamEngine::telemetry() const {
    LibusbStreamTelemetry result;
    if (impl_ == nullptr) return result;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    result.fifo_bytes = static_cast<long long>(impl_->fifo.size());
    result.fifo_capacity_bytes = static_cast<long long>(impl_->fifo.capacity());
    result.submitted_bytes = impl_->submitted_bytes;
    result.submitted_transfers = impl_->submitted_transfers;
    result.submitted_packets = impl_->submitted_packets;
    result.underruns = impl_->underruns;
    result.active_output_transfers = impl_->active_output;
    if (impl_->bytes_per_frame > 0 && impl_->sample_rate > 0) {
        const long long fifo_frames = result.fifo_bytes / impl_->bytes_per_frame;
        result.buffered_packets =
            (fifo_frames * impl_->packets_per_second + impl_->sample_rate - 1) /
            impl_->sample_rate;
    }
    return result;
}

std::string LibusbStreamEngine::lastError() const {
    if (impl_ == nullptr) return {};
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->last_error;
}
