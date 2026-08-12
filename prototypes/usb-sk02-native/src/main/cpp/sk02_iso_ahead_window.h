#pragma once

#include "usb_iso_ahead_window.h"

namespace sk02::iso {

constexpr std::uint32_t kUsbIntervalsPerSecond = 8'000;
constexpr std::uint32_t kDataPacketsPerTransfer = 8;

// Current production policy. Kept here so the worker and the regression test share one seam.
constexpr std::uint32_t kDataQueueDepth = 16;

using mica::usb::iso::ahead_window_us;
using mica::usb::iso::minimum_queue_depth_for_gap;

}  // namespace sk02::iso
