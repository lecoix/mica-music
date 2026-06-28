package com.mica.music.ui.screens.player

import com.mica.music.data.PlayerCoverFlowMode

internal object ParticleCoverThemePolicy {
    fun isParticleCover(mode: PlayerCoverFlowMode): Boolean =
        mode == PlayerCoverFlowMode.PARTICLE_COVER

    fun particleCoverMode(mode: PlayerCoverFlowMode): Boolean =
        isParticleCover(mode)

    fun coverFlowStageEnabled(mode: PlayerCoverFlowMode): Boolean =
        !isParticleCover(mode) && mode.usesCoverFlowStage

    fun forcesSquareCrop(mode: PlayerCoverFlowMode): Boolean =
        isParticleCover(mode) || mode.forcesSquareCrop
}
