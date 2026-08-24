#pragma once

#include <cstdint>
#include <string>

struct LibusbStreamTelemetry {
    long long buffered_packets = 0;
    long long fifo_bytes = 0;
    long long fifo_capacity_bytes = 0;
    long long submitted_bytes = 0;
    long long submitted_transfers = 0;
    long long submitted_packets = 0;
    long long underruns = 0;
    long long active_output_transfers = 0;
};

class LibusbStreamEngine {
public:
    bool open(
        int fd,
        int output_endpoint,
        int output_max_packet_size,
        int feedback_endpoint,
        int feedback_packet_size,
        long long generation,
        std::string* error);
    bool configure(
        int sample_rate,
        int packets_per_second,
        int bytes_per_frame,
        int target_buffer_ms,
        long long generation,
        std::string* error);
    bool activate(long long generation, std::string* error);
    bool enqueue(
        const uint8_t* data,
        int length,
        long long generation,
        long long source_timeline_generation,
        std::string* error);
    long long beginSourceTimeline(long long generation, std::string* error);
    long long consumedSourceFrames(long long generation, long long source_timeline_generation) const;
    int reserveTailPaddingFrames(long long generation, std::string* error);
    bool commitTailPadding(long long generation, std::string* error);
    bool flush(long long next_generation, std::string* error);
    void invalidate(long long generation);
    void close();
    int feedbackFramesPerPacketQ16() const;
    LibusbStreamTelemetry telemetry() const;
    std::string lastError() const;

private:
    struct Impl;
    Impl* impl_ = nullptr;
};
