package com.mica.music.media

/**
 * 进程内单例，供 [MicaMediaService] 与 [com.mica.music.data.PlayerController] 共享 ALAC 流式引擎与 MediaSession 桥接。
 */
object AlacPlaybackCoordinator {
    var engine: AlacAudioTrackEngine? = null
    var compositePlayer: MicaCompositePlayer? = null
    var sessionHandler: AlacSessionCommandHandler? = null
}
