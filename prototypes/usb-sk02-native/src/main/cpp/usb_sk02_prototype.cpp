// THROWAWAY PROTOTYPE: prove an Android UsbDeviceConnection fd can reach USBFS from JNI and
// sustain an SK02-specific asynchronous isochronous PCM queue. Do not treat this as a reusable
// USB audio engine: device matching, lifecycle, cancellation, and recovery are intentionally narrow.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <linux/usbdevice_fs.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/ioctl.h>
#include <thread>
#include <vector>

#include "usb_underrun_accounting.h"

namespace {

std::atomic<long long> active_generation{0};

constexpr const char* kLogTag = "MicaUsbPrototype";

std::string query_driver(const int fd, const int interface_number) {
    usbdevfs_getdriver request{};
    request.interface = static_cast<unsigned int>(interface_number);
    const int result = ioctl(fd, USBDEVFS_GETDRIVER, &request);
    if (result == 0) {
        return "driver=" + std::string(request.driver) + " errno=0";
    }
    const int saved_errno = errno;
    return "driver=null errno=" + std::to_string(saved_errno) +
        " message=" + std::string(std::strerror(saved_errno));
}

int connect_kernel_driver(const int fd, const int interface_number) {
    usbdevfs_ioctl command{};
    command.ifno = interface_number;
    command.ioctl_code = USBDEVFS_CONNECT;
    command.data = nullptr;
    const auto started = std::chrono::steady_clock::now();
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "kernelConnect=begin interface=%d",
        interface_number);
    const int result = ioctl(fd, USBDEVFS_IOCTL, &command);
    const int saved_errno = result == 0 ? 0 : errno;
    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "kernelConnect=end interface=%d errno=%d elapsedMs=%lld",
        interface_number,
        saved_errno,
        static_cast<long long>(elapsed_ms));
    return saved_errno;
}

std::string read_feedback_once(const int fd) {
    constexpr int kPacketBytes = 4;
    constexpr int kTimeoutMs = 2'000;
    std::vector<unsigned char> buffer(kPacketBytes, 0);
    std::vector<unsigned char> storage(
        sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc),
        0);
    auto* urb = reinterpret_cast<usbdevfs_urb*>(storage.data());
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = 0x84;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = buffer.data();
    urb->buffer_length = kPacketBytes;
    urb->number_of_packets = 1;
    urb->iso_frame_desc[0].length = kPacketBytes;

    if (ioctl(fd, USBDEVFS_SUBMITURB, urb) != 0) {
        const int saved_errno = errno;
        return "submit=-1 errno=" + std::to_string(saved_errno) +
            " message=" + std::string(std::strerror(saved_errno));
    }

    pollfd poll_descriptor{fd, POLLOUT, 0};
    const int poll_result = poll(&poll_descriptor, 1, kTimeoutMs);
    if (poll_result <= 0) {
        const int poll_errno = errno;
        ioctl(fd, USBDEVFS_DISCARDURB, urb);
        void* discarded = nullptr;
        ioctl(fd, USBDEVFS_REAPURBNDELAY, &discarded);
        return "submit=0 poll=" + std::to_string(poll_result) +
            " errno=" + std::to_string(poll_errno);
    }

    void* completed = nullptr;
    if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed) != 0) {
        const int saved_errno = errno;
        return "submit=0 reap=-1 errno=" + std::to_string(saved_errno) +
            " message=" + std::string(std::strerror(saved_errno));
    }
    if (completed != urb) {
        return "submit=0 reap=unexpected_urb";
    }

    const int actual = urb->iso_frame_desc[0].actual_length;
    unsigned long feedback = 0;
    for (int index = 0; index < actual && index < kPacketBytes; ++index) {
        feedback |= static_cast<unsigned long>(buffer[index]) << (index * 8);
    }
    const double frames_per_microframe = static_cast<double>(feedback) / 65'536.0;
    const double sample_rate_hz = frames_per_microframe * 8'000.0;
    char hex[9]{};
    std::snprintf(
        hex,
        sizeof(hex),
        "%02x%02x%02x%02x",
        buffer[0],
        buffer[1],
        buffer[2],
        buffer[3]);
    return "submit=0 reap=0 urbStatus=" + std::to_string(urb->status) +
        " errorCount=" + std::to_string(urb->error_count) +
        " packetStatus=" + std::to_string(urb->iso_frame_desc[0].status) +
        " actualBytes=" + std::to_string(actual) +
        " rawLe=" + std::string(hex) +
        " feedbackValue=" + std::to_string(feedback) +
        " framesPerMicroframe=" + std::to_string(frames_per_microframe) +
        " sampleRateHz=" + std::to_string(sample_rate_hz);
}

std::string write_silent_pcm16_once(const int fd) {
    constexpr int kPackets = 80;  // 10 ms at one high-speed microframe per packet.
    constexpr int kSampleRate = 44'100;
    constexpr int kMicroframesPerSecond = 8'000;
    constexpr int kBytesPerStereoFrame = 4;
    constexpr int kTimeoutMs = 2'000;

    std::vector<unsigned int> packet_lengths(kPackets, 0);
    int phase = 0;
    int total_bytes = 0;
    for (int index = 0; index < kPackets; ++index) {
        phase += kSampleRate;
        const int frames = phase / kMicroframesPerSecond;
        phase %= kMicroframesPerSecond;
        packet_lengths[index] = static_cast<unsigned int>(frames * kBytesPerStereoFrame);
        total_bytes += static_cast<int>(packet_lengths[index]);
    }
    std::vector<unsigned char> buffer(total_bytes, 0);
    std::vector<unsigned char> storage(
        sizeof(usbdevfs_urb) + kPackets * sizeof(usbdevfs_iso_packet_desc),
        0);
    auto* urb = reinterpret_cast<usbdevfs_urb*>(storage.data());
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = 0x03;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = buffer.data();
    urb->buffer_length = total_bytes;
    urb->number_of_packets = kPackets;
    for (int index = 0; index < kPackets; ++index) {
        urb->iso_frame_desc[index].length = packet_lengths[index];
    }

    if (ioctl(fd, USBDEVFS_SUBMITURB, urb) != 0) {
        const int saved_errno = errno;
        return "submit=-1 errno=" + std::to_string(saved_errno) +
            " message=" + std::string(std::strerror(saved_errno)) + " writtenBytes=0";
    }
    pollfd poll_descriptor{fd, POLLOUT, 0};
    const int poll_result = poll(&poll_descriptor, 1, kTimeoutMs);
    if (poll_result <= 0) {
        const int poll_errno = errno;
        ioctl(fd, USBDEVFS_DISCARDURB, urb);
        void* discarded = nullptr;
        ioctl(fd, USBDEVFS_REAPURBNDELAY, &discarded);
        return "submit=0 poll=" + std::to_string(poll_result) +
            " errno=" + std::to_string(poll_errno) + " writtenBytes=0";
    }
    void* completed = nullptr;
    if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed) != 0 || completed != urb) {
        const int saved_errno = errno;
        return "submit=0 reap=-1 errno=" + std::to_string(saved_errno) + " writtenBytes=0";
    }
    int successful_bytes = 0;
    int failed_packets = 0;
    for (int index = 0; index < kPackets; ++index) {
        if (urb->iso_frame_desc[index].status == 0) {
            successful_bytes += static_cast<int>(packet_lengths[index]);
        } else {
            ++failed_packets;
        }
    }
    return "submit=0 reap=0 urbStatus=" + std::to_string(urb->status) +
        " errorCount=" + std::to_string(urb->error_count) +
        " failedPackets=" + std::to_string(failed_packets) +
        " requestedBytes=" + std::to_string(total_bytes) +
        " writtenBytes=" + std::to_string(successful_bytes) +
        " packets=" + std::to_string(kPackets) +
        " finalPhase=" + std::to_string(phase);
}

struct IsoRequest {
    std::vector<unsigned char> storage;
    std::vector<unsigned char> buffer;
    usbdevfs_urb* urb;
    bool feedback;
    bool submitted = false;
    size_t source_bytes = 0;

    IsoRequest(const int packets, const int buffer_bytes, const bool is_feedback)
        : storage(sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc), 0),
          buffer(buffer_bytes, 0),
          urb(reinterpret_cast<usbdevfs_urb*>(storage.data())),
          feedback(is_feedback) {
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = feedback ? 0x84 : 0x03;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = buffer.data();
        urb->buffer_length = buffer_bytes;
        urb->number_of_packets = packets;
    }
};

/**
 * THROWAWAY Media3 bridge for the already-proven SK02 USBFS transport.
 *
 * The Java side owns permission, interface claims, clock selection and restoration. This object
 * owns the only running URB queue. Writes are non-blocking and feed a bounded two-second ring;
 * the USB worker emits silence instead of replaying stale source bytes on underrun or pause.
 */
class Media3StreamSession {
public:
    Media3StreamSession(
        const int descriptor,
        const int rate,
        const int frame_bytes,
        const int packet_bytes,
        const long long generation)
        : fd(descriptor),
          sample_rate_hz(rate),
          bytes_per_frame(frame_bytes),
          max_packet_bytes(packet_bytes),
          expected_generation(generation),
          ring(static_cast<size_t>(rate) * static_cast<size_t>(frame_bytes) * 2U, 0) {
        worker = std::thread(&Media3StreamSession::run, this);
    }

    ~Media3StreamSession() {
        shutdown();
    }

    Media3StreamSession(const Media3StreamSession&) = delete;
    Media3StreamSession& operator=(const Media3StreamSession&) = delete;

    int write(const unsigned char* source, const int length) {
        if (source == nullptr || length <= 0 || length % bytes_per_frame != 0) return 0;
        std::lock_guard<std::mutex> guard(mutex);
        if (stop_requested.load(std::memory_order_acquire) || error_code.load() != 0) return 0;
        const size_t writable = ring.size() - ring_size;
        const size_t aligned = std::min(
            static_cast<size_t>(length),
            writable - writable % static_cast<size_t>(bytes_per_frame));
        for (size_t index = 0; index < aligned; ++index) {
            ring[ring_tail] = source[index];
            ring_tail = (ring_tail + 1U) % ring.size();
        }
        ring_size += aligned;
        queued_bytes.fetch_add(static_cast<unsigned long long>(aligned));
        condition.notify_all();
        return static_cast<int>(aligned);
    }

    void set_playing(const bool value) {
        const bool previous = playing.exchange(value, std::memory_order_acq_rel);
        if (value && !previous) {
            resume_started_ns.store(now_ns(), std::memory_order_release);
            underrun_logged_for_resume.store(false, std::memory_order_release);
        }
        condition.notify_all();
    }

    void flush() {
        // The Java owner recreates the native session after this call. Keeping flush here bounded
        // makes a direct call safe and prevents any new source data from entering the old queue.
        shutdown();
    }

    void shutdown() {
        bool expected = false;
        if (shutdown_started.compare_exchange_strong(expected, true)) {
            stop_requested.store(true, std::memory_order_release);
            condition.notify_all();
            if (worker.joinable()) worker.join();
        }
    }

    long long completed_frames_value() const {
        return static_cast<long long>(
            completed_source_bytes.load(std::memory_order_acquire) /
            static_cast<unsigned long long>(bytes_per_frame));
    }

    long long buffered_frames_value() const {
        std::lock_guard<std::mutex> guard(mutex);
        return static_cast<long long>(
            (ring_size + in_flight_source_bytes) / static_cast<size_t>(bytes_per_frame));
    }

    long long underrun_bytes_value() const {
        return static_cast<long long>(underrun_bytes.load(std::memory_order_acquire));
    }

    int error_code_value() const {
        return error_code.load(std::memory_order_acquire);
    }

private:
    static long long now_ns() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    bool is_current() const {
        return active_generation.load(std::memory_order_acquire) == expected_generation;
    }

    SourceTakeResult take_source(unsigned char* target, const size_t requested) {
        std::lock_guard<std::mutex> guard(mutex);
        const bool playing_when_taken = playing.load(std::memory_order_acquire);
        if (!playing_when_taken) return {0, false};
        const size_t available = std::min(requested, ring_size);
        const size_t aligned = available - available % static_cast<size_t>(bytes_per_frame);
        for (size_t index = 0; index < aligned; ++index) {
            target[index] = ring[ring_head];
            ring_head = (ring_head + 1U) % ring.size();
        }
        ring_size -= aligned;
        in_flight_source_bytes += aligned;
        return {aligned, true};
    }

    void complete_source(const size_t bytes) {
        std::lock_guard<std::mutex> guard(mutex);
        const size_t accounted = std::min(bytes, in_flight_source_bytes);
        in_flight_source_bytes -= accounted;
        completed_source_bytes.fetch_add(static_cast<unsigned long long>(accounted));
    }

    void abandon_source(const size_t bytes) {
        std::lock_guard<std::mutex> guard(mutex);
        in_flight_source_bytes -= std::min(bytes, in_flight_source_bytes);
    }

    void run() {
        constexpr int kDataQueueDepth = 8;
        constexpr int kFeedbackQueueDepth = 4;
        constexpr int kDataPackets = 8;
        const unsigned long initial_feedback =
            static_cast<unsigned long>(sample_rate_hz) * 65'536UL / 8'000UL;

        std::vector<std::unique_ptr<IsoRequest>> requests;
        requests.reserve(kDataQueueDepth + kFeedbackQueueDepth);
        for (int index = 0; index < kDataQueueDepth; ++index) {
            requests.push_back(std::make_unique<IsoRequest>(
                kDataPackets,
                kDataPackets * max_packet_bytes,
                false));
        }
        for (int index = 0; index < kFeedbackQueueDepth; ++index) {
            auto request = std::make_unique<IsoRequest>(1, 4, true);
            request->urb->iso_frame_desc[0].length = 4;
            requests.push_back(std::move(request));
        }

        {
            std::unique_lock<std::mutex> guard(mutex);
            const size_t prebuffer = static_cast<size_t>(sample_rate_hz) *
                static_cast<size_t>(bytes_per_frame) / 20U;
            condition.wait_for(guard, std::chrono::seconds(2), [&]() {
                return stop_requested.load(std::memory_order_acquire) ||
                    !is_current() || ring_size >= prebuffer;
            });
        }
        if (stop_requested.load(std::memory_order_acquire) || !is_current()) return;

        unsigned long feedback = initial_feedback;
        unsigned long long phase = 0;
        const auto fill_data = [&](IsoRequest& request) {
            int total = 0;
            for (int packet = 0; packet < kDataPackets; ++packet) {
                phase += feedback;
                const unsigned long frames = static_cast<unsigned long>(phase >> 16);
                phase &= 0xffff;
                unsigned int bytes = static_cast<unsigned int>(frames * bytes_per_frame);
                if (bytes > static_cast<unsigned int>(max_packet_bytes)) {
                    bytes = static_cast<unsigned int>(max_packet_bytes / bytes_per_frame) *
                        static_cast<unsigned int>(bytes_per_frame);
                }
                request.urb->iso_frame_desc[packet].length = bytes;
                request.urb->iso_frame_desc[packet].actual_length = 0;
                request.urb->iso_frame_desc[packet].status = 0;
                total += static_cast<int>(bytes);
            }
            request.urb->buffer_length = total;
            request.urb->status = 0;
            request.urb->error_count = 0;
            const SourceTakeResult take =
                take_source(request.buffer.data(), static_cast<size_t>(total));
            request.source_bytes = take.bytes;
            if (request.source_bytes < static_cast<size_t>(total)) {
                std::fill(
                    request.buffer.begin() + static_cast<std::ptrdiff_t>(request.source_bytes),
                    request.buffer.begin() + total,
                    0);
                if (should_count_underrun(
                        take,
                        static_cast<size_t>(total))) {
                    const auto missing = static_cast<unsigned long long>(total) - request.source_bytes;
                    underrun_bytes.fetch_add(missing);
                    bool expected = false;
                    if (underrun_logged_for_resume.compare_exchange_strong(expected, true)) {
                        const long long started = resume_started_ns.load(std::memory_order_acquire);
                        const long long elapsed_us = started == 0 ? -1 : (now_ns() - started) / 1'000;
                        __android_log_print(
                            ANDROID_LOG_ERROR,
                            kLogTag,
                            "[DEBUG-resume-timing] underrun elapsedUs=%lld requestedBytes=%d sourceBytes=%zu missingBytes=%llu queuedBytes=%llu completedBytes=%llu",
                            elapsed_us,
                            total,
                            request.source_bytes,
                            missing,
                            queued_bytes.load(std::memory_order_acquire),
                            completed_source_bytes.load(std::memory_order_acquire));
                    }
                }
            }
        };
        const auto submit = [&](IsoRequest& request) {
            if (!is_current() || stop_requested.load(std::memory_order_acquire)) return false;
            if (!request.feedback) fill_data(request);
            if (ioctl(fd, USBDEVFS_SUBMITURB, request.urb) == 0) {
                request.submitted = true;
                return true;
            }
            if (!request.feedback) abandon_source(request.source_bytes);
            request.source_bytes = 0;
            error_code.store(errno == 0 ? EIO : errno, std::memory_order_release);
            return false;
        };

        for (const auto& request : requests) {
            if (!submit(*request)) break;
        }
        while (error_code.load(std::memory_order_acquire) == 0 &&
               !stop_requested.load(std::memory_order_acquire) && is_current()) {
            pollfd poll_descriptor{fd, POLLOUT, 0};
            const int poll_result = poll(&poll_descriptor, 1, 100);
            if (poll_result == 0) continue;
            if (poll_result < 0) {
                if (errno == EINTR) continue;
                error_code.store(errno == 0 ? EIO : errno, std::memory_order_release);
                break;
            }
            void* completed_pointer = nullptr;
            if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed_pointer) != 0) {
                if (errno == EAGAIN || errno == EINTR) continue;
                error_code.store(errno == 0 ? EIO : errno, std::memory_order_release);
                break;
            }
            IsoRequest* completed = nullptr;
            for (const auto& request : requests) {
                if (request->urb == completed_pointer) {
                    completed = request.get();
                    break;
                }
            }
            if (completed == nullptr) {
                error_code.store(EPROTO, std::memory_order_release);
                break;
            }
            completed->submitted = false;
            if (completed->urb->status != 0 || completed->urb->error_count != 0) {
                if (!completed->feedback) abandon_source(completed->source_bytes);
                completed->source_bytes = 0;
                error_code.store(EIO, std::memory_order_release);
                break;
            }
            if (completed->feedback) {
                const auto& packet = completed->urb->iso_frame_desc[0];
                if (packet.status == 0 && packet.actual_length == 4) {
                    unsigned long value = 0;
                    for (int index = 0; index < 4; ++index) {
                        value |= static_cast<unsigned long>(completed->buffer[index]) << (index * 8);
                    }
                    if (value >= 1UL * 65'536UL && value <= 64UL * 65'536UL) feedback = value;
                }
            } else {
                complete_source(completed->source_bytes);
                completed->source_bytes = 0;
            }
            if (!submit(*completed)) break;
        }

        int pending = 0;
        for (const auto& request : requests) {
            if (request->submitted) {
                ++pending;
                ioctl(fd, USBDEVFS_DISCARDURB, request->urb);
            }
        }
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
        while (pending > 0 && std::chrono::steady_clock::now() < deadline) {
            pollfd poll_descriptor{fd, POLLOUT, 0};
            if (poll(&poll_descriptor, 1, 100) <= 0) continue;
            void* completed_pointer = nullptr;
            if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed_pointer) != 0) continue;
            for (const auto& request : requests) {
                if (request->submitted && request->urb == completed_pointer) {
                    request->submitted = false;
                    if (!request->feedback) abandon_source(request->source_bytes);
                    request->source_bytes = 0;
                    --pending;
                    break;
                }
            }
        }
    }

    const int fd;
    const int sample_rate_hz;
    const int bytes_per_frame;
    const int max_packet_bytes;
    const long long expected_generation;
    mutable std::mutex mutex;
    std::condition_variable condition;
    std::vector<unsigned char> ring;
    size_t ring_head = 0;
    size_t ring_tail = 0;
    size_t ring_size = 0;
    size_t in_flight_source_bytes = 0;
    std::atomic<bool> playing{false};
    std::atomic<bool> stop_requested{false};
    std::atomic<bool> shutdown_started{false};
    std::atomic<int> error_code{0};
    std::atomic<unsigned long long> queued_bytes{0};
    std::atomic<unsigned long long> completed_source_bytes{0};
    std::atomic<unsigned long long> underrun_bytes{0};
    std::atomic<long long> resume_started_ns{0};
    std::atomic<bool> underrun_logged_for_resume{false};
    std::thread worker;
};

std::string run_pcm16_queue(
    const int fd,
    const int duration_ms,
    const std::vector<unsigned char>* source,
    const int sample_rate_hz = 44'100,
    const int bytes_per_stereo_frame = 4,
    const int max_packet_bytes = 200,
    const long long expected_generation = 0) {
    constexpr int kDataQueueDepth = 8;
    constexpr int kFeedbackQueueDepth = 4;
    constexpr int kDataPackets = 8;
    const unsigned long initial_feedback =
        static_cast<unsigned long>(sample_rate_hz) * 65'536UL / 8'000UL;

    std::vector<std::unique_ptr<IsoRequest>> requests;
    requests.reserve(kDataQueueDepth + kFeedbackQueueDepth);
    for (int index = 0; index < kDataQueueDepth; ++index) {
        requests.push_back(std::make_unique<IsoRequest>(
            kDataPackets,
            kDataPackets * max_packet_bytes,
            false));
    }
    for (int index = 0; index < kFeedbackQueueDepth; ++index) {
        auto request = std::make_unique<IsoRequest>(1, 4, true);
        request->urb->iso_frame_desc[0].length = 4;
        requests.push_back(std::move(request));
    }

    unsigned long feedback = initial_feedback;
    unsigned long min_feedback = feedback;
    unsigned long max_feedback = feedback;
    unsigned long long phase = 0;
    unsigned long long written_bytes = 0;
    unsigned long long data_packets = 0;
    unsigned long long data_urbs = 0;
    unsigned long long feedback_urbs = 0;
    unsigned long long source_bytes_queued = 0;
    unsigned long long non_zero_bytes_queued = 0;
    unsigned long long non_zero_bytes_completed = 0;
    unsigned long long source_wraps = 0;
    size_t source_offset = 0;
    int submit_errors = 0;
    int transport_errors = 0;
    int packet_errors = 0;
    bool cancelled = false;
    const auto is_current = [&]() {
        return expected_generation == 0 ||
            active_generation.load(std::memory_order_acquire) == expected_generation;
    };

    const auto fill_data = [&](IsoRequest& request) {
        int total = 0;
        for (int packet = 0; packet < kDataPackets; ++packet) {
            phase += feedback;
            const unsigned long frames = static_cast<unsigned long>(phase >> 16);
            phase &= 0xffff;
            unsigned int bytes = static_cast<unsigned int>(frames * bytes_per_stereo_frame);
            if (bytes > static_cast<unsigned int>(max_packet_bytes)) {
                bytes = static_cast<unsigned int>(max_packet_bytes / bytes_per_stereo_frame) *
                    static_cast<unsigned int>(bytes_per_stereo_frame);
                ++packet_errors;
            }
            request.urb->iso_frame_desc[packet].length = bytes;
            request.urb->iso_frame_desc[packet].actual_length = 0;
            request.urb->iso_frame_desc[packet].status = 0;
            total += static_cast<int>(bytes);
        }
        request.urb->buffer_length = total;
        request.urb->status = 0;
        request.urb->error_count = 0;
        if (source == nullptr || source->empty()) {
            std::fill(request.buffer.begin(), request.buffer.begin() + total, 0);
        } else {
            for (int index = 0; index < total; ++index) {
                const unsigned char value = (*source)[source_offset++];
                request.buffer[index] = value;
                if (value != 0) ++non_zero_bytes_queued;
                ++source_bytes_queued;
                if (source_offset == source->size()) {
                    source_offset = 0;
                    ++source_wraps;
                }
            }
        }
    };
    const auto submit = [&](IsoRequest& request) {
        if (!request.feedback) fill_data(request);
        if (ioctl(fd, USBDEVFS_SUBMITURB, request.urb) == 0) {
            request.submitted = true;
            return true;
        }
        ++submit_errors;
        return false;
    };

    for (const auto& request : requests) {
        if (!is_current()) {
            cancelled = true;
            break;
        }
        if (!submit(*request)) break;
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(duration_ms);
    while (submit_errors == 0 && std::chrono::steady_clock::now() < deadline) {
        pollfd poll_descriptor{fd, POLLOUT, 0};
        const int poll_result = poll(&poll_descriptor, 1, 500);
        if (poll_result <= 0) {
            ++transport_errors;
            break;
        }
        void* completed_pointer = nullptr;
        if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed_pointer) != 0) {
            if (errno == EAGAIN) continue;
            ++transport_errors;
            break;
        }
        if (!is_current()) cancelled = true;
        IsoRequest* completed = nullptr;
        for (const auto& request : requests) {
            if (request->urb == completed_pointer) {
                completed = request.get();
                break;
            }
        }
        if (completed == nullptr) {
            ++transport_errors;
            break;
        }
        completed->submitted = false;
        if (completed->urb->status != 0 || completed->urb->error_count != 0) {
            ++transport_errors;
        } else if (completed->feedback) {
            const auto& packet = completed->urb->iso_frame_desc[0];
            if (packet.status == 0 && packet.actual_length == 4) {
                unsigned long value = 0;
                for (int index = 0; index < 4; ++index) {
                    value |= static_cast<unsigned long>(completed->buffer[index]) << (index * 8);
                }
                if (value >= 1UL * 65'536UL && value <= 64UL * 65'536UL) {
                    feedback = value;
                    min_feedback = std::min(min_feedback, value);
                    max_feedback = std::max(max_feedback, value);
                    ++feedback_urbs;
                } else {
                    ++packet_errors;
                }
            } else {
                ++packet_errors;
            }
        } else {
            ++data_urbs;
            int buffer_offset = 0;
            for (int packet_index = 0; packet_index < kDataPackets; ++packet_index) {
                const auto& packet = completed->urb->iso_frame_desc[packet_index];
                if (packet.status == 0) {
                    written_bytes += packet.length;
                    ++data_packets;
                    for (unsigned int index = 0; index < packet.length; ++index) {
                        if (completed->buffer[buffer_offset + static_cast<int>(index)] != 0) {
                            ++non_zero_bytes_completed;
                        }
                    }
                } else {
                    ++packet_errors;
                }
                buffer_offset += static_cast<int>(packet.length);
            }
        }
        if (transport_errors == 0 && !cancelled && !submit(*completed)) break;
        if (cancelled) break;
    }
    if (!is_current()) cancelled = true;

    int discarded = 0;
    int pending = 0;
    for (const auto& request : requests) {
        if (request->submitted) {
            ++pending;
            if (ioctl(fd, USBDEVFS_DISCARDURB, request->urb) == 0) ++discarded;
        }
    }
    const auto drain_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (pending > 0 && std::chrono::steady_clock::now() < drain_deadline) {
        pollfd poll_descriptor{fd, POLLOUT, 0};
        if (poll(&poll_descriptor, 1, 100) <= 0) continue;
        void* completed_pointer = nullptr;
        if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed_pointer) != 0) continue;
        for (const auto& request : requests) {
            if (request->submitted && request->urb == completed_pointer) {
                request->submitted = false;
                --pending;
                break;
            }
        }
    }

    const double min_hz = static_cast<double>(min_feedback) / 65'536.0 * 8'000.0;
    const double max_hz = static_cast<double>(max_feedback) / 65'536.0 * 8'000.0;
    return "durationMs=" + std::to_string(duration_ms) +
        " sampleRateHz=" + std::to_string(sample_rate_hz) +
        " bytesPerFrame=" + std::to_string(bytes_per_stereo_frame) +
        " dataUrbs=" + std::to_string(data_urbs) +
        " dataPackets=" + std::to_string(data_packets) +
        " writtenBytes=" + std::to_string(written_bytes) +
        " feedbackUrbs=" + std::to_string(feedback_urbs) +
        " sourceBytes=" + std::to_string(source == nullptr ? 0 : source->size()) +
        " sourceBytesQueued=" + std::to_string(source_bytes_queued) +
        " nonZeroBytesQueued=" + std::to_string(non_zero_bytes_queued) +
        " nonZeroBytesCompleted=" + std::to_string(non_zero_bytes_completed) +
        " sourceWraps=" + std::to_string(source_wraps) +
        " minFeedbackHz=" + std::to_string(min_hz) +
        " maxFeedbackHz=" + std::to_string(max_hz) +
        " submitErrors=" + std::to_string(submit_errors) +
        " transportErrors=" + std::to_string(transport_errors) +
        " packetErrors=" + std::to_string(packet_errors) +
        " discarded=" + std::to_string(discarded) +
        " pendingAfterDrain=" + std::to_string(pending) +
        " cancelled=" + std::string(cancelled ? "true" : "false") +
        " generation=" + std::to_string(expected_generation);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_queryInterfaceDriver(
    JNIEnv* env,
    jobject /* this */,
    jint fd,
    jint interface_number) {
    const std::string result = query_driver(fd, interface_number);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_connectKernelDriver(
    JNIEnv* /* env */,
    jobject /* this */,
    jint fd,
    jint interface_number) {
    if (fd < 0 || interface_number < 0) return EINVAL;
    return connect_kernel_driver(fd, interface_number);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_reconnectKernelDrivers(
    JNIEnv* /* env */,
    jobject /* this */,
    jint fd) {
    if (fd < 0) return EINVAL;
    const auto drivers_are_bound = [fd]() {
        return query_driver(fd, 1).find("driver=snd-usb-audio") != std::string::npos &&
            query_driver(fd, 2).find("driver=snd-usb-audio") != std::string::npos;
    };
    if (drivers_are_bound()) return 0;
    // USBDEVFS_CONNECT is an interface ioctl and must be tunneled through USBDEVFS_IOCTL.
    // Calling it directly returns ENOTTY on this Android kernel.
    // snd-usb-audio claims the associated control + streaming interfaces as one device driver;
    // one successful interface connect is therefore terminal. Issuing the second CONNECT while
    // the first driver probe is binding both interfaces created a long blocking race on the phone.
    const int control_error = connect_kernel_driver(fd, 1);
    if (control_error == 0) return 0;
    if (drivers_are_bound()) return 0;
    const int streaming_error = connect_kernel_driver(fd, 2);
    if (streaming_error == 0) return 0;
    if (drivers_are_bound()) return 0;
    return control_error != 0 ? control_error : streaming_error;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_readFeedbackOnce(
    JNIEnv* env,
    jobject /* this */,
    jint fd) {
    const std::string result = read_feedback_once(fd);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_writeSilentPcm16Once(
    JNIEnv* env,
    jobject /* this */,
    jint fd) {
    const std::string result = write_silent_pcm16_once(fd);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_runSilentPcm16Queue(
    JNIEnv* env,
    jobject /* this */,
    jint fd,
    jint duration_ms) {
    const std::string result = run_pcm16_queue(fd, duration_ms, nullptr);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_publishGeneration(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong generation) {
    active_generation.store(static_cast<long long>(generation), std::memory_order_release);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_runSilentPcm16QueueGeneration(
    JNIEnv* env,
    jobject /* this */,
    jint fd,
    jint duration_ms,
    jlong generation) {
    const std::string result = run_pcm16_queue(
        fd,
        duration_ms,
        nullptr,
        44'100,
        4,
        200,
        static_cast<long long>(generation));
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_runPcm16Queue(
    JNIEnv* env,
    jobject /* this */,
    jint fd,
    jint duration_ms,
    jbyteArray pcm) {
    const jsize length = pcm == nullptr ? 0 : env->GetArrayLength(pcm);
    if (length <= 0 || length % 4 != 0) {
        return env->NewStringUTF("invalidSource=true");
    }
    std::vector<unsigned char> source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        pcm,
        0,
        length,
        reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;
    const std::string result = run_pcm16_queue(fd, duration_ms, &source);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_runPcm24Queue(
    JNIEnv* env,
    jobject /* this */,
    jint fd,
    jint duration_ms,
    jbyteArray pcm,
    jint sample_rate_hz) {
    const jsize length = pcm == nullptr ? 0 : env->GetArrayLength(pcm);
    if (length <= 0 || length % 6 != 0 || sample_rate_hz <= 0) {
        return env->NewStringUTF("invalidSource=true");
    }
    std::vector<unsigned char> source(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        pcm,
        0,
        length,
        reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;
    const std::string result = run_pcm16_queue(
        fd,
        duration_ms,
        &source,
        sample_rate_hz,
        6,
        300);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_createMedia3Stream(
    JNIEnv* /* env */,
    jobject /* this */,
    jint fd,
    jint sample_rate_hz,
    jint bytes_per_frame,
    jint max_packet_bytes,
    jlong generation) {
    if (fd < 0 || sample_rate_hz <= 0 || bytes_per_frame <= 0 || max_packet_bytes <= 0 ||
        generation <= 0) {
        return 0;
    }
    try {
        auto* session = new Media3StreamSession(
            fd,
            sample_rate_hz,
            bytes_per_frame,
            max_packet_bytes,
            static_cast<long long>(generation));
        return reinterpret_cast<jlong>(session);
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_writeMedia3Stream(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jobject buffer,
    jint offset,
    jint length) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    auto* address = static_cast<unsigned char*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (session == nullptr || address == nullptr || offset < 0 || length < 0 ||
        capacity < static_cast<jlong>(offset) + length) {
        return 0;
    }
    return session->write(address + offset, length);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_setMedia3StreamPlaying(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle,
    jboolean playing) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    if (session != nullptr) session->set_playing(playing == JNI_TRUE);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3CompletedFrames(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->completed_frames_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3BufferedFrames(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->buffered_frames_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3UnderrunBytes(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->underrun_bytes_value();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3ErrorCode(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? ENODEV : session->error_code_value();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_destroyMedia3Stream(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    delete session;
}
