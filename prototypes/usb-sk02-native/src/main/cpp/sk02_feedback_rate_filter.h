#pragma once

#include "usb_feedback_rate_filter.h"

// Compatibility name for the frozen SK02 P2 path. The implementation is now device-agnostic so
// P4 host stress and the production worker exercise the same feedback estimator math.
using Sk02FeedbackRateFilter = mica::usb::feedback::MedianSlewRateFilter;
