/*
 * Derived in whole or in part from the SylvaKru USB-exclusive implementation
 * (https://github.com/huya688zdx/sylvakru), Apache License 2.0.
 * Modified/adapted for Mica; see third_party/sylvakru-usb-transport/NOTICE.
 */
package com.afalphy.sylvakru

internal fun chooseBitPerfectMixerSampleRate(
    requestedSampleRate: Int?,
    supportedSampleRates: List<Int>,
): Int? {
    val validRates = supportedSampleRates.filter { it > 0 }
    return if (requestedSampleRate != null) {
        validRates.firstOrNull { it == requestedSampleRate }
    } else {
        validRates.maxOrNull()
    }
}
