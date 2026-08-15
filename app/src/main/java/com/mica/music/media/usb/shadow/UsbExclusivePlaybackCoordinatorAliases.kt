package com.mica.music.media.usb.shadow

/**
 * M3 production names for the single coordinator/state implementation.
 *
 * These are type aliases, not a second state machine: every stack still owns exactly one
 * [UsbExclusivePlaybackProtocol] instance and every alias resolves to the existing coordinator
 * implementation. The old names remain source-compatible for diagnostic and M2 tests.
 */
internal typealias UsbExclusivePlaybackCoordinator = UsbExclusiveShadowCoordinator
internal typealias UsbExclusivePlaybackStack = UsbExclusiveShadowStack
internal typealias UsbExclusivePlaybackAdapter = UsbExclusiveShadowAdapter
internal typealias UsbExclusivePlaybackAdapterKind = UsbExclusiveShadowAdapterKind
