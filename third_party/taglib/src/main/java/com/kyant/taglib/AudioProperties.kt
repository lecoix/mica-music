package com.kyant.taglib

/**
 * AudioProperties contains information about the audio properties of a file.
 *
 * @property length Length in milliseconds
 * @property bitrate Bitrate in kbps
 * @property sampleRate Sample rate in Hz
 * @property channels Number of channels
 * @property bitsPerSample Bits per sample; 0 when unknown or not applicable
 */
public data class AudioProperties(
    val length: Int,
    val bitrate: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
)
