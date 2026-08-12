#pragma once

#include "usb_feedback_decoder.h"

namespace sk02::feedback {

// SK02 UAC2 explicit feedback endpoint 0x84:
// - 4-byte unsigned 16.16 feedback value;
// - value unit is frames per HS microframe (125 us);
// - data endpoint 0x03 has bInterval=1, so one data service interval is one microframe;
// - feedback endpoint has bInterval=4, so it is polled every 8 microframes (1 ms).
constexpr mica::usb::feedback::DecodeNormalizationProfile kProfile{
    {4, 16},
    mica::usb::feedback::RawTimeUnit::FramesPerMicroframe,
    1,
    1,
    {8, 8'000},
    0,
};

}  // namespace sk02::feedback
