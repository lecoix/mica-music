// THROWAWAY PROTOTYPE: prove an Android UsbDeviceConnection fd can reach USBFS from JNI and
// sustain an SK02-specific asynchronous isochronous PCM queue. Do not treat this as a reusable
// USB audio engine: device matching, lifecycle, cancellation, and recovery are intentionally narrow.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <climits>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <linux/usbdevice_fs.h>
#include <memory>
#include <mutex>
#include <optional>
#include <poll.h>
#include <string>
#include <stdexcept>
#include <sys/ioctl.h>
#include <thread>
#include <vector>

#include "usb_underrun_accounting.h"
#include "sk02_feedback_profile.h"
#include "sk02_feedback_rate_filter.h"
#include "sk02_iso_ahead_window.h"
#include "sk02_stream_metrics.h"
#include "usb_iso_scheduler.h"

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

    IsoRequest(
        const int packets,
        const int buffer_bytes,
        const unsigned char endpoint_address,
        const bool is_feedback)
        : storage(sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc), 0),
          buffer(buffer_bytes, 0),
          urb(reinterpret_cast<usbdevfs_urb*>(storage.data())),
          feedback(is_feedback) {
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = endpoint_address;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = buffer.data();
        urb->buffer_length = buffer_bytes;
        urb->number_of_packets = packets;
    }
};

struct NativeFeedbackConfig {
    bool enabled = false;
    unsigned char endpoint_address = 0;
    std::uint32_t endpoint_capacity_bytes_per_service_interval = 0;
    mica::usb::feedback::DecodeNormalizationProfile decode_profile{};

    bool valid() const {
        if (!enabled) return endpoint_address == 0;
        return endpoint_address != 0 && (endpoint_address & 0x80U) != 0 &&
            endpoint_capacity_bytes_per_service_interval >= decode_profile.fixed_point.byte_count &&
            decode_profile.valid();
    }
};

struct NativeTransportConfig {
    std::uint64_t nominal_runtime_frame_rate_hz = 0;
    unsigned char data_endpoint_address = 0;
    std::uint32_t bytes_per_runtime_frame = 0;
    std::uint32_t max_bytes_per_data_service_interval = 0;
    mica::usb::iso::ServicePeriod data_service_period{};
    std::uint32_t packets_per_transfer = 0;
    std::uint32_t data_queue_depth = 0;
    NativeFeedbackConfig feedback{};

    bool valid() const {
        return nominal_runtime_frame_rate_hz > 0 && data_endpoint_address != 0 &&
            (data_endpoint_address & 0x80U) == 0 && bytes_per_runtime_frame > 0 &&
            max_bytes_per_data_service_interval >= bytes_per_runtime_frame &&
            data_service_period.valid() && packets_per_transfer > 0 && data_queue_depth > 0 &&
            feedback.valid();
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
        const NativeTransportConfig transport_config,
        const long long generation)
        : fd(descriptor),
          transport(transport_config),
          expected_generation(generation),
          ring(
              static_cast<size_t>(transport_config.nominal_runtime_frame_rate_hz) *
                  static_cast<size_t>(transport_config.bytes_per_runtime_frame) * 2U,
              0) {
        if (!transport.valid()) throw std::invalid_argument("invalid USB transport config");
        worker = std::thread(&Media3StreamSession::run, this);
    }

    ~Media3StreamSession() {
        shutdown();
    }

    Media3StreamSession(const Media3StreamSession&) = delete;
    Media3StreamSession& operator=(const Media3StreamSession&) = delete;

    int write(const unsigned char* source, const int length) {
        if (source == nullptr || length <= 0 || length % transport.bytes_per_runtime_frame != 0) return 0;
        std::lock_guard<std::mutex> guard(mutex);
        if (stop_requested.load(std::memory_order_acquire) || error_code.load() != 0) return 0;
        const size_t buffered_before_write = ring_size + in_flight_source_bytes;
        const size_t writable = ring.size() - ring_size;
        const size_t aligned = std::min(
            static_cast<size_t>(length),
            writable - writable % static_cast<size_t>(transport.bytes_per_runtime_frame));
        for (size_t index = 0; index < aligned; ++index) {
            ring[ring_tail] = source[index];
            ring_tail = (ring_tail + 1U) % ring.size();
        }
        ring_size += aligned;
        if (aligned > 0) {
            const long long written_ns = now_ns();
            const long long previous_ns =
                last_successful_write_ns.exchange(written_ns, std::memory_order_acq_rel);
            if (previous_ns > 0) {
                const long long gap_us = (written_ns - previous_ns) / 1'000;
                previous_successful_write_gap_us.store(gap_us, std::memory_order_release);
                update_max(max_successful_write_gap_us, gap_us);
                if (gap_us >= 200'000) {
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        kLogTag,
                        "[DEBUG-pcm-write-gap-71c2] generation=%lld gapUs=%lld "
                        "bufferedBeforeFrames=%zu bufferedAfterFrames=%zu acceptedBytes=%zu "
                        "minimumBufferedFrames=%zu queuedBytes=%llu completedBytes=%llu",
                        expected_generation,
                        gap_us,
                        buffered_before_write / static_cast<size_t>(transport.bytes_per_runtime_frame),
                        (ring_size + in_flight_source_bytes) /
                            static_cast<size_t>(transport.bytes_per_runtime_frame),
                        aligned,
                        minimum_buffered_source_bytes.load(std::memory_order_acquire) /
                            static_cast<size_t>(transport.bytes_per_runtime_frame),
                        queued_bytes.load(std::memory_order_acquire),
                        completed_source_bytes.load(std::memory_order_acquire));
                }
            }
        }
        queued_bytes.fetch_add(static_cast<unsigned long long>(aligned));
        condition.notify_all();
        return static_cast<int>(aligned);
    }

    void set_playing(const bool value) {
        const bool previous = playing.exchange(value, std::memory_order_acq_rel);
        if (value && !previous) {
            {
                std::lock_guard<std::mutex> guard(mutex);
                minimum_buffered_source_bytes.store(
                    ring_size + in_flight_source_bytes,
                    std::memory_order_release);
            }
            const long long started_ns = now_ns();
            resume_started_ns.store(started_ns, std::memory_order_release);
            underrun_logged_for_resume.store(false, std::memory_order_release);
            last_successful_write_ns.store(0, std::memory_order_release);
            previous_successful_write_gap_us.store(0, std::memory_order_release);
            max_successful_write_gap_us.store(0, std::memory_order_release);
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
            static_cast<unsigned long long>(transport.bytes_per_runtime_frame));
    }

    long long buffered_frames_value() const {
        std::lock_guard<std::mutex> guard(mutex);
        return static_cast<long long>(
            (ring_size + in_flight_source_bytes) / static_cast<size_t>(transport.bytes_per_runtime_frame));
    }

    long long buffer_capacity_frames_value() const {
        return static_cast<long long>(ring.size() / static_cast<size_t>(transport.bytes_per_runtime_frame));
    }

    long long minimum_buffered_frames_value() const {
        const auto minimum = minimum_buffered_source_bytes.load(std::memory_order_acquire);
        return static_cast<long long>(minimum / static_cast<size_t>(transport.bytes_per_runtime_frame));
    }

    long long accepted_pcm_bytes_value() const {
        return static_cast<long long>(queued_bytes.load(std::memory_order_acquire));
    }

    long long previous_successful_write_gap_us_value() const {
        return previous_successful_write_gap_us.load(std::memory_order_acquire);
    }

    long long maximum_successful_write_gap_us_value() const {
        return max_successful_write_gap_us.load(std::memory_order_acquire);
    }

    long long previous_data_completion_gap_us_value() const {
        return previous_data_completion_gap_us.load(std::memory_order_acquire);
    }

    long long maximum_data_completion_gap_us_value() const {
        return max_data_completion_gap_us.load(std::memory_order_acquire);
    }

    long long previous_feedback_completion_gap_us_value() const {
        return previous_feedback_completion_gap_us.load(std::memory_order_acquire);
    }

    long long maximum_feedback_completion_gap_us_value() const {
        return max_feedback_completion_gap_us.load(std::memory_order_acquire);
    }

    long long total_poll_timeouts_value() const {
        return total_poll_timeouts.load(std::memory_order_acquire);
    }

    long long maximum_consecutive_poll_timeouts_value() const {
        return max_consecutive_poll_timeouts.load(std::memory_order_acquire);
    }

    long long invalid_feedback_packet_count_value() const {
        return invalid_feedback_packet_count.load(std::memory_order_acquire);
    }

    long long data_packet_error_count_value() const {
        return data_packet_error_count.load(std::memory_order_acquire);
    }

    long long current_feedback_q16_value() const {
        return static_cast<long long>(current_feedback_q16.load(std::memory_order_acquire));
    }

    long long minimum_feedback_q16_value() const {
        return static_cast<long long>(minimum_feedback_q16.load(std::memory_order_acquire));
    }

    long long maximum_feedback_q16_value() const {
        return static_cast<long long>(maximum_feedback_q16.load(std::memory_order_acquire));
    }

    long long maximum_feedback_step_q16_value() const {
        return static_cast<long long>(maximum_feedback_step_q16.load(std::memory_order_acquire));
    }

    long long trusted_feedback_q16_value() const {
        return static_cast<long long>(trusted_feedback_q16.load(std::memory_order_acquire));
    }

    long long feedback_filter_intervention_count_value() const {
        return static_cast<long long>(
            feedback_filter_intervention_count.load(std::memory_order_acquire));
    }

    std::array<long long, 17> diagnostic_metrics_value() const {
        return {
            static_cast<long long>(scheduled_packet_count.load(std::memory_order_acquire)),
            static_cast<long long>(scheduled_frame_count.load(std::memory_order_acquire)),
            static_cast<long long>(out_of_nominal_request_count.load(std::memory_order_acquire)),
            static_cast<long long>(
                max_consecutive_out_of_nominal_requests.load(std::memory_order_acquire)),
            static_cast<long long>(minimum_frames_per_packet.load(std::memory_order_acquire)),
            static_cast<long long>(maximum_frames_per_packet.load(std::memory_order_acquire)),
            static_cast<long long>(maximum_packet_frame_step.load(std::memory_order_acquire)),
            schedule_deviation_frames.load(std::memory_order_acquire),
            static_cast<long long>(observed_pcm_frames.load(std::memory_order_acquire)),
            static_cast<long long>(zero_pcm_frame_count.load(std::memory_order_acquire)),
            static_cast<long long>(max_consecutive_zero_pcm_frames.load(std::memory_order_acquire)),
            static_cast<long long>(repeated_pcm_frame_count.load(std::memory_order_acquire)),
            static_cast<long long>(
                max_consecutive_repeated_pcm_frames.load(std::memory_order_acquire)),
            static_cast<long long>(duplicate_pcm_request_count.load(std::memory_order_acquire)),
            static_cast<long long>(
                max_consecutive_duplicate_pcm_requests.load(std::memory_order_acquire)),
            static_cast<long long>(max_adjacent_sample_delta.load(std::memory_order_acquire)),
            static_cast<long long>(max_request_boundary_sample_delta.load(std::memory_order_acquire)),
        };
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

    static void update_max(std::atomic<long long>& maximum, const long long candidate) {
        long long observed = maximum.load(std::memory_order_acquire);
        while (candidate > observed &&
               !maximum.compare_exchange_weak(
                   observed,
                   candidate,
                   std::memory_order_acq_rel,
                   std::memory_order_acquire)) {
        }
    }

    static void update_max(
        std::atomic<unsigned long>& maximum,
        const unsigned long candidate) {
        unsigned long observed = maximum.load(std::memory_order_acquire);
        while (candidate > observed &&
               !maximum.compare_exchange_weak(
                   observed,
                   candidate,
                   std::memory_order_acq_rel,
                   std::memory_order_acquire)) {
        }
    }

    static void update_min(
        std::atomic<unsigned long>& minimum,
        const unsigned long candidate) {
        unsigned long observed = minimum.load(std::memory_order_acquire);
        while (candidate < observed &&
               !minimum.compare_exchange_weak(
                   observed,
                   candidate,
                   std::memory_order_acq_rel,
                   std::memory_order_acquire)) {
        }
    }

    void update_minimum_buffered_source_bytes_locked() {
        if (!playing.load(std::memory_order_acquire)) return;
        const size_t candidate = ring_size + in_flight_source_bytes;
        size_t observed = minimum_buffered_source_bytes.load(std::memory_order_acquire);
        while (candidate < observed &&
               !minimum_buffered_source_bytes.compare_exchange_weak(
                   observed,
                   candidate,
                   std::memory_order_acq_rel,
                   std::memory_order_acquire)) {
        }
    }

    bool is_current() const {
        return active_generation.load(std::memory_order_acquire) == expected_generation;
    }

    SourceTakeResult take_source(unsigned char* target, const size_t requested) {
        std::lock_guard<std::mutex> guard(mutex);
        const bool playing_when_taken = playing.load(std::memory_order_acquire);
        if (!playing_when_taken) return {0, false};
        const size_t available = std::min(requested, ring_size);
        const size_t aligned = available - available % static_cast<size_t>(transport.bytes_per_runtime_frame);
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
        update_minimum_buffered_source_bytes_locked();
    }

    void abandon_source(const size_t bytes) {
        std::lock_guard<std::mutex> guard(mutex);
        in_flight_source_bytes -= std::min(bytes, in_flight_source_bytes);
        update_minimum_buffered_source_bytes_locked();
    }

    void run() {
        constexpr int kFeedbackQueueDepth = 4;
        const int data_queue_depth = static_cast<int>(transport.data_queue_depth);
        const int data_packets = static_cast<int>(transport.packets_per_transfer);
        const bool has_feedback = transport.feedback.enabled;
        const auto data_request_bytes64 =
            static_cast<std::uint64_t>(transport.packets_per_transfer) *
            transport.max_bytes_per_data_service_interval;
        if (data_request_bytes64 == 0 || data_request_bytes64 > static_cast<std::uint64_t>(INT_MAX)) {
            error_code.store(EOVERFLOW, std::memory_order_release);
            return;
        }
        const int data_request_bytes = static_cast<int>(data_request_bytes64);

        std::vector<std::unique_ptr<IsoRequest>> requests;
        requests.reserve(data_queue_depth + (has_feedback ? kFeedbackQueueDepth : 0));
        for (int index = 0; index < data_queue_depth; ++index) {
            requests.push_back(std::make_unique<IsoRequest>(
                data_packets,
                data_request_bytes,
                transport.data_endpoint_address,
                false));
        }
        if (has_feedback) {
            const int feedback_capacity = static_cast<int>(
                transport.feedback.endpoint_capacity_bytes_per_service_interval);
            for (int index = 0; index < kFeedbackQueueDepth; ++index) {
                auto request = std::make_unique<IsoRequest>(
                    1,
                    feedback_capacity,
                    transport.feedback.endpoint_address,
                    true);
                request->urb->iso_frame_desc[0].length = feedback_capacity;
                requests.push_back(std::move(request));
            }
        }

        {
            std::unique_lock<std::mutex> guard(mutex);
            const size_t prebuffer = static_cast<size_t>(transport.nominal_runtime_frame_rate_hz) *
                static_cast<size_t>(transport.bytes_per_runtime_frame) / 20U;
            condition.wait_for(guard, std::chrono::seconds(2), [&]() {
                return stop_requested.load(std::memory_order_acquire) ||
                    !is_current() || ring_size >= prebuffer;
            });
        }
        if (stop_requested.load(std::memory_order_acquire) || !is_current()) return;

        unsigned long initial_feedback = 0;
        if (has_feedback) {
            // Bootstrap only: explicit device feedback becomes authoritative as soon as the first
            // valid packet arrives. A floored Q16 seed is therefore allowed here, but it is never
            // used as the scheduling authority for no-feedback modes (which use the exact rational
            // scheduler below). This preserves 44.1 kHz devices whose nominal frames/interval is
            // not exactly representable in Q16.
            std::uint64_t nominal_rate_numerator = 0;
            std::uint64_t bootstrap_q16_numerator = 0;
            if (!mica::usb::iso::checked_multiply_u64(
                    transport.nominal_runtime_frame_rate_hz,
                    transport.data_service_period.numerator,
                    &nominal_rate_numerator) ||
                !mica::usb::iso::checked_multiply_u64(
                    nominal_rate_numerator,
                    65'536ULL,
                    &bootstrap_q16_numerator) ||
                transport.data_service_period.denominator == 0) {
                error_code.store(EOVERFLOW, std::memory_order_release);
                return;
            }
            const auto bootstrap_q16 =
                bootstrap_q16_numerator / transport.data_service_period.denominator;
            if (bootstrap_q16 == 0 ||
                bootstrap_q16 > std::numeric_limits<unsigned long>::max()) {
                error_code.store(EINVAL, std::memory_order_release);
                return;
            }
            initial_feedback = static_cast<unsigned long>(bootstrap_q16);
        }
        unsigned long feedback = initial_feedback;
        unsigned long previous_raw_feedback = initial_feedback;
        std::optional<Sk02FeedbackRateFilter> feedback_filter;
        std::optional<mica::usb::iso::PacketScheduler> feedback_scheduler;
        if (has_feedback) {
            if (transport.data_service_period.numerator == 0 ||
                transport.data_service_period.denominator % transport.data_service_period.numerator != 0) {
                error_code.store(EINVAL, std::memory_order_release);
                return;
            }
            const auto intervals_per_second =
                transport.data_service_period.denominator / transport.data_service_period.numerator;
            if (intervals_per_second == 0 ||
                intervals_per_second > std::numeric_limits<std::uint32_t>::max()) {
                error_code.store(EOVERFLOW, std::memory_order_release);
                return;
            }
            feedback_filter.emplace(initial_feedback);
            feedback_scheduler.emplace(
                mica::usb::iso::SchedulerConfig{
                    static_cast<std::uint32_t>(intervals_per_second),
                    transport.bytes_per_runtime_frame,
                    transport.max_bytes_per_data_service_interval,
                });
        }
        mica::usb::iso::ExactNominalPacketScheduler nominal_scheduler(
            mica::usb::iso::ExactNominalSchedulerConfig{
                transport.data_service_period,
                transport.bytes_per_runtime_frame,
                transport.max_bytes_per_data_service_interval,
            },
            transport.nominal_runtime_frame_rate_hz);
        if (!has_feedback && !nominal_scheduler.valid()) {
            error_code.store(EINVAL, std::memory_order_release);
            return;
        }
        current_feedback_q16.store(initial_feedback, std::memory_order_release);
        minimum_feedback_q16.store(initial_feedback, std::memory_order_release);
        maximum_feedback_q16.store(initial_feedback, std::memory_order_release);
        trusted_feedback_q16.store(initial_feedback, std::memory_order_release);
        long long last_data_completion_ns = 0;
        long long previous_data_completion_gap_us = 0;
        long long max_data_completion_gap_us = 0;
        long long last_feedback_completion_ns = 0;
        long long previous_feedback_completion_gap_us = 0;
        long long max_feedback_completion_gap_us = 0;
        unsigned long long consecutive_poll_timeouts = 0;
        Sk02PacketScheduleMetrics schedule_metrics;
        Sk02PcmContinuityMetrics pcm_metrics;
        const auto fill_data = [&](IsoRequest& request) {
            int total = 0;
            std::vector<std::uint32_t> scheduled_frames(static_cast<size_t>(data_packets), 0);
            for (int packet = 0; packet < data_packets; ++packet) {
                unsigned int bytes = 0;
                std::uint32_t frames = 0;
                if (has_feedback) {
                    const auto schedule = feedback_scheduler->next(feedback);
                    if (!schedule.valid || schedule.capacity_limited) {
                        error_code.store(EOVERFLOW, std::memory_order_release);
                        return false;
                    }
                    bytes = schedule.scheduled_bytes;
                    frames = schedule.scheduled_frames;
                } else {
                    const auto schedule = nominal_scheduler.next();
                    if (!schedule.valid || schedule.capacity_limited) {
                        error_code.store(EOVERFLOW, std::memory_order_release);
                        return false;
                    }
                    bytes = schedule.scheduled_bytes;
                    frames = schedule.scheduled_runtime_frames;
                }
                request.urb->iso_frame_desc[packet].length = bytes;
                request.urb->iso_frame_desc[packet].actual_length = 0;
                request.urb->iso_frame_desc[packet].status = 0;
                total += static_cast<int>(bytes);
                scheduled_frames[packet] = frames;
            }
            request.urb->buffer_length = total;
            request.urb->status = 0;
            request.urb->error_count = 0;
            const SourceTakeResult take =
                take_source(request.buffer.data(), static_cast<size_t>(total));
            request.source_bytes = take.bytes;
            if (take.playing_when_taken) {
                schedule_metrics.observe_request(
                    scheduled_frames.data(),
                    scheduled_frames.size(),
                    static_cast<std::uint32_t>(transport.nominal_runtime_frame_rate_hz));
                scheduled_packet_count.store(
                    schedule_metrics.total_packets,
                    std::memory_order_release);
                scheduled_frame_count.store(
                    schedule_metrics.total_frames,
                    std::memory_order_release);
                out_of_nominal_request_count.store(
                    schedule_metrics.out_of_nominal_request_count,
                    std::memory_order_release);
                max_consecutive_out_of_nominal_requests.store(
                    schedule_metrics.maximum_consecutive_out_of_nominal_requests,
                    std::memory_order_release);
                minimum_frames_per_packet.store(
                    schedule_metrics.published_minimum_frames_per_packet(),
                    std::memory_order_release);
                maximum_frames_per_packet.store(
                    schedule_metrics.maximum_frames_per_packet,
                    std::memory_order_release);
                maximum_packet_frame_step.store(
                    schedule_metrics.maximum_packet_frame_step,
                    std::memory_order_release);
                schedule_deviation_frames.store(
                    schedule_metrics.schedule_deviation_frames,
                    std::memory_order_release);
            }
            if (request.source_bytes > 0) {
                pcm_metrics.observe_request(
                    request.buffer.data(),
                    request.source_bytes,
                    transport.bytes_per_runtime_frame);
                observed_pcm_frames.store(pcm_metrics.observed_frames, std::memory_order_release);
                zero_pcm_frame_count.store(pcm_metrics.zero_frame_count, std::memory_order_release);
                max_consecutive_zero_pcm_frames.store(
                    pcm_metrics.maximum_consecutive_zero_frames,
                    std::memory_order_release);
                repeated_pcm_frame_count.store(
                    pcm_metrics.repeated_frame_count,
                    std::memory_order_release);
                max_consecutive_repeated_pcm_frames.store(
                    pcm_metrics.maximum_consecutive_repeated_frames,
                    std::memory_order_release);
                duplicate_pcm_request_count.store(
                    pcm_metrics.duplicate_request_count,
                    std::memory_order_release);
                max_consecutive_duplicate_pcm_requests.store(
                    pcm_metrics.maximum_consecutive_duplicate_requests,
                    std::memory_order_release);
                max_adjacent_sample_delta.store(
                    pcm_metrics.maximum_adjacent_sample_delta,
                    std::memory_order_release);
                max_request_boundary_sample_delta.store(
                    pcm_metrics.maximum_request_boundary_sample_delta,
                    std::memory_order_release);
            }
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
                        const long long observed_ns = now_ns();
                        const long long elapsed_us =
                            started == 0 ? -1 : (observed_ns - started) / 1'000;
                        const long long last_write_ns =
                            last_successful_write_ns.load(std::memory_order_acquire);
                        const long long since_last_write_us =
                            last_write_ns == 0 ? -1 : (observed_ns - last_write_ns) / 1'000;
                        __android_log_print(
                            ANDROID_LOG_ERROR,
                            kLogTag,
                            "[DEBUG-underrun-cause-a81f] elapsedUs=%lld requestedBytes=%d sourceBytes=%zu missingBytes=%llu queuedBytes=%llu completedBytes=%llu sinceLastWriteUs=%lld previousWriteGapUs=%lld maxWriteGapUs=%lld previousDataCompletionGapUs=%lld maxDataCompletionGapUs=%lld previousFeedbackCompletionGapUs=%lld maxFeedbackCompletionGapUs=%lld consecutivePollTimeouts=%llu feedback=%lu",
                            elapsed_us,
                            total,
                            request.source_bytes,
                            missing,
                            queued_bytes.load(std::memory_order_acquire),
                            completed_source_bytes.load(std::memory_order_acquire),
                            since_last_write_us,
                            previous_successful_write_gap_us.load(std::memory_order_acquire),
                            max_successful_write_gap_us.load(std::memory_order_acquire),
                            previous_data_completion_gap_us,
                            max_data_completion_gap_us,
                            previous_feedback_completion_gap_us,
                            max_feedback_completion_gap_us,
                            consecutive_poll_timeouts,
                            feedback);
                    }
                }
            }
            return true;
        };
        const auto submit = [&](IsoRequest& request) {
            if (!is_current() || stop_requested.load(std::memory_order_acquire)) return false;
            if (!request.feedback && !fill_data(request)) return false;
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
            if (poll_result == 0) {
                ++consecutive_poll_timeouts;
                total_poll_timeouts.fetch_add(1, std::memory_order_acq_rel);
                update_max(
                    max_consecutive_poll_timeouts,
                    static_cast<long long>(consecutive_poll_timeouts));
                continue;
            }
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
            consecutive_poll_timeouts = 0;
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
            const long long completed_ns = now_ns();
            if (completed->feedback) {
                if (last_feedback_completion_ns > 0) {
                    previous_feedback_completion_gap_us =
                        (completed_ns - last_feedback_completion_ns) / 1'000;
                    max_feedback_completion_gap_us = std::max(
                        max_feedback_completion_gap_us,
                        previous_feedback_completion_gap_us);
                    this->previous_feedback_completion_gap_us.store(
                        previous_feedback_completion_gap_us,
                        std::memory_order_release);
                    update_max(
                        this->max_feedback_completion_gap_us,
                        max_feedback_completion_gap_us);
                }
                last_feedback_completion_ns = completed_ns;
                const auto& packet = completed->urb->iso_frame_desc[0];
                const auto normalized = packet.status == 0 ?
                    mica::usb::feedback::decode_and_normalize_unsigned_le(
                        completed->buffer.data(),
                        static_cast<std::size_t>(packet.actual_length),
                        transport.feedback.decode_profile) :
                    mica::usb::feedback::NormalizedFeedbackRate{};
                const auto normalized_q16 =
                    mica::usb::feedback::to_fixed_point_exact(normalized, 16);
                if (normalized_q16.valid && normalized_q16.value > 0 &&
                    normalized_q16.value <= std::numeric_limits<unsigned long>::max()) {
                    const auto value = static_cast<unsigned long>(normalized_q16.value);
                    current_feedback_q16.store(value, std::memory_order_release);
                    update_min(minimum_feedback_q16, value);
                    update_max(maximum_feedback_q16, value);
                    const unsigned long step = value >= previous_raw_feedback ?
                        value - previous_raw_feedback : previous_raw_feedback - value;
                    previous_raw_feedback = value;
                    update_max(maximum_feedback_step_q16, step);
                    const unsigned long trusted = feedback_filter->ingest(value);
                    if (trusted != value) {
                        feedback_filter_intervention_count.fetch_add(
                            1,
                            std::memory_order_acq_rel);
                    }
                    // The estimator is counterfactual diagnostics only. Raw normalized device
                    // feedback remains authoritative for the explicit-feedback scheduler.
                    feedback = value;
                    trusted_feedback_q16.store(trusted, std::memory_order_release);
                } else {
                    invalid_feedback_packet_count.fetch_add(1, std::memory_order_acq_rel);
                }
            } else {
                if (last_data_completion_ns > 0) {
                    previous_data_completion_gap_us =
                        (completed_ns - last_data_completion_ns) / 1'000;
                    max_data_completion_gap_us = std::max(
                        max_data_completion_gap_us,
                        previous_data_completion_gap_us);
                    this->previous_data_completion_gap_us.store(
                        previous_data_completion_gap_us,
                        std::memory_order_release);
                    update_max(this->max_data_completion_gap_us, max_data_completion_gap_us);
                }
                last_data_completion_ns = completed_ns;
                for (int packet = 0; packet < data_packets; ++packet) {
                    if (completed->urb->iso_frame_desc[packet].status != 0) {
                        data_packet_error_count.fetch_add(1, std::memory_order_acq_rel);
                    }
                }
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
    const NativeTransportConfig transport;
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
    std::atomic<size_t> minimum_buffered_source_bytes{0};
    std::atomic<long long> resume_started_ns{0};
    std::atomic<long long> last_successful_write_ns{0};
    std::atomic<long long> previous_successful_write_gap_us{0};
    std::atomic<long long> max_successful_write_gap_us{0};
    std::atomic<long long> previous_data_completion_gap_us{0};
    std::atomic<long long> max_data_completion_gap_us{0};
    std::atomic<long long> previous_feedback_completion_gap_us{0};
    std::atomic<long long> max_feedback_completion_gap_us{0};
    std::atomic<long long> total_poll_timeouts{0};
    std::atomic<long long> max_consecutive_poll_timeouts{0};
    std::atomic<long long> invalid_feedback_packet_count{0};
    std::atomic<long long> data_packet_error_count{0};
    std::atomic<unsigned long> current_feedback_q16{0};
    std::atomic<unsigned long> minimum_feedback_q16{0};
    std::atomic<unsigned long> maximum_feedback_q16{0};
    std::atomic<unsigned long> maximum_feedback_step_q16{0};
    std::atomic<unsigned long> trusted_feedback_q16{0};
    std::atomic<unsigned long long> feedback_filter_intervention_count{0};
    std::atomic<unsigned long long> scheduled_packet_count{0};
    std::atomic<unsigned long long> scheduled_frame_count{0};
    std::atomic<unsigned long long> out_of_nominal_request_count{0};
    std::atomic<unsigned long long> max_consecutive_out_of_nominal_requests{0};
    std::atomic<unsigned long> minimum_frames_per_packet{0};
    std::atomic<unsigned long> maximum_frames_per_packet{0};
    std::atomic<unsigned long> maximum_packet_frame_step{0};
    std::atomic<long long> schedule_deviation_frames{0};
    std::atomic<unsigned long long> observed_pcm_frames{0};
    std::atomic<unsigned long long> zero_pcm_frame_count{0};
    std::atomic<unsigned long long> max_consecutive_zero_pcm_frames{0};
    std::atomic<unsigned long long> repeated_pcm_frame_count{0};
    std::atomic<unsigned long long> max_consecutive_repeated_pcm_frames{0};
    std::atomic<unsigned long long> duplicate_pcm_request_count{0};
    std::atomic<unsigned long long> max_consecutive_duplicate_pcm_requests{0};
    std::atomic<unsigned long long> max_adjacent_sample_delta{0};
    std::atomic<unsigned long long> max_request_boundary_sample_delta{0};
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
            0x03,
            false));
    }
    for (int index = 0; index < kFeedbackQueueDepth; ++index) {
        auto request = std::make_unique<IsoRequest>(1, 4, 0x84, true);
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
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_createMedia3StreamNative(
    JNIEnv* /* env */,
    jobject /* this */,
    jint fd,
    jlong nominal_runtime_frame_rate_hz,
    jint data_endpoint_address,
    jint bytes_per_runtime_frame,
    jint data_max_bytes_per_service_interval,
    jlong data_service_period_numerator,
    jlong data_service_period_denominator,
    jint packets_per_transfer,
    jint data_queue_depth,
    jint feedback_endpoint_address,
    jint feedback_endpoint_capacity_bytes_per_service_interval,
    jint feedback_expected_payload_bytes,
    jint feedback_fractional_bits,
    jint feedback_raw_time_unit,
    jlong feedback_raw_to_data_scale_numerator,
    jlong feedback_raw_to_data_scale_denominator,
    jlong feedback_poll_period_numerator,
    jlong feedback_poll_period_denominator,
    jlong feedback_required_zero_mask,
    jlong generation) {
    if (fd < 0 || nominal_runtime_frame_rate_hz <= 0 ||
        data_endpoint_address <= 0 || data_endpoint_address > 0xff ||
        bytes_per_runtime_frame <= 0 || data_max_bytes_per_service_interval <= 0 ||
        data_service_period_numerator <= 0 || data_service_period_denominator <= 0 ||
        packets_per_transfer <= 0 || data_queue_depth <= 0 || generation <= 0) {
        return 0;
    }

    NativeTransportConfig config{};
    config.nominal_runtime_frame_rate_hz =
        static_cast<std::uint64_t>(nominal_runtime_frame_rate_hz);
    config.data_endpoint_address = static_cast<unsigned char>(data_endpoint_address);
    config.bytes_per_runtime_frame = static_cast<std::uint32_t>(bytes_per_runtime_frame);
    config.max_bytes_per_data_service_interval =
        static_cast<std::uint32_t>(data_max_bytes_per_service_interval);
    config.data_service_period = {
        static_cast<std::uint64_t>(data_service_period_numerator),
        static_cast<std::uint64_t>(data_service_period_denominator),
    };
    config.packets_per_transfer = static_cast<std::uint32_t>(packets_per_transfer);
    config.data_queue_depth = static_cast<std::uint32_t>(data_queue_depth);

    if (feedback_endpoint_address == 0) {
        if (feedback_endpoint_capacity_bytes_per_service_interval != 0 ||
            feedback_expected_payload_bytes != 0 || feedback_fractional_bits != 0 ||
            feedback_raw_time_unit != -1 || feedback_raw_to_data_scale_numerator != 0 ||
            feedback_raw_to_data_scale_denominator != 0 || feedback_poll_period_numerator != 0 ||
            feedback_poll_period_denominator != 0 || feedback_required_zero_mask != 0) {
            return 0;
        }
    } else {
        if (feedback_endpoint_address < 0 || feedback_endpoint_address > 0xff ||
            feedback_endpoint_capacity_bytes_per_service_interval <= 0 ||
            feedback_expected_payload_bytes <= 0 || feedback_expected_payload_bytes > 8 ||
            feedback_fractional_bits <= 0 || feedback_fractional_bits >= 64 ||
            (feedback_raw_time_unit != 0 && feedback_raw_time_unit != 1) ||
            feedback_raw_to_data_scale_numerator <= 0 ||
            feedback_raw_to_data_scale_denominator <= 0 ||
            feedback_poll_period_numerator <= 0 || feedback_poll_period_denominator <= 0 ||
            feedback_required_zero_mask < 0) {
            return 0;
        }
        config.feedback.enabled = true;
        config.feedback.endpoint_address = static_cast<unsigned char>(feedback_endpoint_address);
        config.feedback.endpoint_capacity_bytes_per_service_interval =
            static_cast<std::uint32_t>(feedback_endpoint_capacity_bytes_per_service_interval);
        config.feedback.decode_profile.fixed_point = {
            static_cast<std::uint8_t>(feedback_expected_payload_bytes),
            static_cast<std::uint8_t>(feedback_fractional_bits),
        };
        config.feedback.decode_profile.raw_time_unit = feedback_raw_time_unit == 0 ?
            mica::usb::feedback::RawTimeUnit::FramesPerBusFrame :
            mica::usb::feedback::RawTimeUnit::FramesPerMicroframe;
        config.feedback.decode_profile.raw_to_data_interval_numerator =
            static_cast<std::uint64_t>(feedback_raw_to_data_scale_numerator);
        config.feedback.decode_profile.raw_to_data_interval_denominator =
            static_cast<std::uint64_t>(feedback_raw_to_data_scale_denominator);
        config.feedback.decode_profile.feedback_poll_period = {
            static_cast<std::uint64_t>(feedback_poll_period_numerator),
            static_cast<std::uint64_t>(feedback_poll_period_denominator),
        };
        config.feedback.decode_profile.required_zero_mask =
            static_cast<std::uint64_t>(feedback_required_zero_mask);
    }

    if (!config.valid()) return 0;
    try {
        auto* session = new Media3StreamSession(
            fd,
            config,
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
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3BufferCapacityFrames(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->buffer_capacity_frames_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3MinimumBufferedFrames(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->minimum_buffered_frames_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3AcceptedPcmBytes(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->accepted_pcm_bytes_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3PreviousSuccessfulWriteGapUs(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->previous_successful_write_gap_us_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3MaximumSuccessfulWriteGapUs(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->maximum_successful_write_gap_us_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3PreviousDataCompletionGapUs(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->previous_data_completion_gap_us_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3MaximumDataCompletionGapUs(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->maximum_data_completion_gap_us_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3PreviousFeedbackCompletionGapUs(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->previous_feedback_completion_gap_us_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3MaximumFeedbackCompletionGapUs(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->maximum_feedback_completion_gap_us_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3TotalPollTimeouts(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->total_poll_timeouts_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3MaximumConsecutivePollTimeouts(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->maximum_consecutive_poll_timeouts_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3InvalidFeedbackPacketCount(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->invalid_feedback_packet_count_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3DataPacketErrorCount(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->data_packet_error_count_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3CurrentFeedbackQ16(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->current_feedback_q16_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3MinimumFeedbackQ16(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->minimum_feedback_q16_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3MaximumFeedbackQ16(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->maximum_feedback_q16_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3MaximumFeedbackStepQ16(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->maximum_feedback_step_q16_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3TrustedFeedbackQ16(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->trusted_feedback_q16_value();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3FeedbackFilterInterventionCount(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    return session == nullptr ? 0 : session->feedback_filter_intervention_count_value();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_mica_music_media_usbprototype_UsbSk02NativePrototype_getMedia3DiagnosticMetrics(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    auto* session = reinterpret_cast<Media3StreamSession*>(handle);
    if (session == nullptr) return env->NewLongArray(0);
    const auto metrics = session->diagnostic_metrics_value();
    jlongArray result = env->NewLongArray(static_cast<jsize>(metrics.size()));
    if (result == nullptr) return nullptr;
    env->SetLongArrayRegion(
        result,
        0,
        static_cast<jsize>(metrics.size()),
        reinterpret_cast<const jlong*>(metrics.data()));
    return result;
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
