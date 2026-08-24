package com.mica.music.media

/**
 * Orders a USB mode change so the old playback stack has completely retired before the request
 * epoch is minted. [retire] is intentionally synchronous: cancellation or a later Native stale
 * check is only a second line of defence, not the ordering protocol.
 */
internal object UsbOutputModeSwitchProtocol {
    fun <H, R> retireThenRequest(
        capture: () -> H?,
        retire: () -> Unit,
        request: () -> R,
    ): Pair<H?, R> {
        val handoff = capture()
        retire()
        return handoff to request()
    }
}
