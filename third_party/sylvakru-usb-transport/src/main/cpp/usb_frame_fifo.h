#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

class UsbFrameFifo {
public:
    void reset(std::size_t capacity_bytes, std::size_t bytes_per_frame) {
        storage_.assign(capacity_bytes, 0);
        bytes_per_frame_ = bytes_per_frame;
        read_offset_ = 0;
        size_ = 0;
    }

    void clear() {
        read_offset_ = 0;
        size_ = 0;
    }

    std::size_t size() const { return size_; }
    std::size_t capacity() const { return storage_.size(); }
    std::size_t available() const { return capacity() - size_; }
    std::size_t bytesPerFrame() const { return bytes_per_frame_; }

    bool write(const uint8_t* source, std::size_t length) {
        if (source == nullptr || length == 0 || length > available()) return false;
        if (bytes_per_frame_ == 0 || length % bytes_per_frame_ != 0) return false;
        const std::size_t write_offset = (read_offset_ + size_) % capacity();
        const std::size_t first = std::min(length, capacity() - write_offset);
        std::copy_n(source, first, storage_.data() + write_offset);
        std::copy_n(source + first, length - first, storage_.data());
        size_ += length;
        return true;
    }

    bool read(uint8_t* destination, std::size_t length) {
        if (destination == nullptr || length == 0 || length > size_) return false;
        if (bytes_per_frame_ == 0 || length % bytes_per_frame_ != 0) return false;
        const std::size_t first = std::min(length, capacity() - read_offset_);
        std::copy_n(storage_.data() + read_offset_, first, destination);
        std::copy_n(storage_.data(), length - first, destination + first);
        read_offset_ = (read_offset_ + length) % capacity();
        size_ -= length;
        return true;
    }

private:
    std::vector<uint8_t> storage_;
    std::size_t bytes_per_frame_ = 0;
    std::size_t read_offset_ = 0;
    std::size_t size_ = 0;
};

