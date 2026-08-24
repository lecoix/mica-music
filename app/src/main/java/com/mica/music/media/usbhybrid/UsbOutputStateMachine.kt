package com.mica.music.media.usbhybrid

internal enum class DesiredUsbOutput { Shared, ExactPcm, Dop, NativeDsd }

internal data class FrozenPlaybackIntent(val generation: Long, val playWhenReady: Boolean)

internal sealed interface UsbOutputPhase {
    data object SharedActive : UsbOutputPhase
    data object SharedReconnectRequired : UsbOutputPhase
    data object SharedRouteWaiting : UsbOutputPhase
    data object SharedQuiescing : UsbOutputPhase
    data object ExclusivePreparing : UsbOutputPhase
    data object PermissionWaiting : UsbOutputPhase
    data object ExclusiveOpening : UsbOutputPhase
    data class ExclusiveActive(val mode: DesiredUsbOutput) : UsbOutputPhase
    data object Disconnected : UsbOutputPhase
    data class Failed(val code: String, val message: String) : UsbOutputPhase
}

internal data class UsbOutputState(
    val desiredMode: DesiredUsbOutput = DesiredUsbOutput.Shared,
    val phase: UsbOutputPhase = UsbOutputPhase.SharedActive,
    val generation: Long = 0L,
    val frozenIntent: FrozenPlaybackIntent? = null,
    val targetStable: Boolean = false,
    val permissionGranted: Boolean = false,
    val sharedReturnRequiresReconnect: Boolean = false,
    val openAttempt: Int = 0,
    val activeTransport: UsbActiveTransport? = null,
    val activeSessionId: Long? = null,
)

internal sealed interface UsbOutputEvent {
    data class UserSelected(
        val mode: DesiredUsbOutput,
        val playWhenReady: Boolean,
        val sharedReturnRequiresReconnect: Boolean = false,
    ) : UsbOutputEvent
    data class UserPlayIntentChanged(val playWhenReady: Boolean) : UsbOutputEvent
    data object PlayerRetired : UsbOutputEvent
    data class SharedQuiesced(val generation: Long) : UsbOutputEvent
    data object UsbAttached : UsbOutputEvent
    data class UsbDetached(val playWhenReady: Boolean) : UsbOutputEvent
    data object TargetStable : UsbOutputEvent
    data object TargetLost : UsbOutputEvent
    data class PreparationFailed(val generation: Long, val code: String, val message: String) : UsbOutputEvent
    data class PermissionResult(val generation: Long, val granted: Boolean) : UsbOutputEvent
    data class ExclusiveOpenSucceeded(
        val generation: Long,
        val transport: UsbActiveTransport,
        val sessionId: Long? = null,
    ) : UsbOutputEvent
    data class ExclusiveOpenFailed(
        val generation: Long,
        val code: String,
        val message: String,
        val transientOwnershipRace: Boolean = false,
    ) : UsbOutputEvent
    data object AndroidSharedRouteReady : UsbOutputEvent
    data object AndroidSharedRouteUnavailable : UsbOutputEvent
}

internal sealed interface UsbOutputEffect {
    data object RetirePlayer : UsbOutputEffect
    data object WaitForSharedQuiescence : UsbOutputEffect
    data object WaitForTarget : UsbOutputEffect
    data object RequestPermission : UsbOutputEffect
    data object OpenExclusive : UsbOutputEffect
    data object RetargetExclusive : UsbOutputEffect
    data object CloseExclusive : UsbOutputEffect
    data object RestoreFrozenPlaybackIntent : UsbOutputEffect
    data object WaitForSharedRoute : UsbOutputEffect
    data object BuildSharedPlayer : UsbOutputEffect
    data object ShowReconnectRequired : UsbOutputEffect
    data class ShowError(val code: String, val message: String) : UsbOutputEffect
}

internal data class UsbOutputReduction(val state: UsbOutputState, val effects: List<UsbOutputEffect> = emptyList())

internal object UsbOutputStateMachine {
    fun reduce(state: UsbOutputState, event: UsbOutputEvent): UsbOutputReduction = when (event) {
        is UsbOutputEvent.UserSelected -> select(state, event)
        is UsbOutputEvent.UserPlayIntentChanged -> userPlayIntentChanged(state, event)
        UsbOutputEvent.PlayerRetired -> retired(state)
        is UsbOutputEvent.SharedQuiesced -> sharedQuiesced(state, event)
        UsbOutputEvent.UsbAttached -> attached(state)
        is UsbOutputEvent.UsbDetached -> detached(state, event)
        UsbOutputEvent.TargetStable -> targetStable(state)
        UsbOutputEvent.TargetLost -> targetLost(state)
        is UsbOutputEvent.PreparationFailed -> preparationFailed(state, event)
        is UsbOutputEvent.PermissionResult -> permission(state, event)
        is UsbOutputEvent.ExclusiveOpenSucceeded -> opened(state, event)
        is UsbOutputEvent.ExclusiveOpenFailed -> openFailed(state, event)
        UsbOutputEvent.AndroidSharedRouteReady -> sharedRouteReady(state)
        UsbOutputEvent.AndroidSharedRouteUnavailable -> sharedRouteUnavailable(state)
    }

    private fun userPlayIntentChanged(
        state: UsbOutputState,
        event: UsbOutputEvent.UserPlayIntentChanged,
    ): UsbOutputReduction = UsbOutputReduction(
        state.copy(frozenIntent = FrozenPlaybackIntent(state.generation, event.playWhenReady)),
    )

    private fun select(state: UsbOutputState, event: UsbOutputEvent.UserSelected): UsbOutputReduction {
        val generation = state.generation + 1
        val frozen = FrozenPlaybackIntent(generation, event.playWhenReady)
        if (event.mode == DesiredUsbOutput.Shared) {
            val effects = mutableListOf<UsbOutputEffect>()
            val returningFromExclusive =
                state.phase is UsbOutputPhase.ExclusiveActive ||
                state.phase == UsbOutputPhase.ExclusiveOpening ||
                state.phase == UsbOutputPhase.PermissionWaiting ||
                state.phase == UsbOutputPhase.ExclusivePreparing ||
                state.phase == UsbOutputPhase.SharedQuiescing
            if (returningFromExclusive) {
                effects += UsbOutputEffect.CloseExclusive
                effects += UsbOutputEffect.RetirePlayer
            }
            if (event.sharedReturnRequiresReconnect) {
                effects += UsbOutputEffect.ShowReconnectRequired
                return UsbOutputReduction(
                    state.copy(
                        desiredMode = DesiredUsbOutput.Shared,
                        phase = UsbOutputPhase.SharedReconnectRequired,
                        generation = generation,
                        frozenIntent = frozen,
                        targetStable = false,
                        permissionGranted = false,
                        sharedReturnRequiresReconnect = true,
                        openAttempt = 0,
                        activeTransport = null,
                        activeSessionId = null,
                    ),
                    effects,
                )
            }
            if (returningFromExclusive) {
                effects += UsbOutputEffect.WaitForSharedRoute
                return UsbOutputReduction(
                    state.copy(
                        desiredMode = DesiredUsbOutput.Shared,
                        phase = UsbOutputPhase.SharedRouteWaiting,
                        generation = generation,
                        frozenIntent = frozen,
                        targetStable = false,
                        permissionGranted = false,
                        sharedReturnRequiresReconnect = false,
                        openAttempt = 0,
                        activeTransport = null,
                        activeSessionId = null,
                    ),
                    effects,
                )
            }
            effects += UsbOutputEffect.BuildSharedPlayer
            return UsbOutputReduction(
                state.copy(
                    desiredMode = DesiredUsbOutput.Shared,
                    phase = UsbOutputPhase.SharedActive,
                    generation = generation,
                    frozenIntent = frozen,
                    targetStable = false,
                    permissionGranted = false,
                    sharedReturnRequiresReconnect = false,
                    openAttempt = 0,
                    activeTransport = null,
                    activeSessionId = null,
                ),
                effects,
            )
        }

        if (state.phase is UsbOutputPhase.ExclusiveActive) {
            if (state.desiredMode == event.mode) return UsbOutputReduction(state)
            return UsbOutputReduction(
                state.copy(
                    desiredMode = event.mode,
                    phase = UsbOutputPhase.ExclusiveOpening,
                    generation = generation,
                    frozenIntent = frozen,
                    targetStable = true,
                    permissionGranted = true,
                    openAttempt = 0,
                    activeTransport = null,
                    activeSessionId = null,
                ),
                listOf(UsbOutputEffect.RetirePlayer, UsbOutputEffect.RetargetExclusive),
            )
        }

        val leavingActiveShared = state.phase == UsbOutputPhase.SharedActive
        return UsbOutputReduction(
            state.copy(
                desiredMode = event.mode,
                phase = if (leavingActiveShared) UsbOutputPhase.SharedQuiescing else UsbOutputPhase.ExclusivePreparing,
                generation = generation,
                frozenIntent = frozen,
                targetStable = false,
                permissionGranted = false,
                openAttempt = 0,
                activeTransport = null,
                activeSessionId = null,
            ),
            if (leavingActiveShared) {
                listOf(UsbOutputEffect.RetirePlayer, UsbOutputEffect.WaitForSharedQuiescence)
            } else {
                listOf(UsbOutputEffect.RetirePlayer, UsbOutputEffect.WaitForTarget)
            },
        )
    }

    private fun retired(state: UsbOutputState): UsbOutputReduction = when {
        state.desiredMode == DesiredUsbOutput.Shared && state.sharedReturnRequiresReconnect ->
            UsbOutputReduction(state.copy(phase = UsbOutputPhase.SharedReconnectRequired))
        state.desiredMode == DesiredUsbOutput.Shared ->
            UsbOutputReduction(state.copy(phase = UsbOutputPhase.SharedActive), listOf(UsbOutputEffect.BuildSharedPlayer))
        else -> UsbOutputReduction(
            state.copy(phase = UsbOutputPhase.ExclusivePreparing),
            listOf(UsbOutputEffect.WaitForTarget),
        )
    }

    private fun sharedQuiesced(
        state: UsbOutputState,
        event: UsbOutputEvent.SharedQuiesced,
    ): UsbOutputReduction {
        if (
            event.generation != state.generation ||
            state.desiredMode == DesiredUsbOutput.Shared ||
            state.phase != UsbOutputPhase.SharedQuiescing
        ) return UsbOutputReduction(state)
        return UsbOutputReduction(
            state.copy(phase = UsbOutputPhase.ExclusivePreparing),
            listOf(UsbOutputEffect.WaitForTarget),
        )
    }

    private fun attached(state: UsbOutputState): UsbOutputReduction = when {
        state.desiredMode == DesiredUsbOutput.Shared &&
            (state.phase == UsbOutputPhase.Disconnected || state.phase == UsbOutputPhase.SharedReconnectRequired) ->
            UsbOutputReduction(
                state.copy(phase = UsbOutputPhase.SharedRouteWaiting),
                listOf(UsbOutputEffect.WaitForSharedRoute),
            )
        state.desiredMode != DesiredUsbOutput.Shared -> UsbOutputReduction(
            state.copy(phase = UsbOutputPhase.ExclusivePreparing),
            listOf(UsbOutputEffect.WaitForTarget),
        )
        else -> UsbOutputReduction(state)
    }

    private fun detached(state: UsbOutputState, event: UsbOutputEvent.UsbDetached): UsbOutputReduction {
        val effects = buildList {
            if (state.phase is UsbOutputPhase.ExclusiveActive || state.phase == UsbOutputPhase.ExclusiveOpening) {
                add(UsbOutputEffect.CloseExclusive)
            }
            add(UsbOutputEffect.RetirePlayer)
        }
        return UsbOutputReduction(
            state.copy(
                phase = UsbOutputPhase.Disconnected,
                targetStable = false,
                permissionGranted = false,
                frozenIntent = FrozenPlaybackIntent(state.generation, event.playWhenReady),
                activeTransport = null,
                activeSessionId = null,
            ),
            effects,
        )
    }

    private fun targetStable(state: UsbOutputState): UsbOutputReduction {
        if (state.desiredMode == DesiredUsbOutput.Shared || state.phase != UsbOutputPhase.ExclusivePreparing) {
            return UsbOutputReduction(state.copy(targetStable = true))
        }
        return if (state.permissionGranted) {
            UsbOutputReduction(
                state.copy(phase = UsbOutputPhase.ExclusiveOpening, targetStable = true),
                listOf(UsbOutputEffect.OpenExclusive),
            )
        } else {
            UsbOutputReduction(
                state.copy(phase = UsbOutputPhase.PermissionWaiting, targetStable = true),
                listOf(UsbOutputEffect.RequestPermission),
            )
        }
    }

    private fun targetLost(state: UsbOutputState): UsbOutputReduction {
        if (state.desiredMode == DesiredUsbOutput.Shared) return UsbOutputReduction(state.copy(targetStable = false))
        val effects = mutableListOf<UsbOutputEffect>()
        if (state.phase == UsbOutputPhase.ExclusiveOpening || state.phase is UsbOutputPhase.ExclusiveActive) {
            effects += UsbOutputEffect.RetirePlayer
            effects += UsbOutputEffect.CloseExclusive
        }
        effects += UsbOutputEffect.WaitForTarget
        return UsbOutputReduction(
            state.copy(
                phase = UsbOutputPhase.ExclusivePreparing,
                targetStable = false,
                permissionGranted = false,
                activeTransport = null,
                activeSessionId = null,
            ),
            effects,
        )
    }

    private fun preparationFailed(state: UsbOutputState, event: UsbOutputEvent.PreparationFailed): UsbOutputReduction {
        if (
            event.generation != state.generation ||
            (state.phase != UsbOutputPhase.ExclusivePreparing && state.phase != UsbOutputPhase.SharedQuiescing)
        ) return UsbOutputReduction(state)
        return UsbOutputReduction(
            state.copy(phase = UsbOutputPhase.Failed(event.code, event.message)),
            listOf(UsbOutputEffect.ShowError(event.code, event.message)),
        )
    }

    private fun permission(state: UsbOutputState, event: UsbOutputEvent.PermissionResult): UsbOutputReduction {
        if (event.generation != state.generation || state.phase != UsbOutputPhase.PermissionWaiting) {
            return UsbOutputReduction(state)
        }
        if (!event.granted) {
            return UsbOutputReduction(
                state.copy(
                    phase = UsbOutputPhase.Failed("USB_PERMISSION_DENIED", "USB permission was denied."),
                    permissionGranted = false,
                ),
                listOf(UsbOutputEffect.ShowError("USB_PERMISSION_DENIED", "USB permission was denied.")),
            )
        }
        return UsbOutputReduction(
            state.copy(phase = UsbOutputPhase.ExclusiveOpening, permissionGranted = true),
            listOf(UsbOutputEffect.OpenExclusive),
        )
    }

    private fun opened(state: UsbOutputState, event: UsbOutputEvent.ExclusiveOpenSucceeded): UsbOutputReduction {
        if (event.generation != state.generation) return UsbOutputReduction(state)
        if (state.phase != UsbOutputPhase.ExclusiveOpening && state.phase !is UsbOutputPhase.ExclusiveActive) {
            return UsbOutputReduction(state)
        }
        if (!transportMatchesPolicy(state.desiredMode, event.transport)) return UsbOutputReduction(state)

        val transportState = state.copy(
            activeTransport = event.transport,
            activeSessionId = event.sessionId ?: state.activeSessionId,
        )
        val activatedFromOpening = state.phase == UsbOutputPhase.ExclusiveOpening
        return UsbOutputReduction(
            if (activatedFromOpening) {
                transportState.copy(phase = UsbOutputPhase.ExclusiveActive(state.desiredMode))
            } else {
                transportState
            },
            if (activatedFromOpening) listOf(UsbOutputEffect.RestoreFrozenPlaybackIntent) else emptyList(),
        )
    }

    private fun transportMatchesPolicy(
        desiredMode: DesiredUsbOutput,
        transport: UsbActiveTransport,
    ): Boolean = when (desiredMode) {
        DesiredUsbOutput.Shared -> false
        DesiredUsbOutput.ExactPcm -> transport == UsbActiveTransport.PCM
        DesiredUsbOutput.Dop -> transport == UsbActiveTransport.PCM || transport == UsbActiveTransport.DOP
        DesiredUsbOutput.NativeDsd -> transport == UsbActiveTransport.PCM || transport == UsbActiveTransport.NATIVE_DSD
    }

    private fun openFailed(state: UsbOutputState, event: UsbOutputEvent.ExclusiveOpenFailed): UsbOutputReduction {
        if (event.generation != state.generation || state.phase != UsbOutputPhase.ExclusiveOpening) {
            return UsbOutputReduction(state)
        }
        if (event.transientOwnershipRace && state.openAttempt < 2) {
            return UsbOutputReduction(
                state.copy(
                    phase = UsbOutputPhase.ExclusivePreparing,
                    targetStable = false,
                    permissionGranted = true,
                    openAttempt = state.openAttempt + 1,
                ),
                listOf(UsbOutputEffect.RetirePlayer, UsbOutputEffect.CloseExclusive, UsbOutputEffect.WaitForTarget),
            )
        }
        return UsbOutputReduction(
            state.copy(phase = UsbOutputPhase.Failed(event.code, event.message)),
            listOf(UsbOutputEffect.ShowError(event.code, event.message)),
        )
    }

    private fun sharedRouteReady(state: UsbOutputState): UsbOutputReduction {
        if (state.desiredMode != DesiredUsbOutput.Shared || state.phase != UsbOutputPhase.SharedRouteWaiting) {
            return UsbOutputReduction(state)
        }
        return UsbOutputReduction(
            state.copy(
                phase = UsbOutputPhase.SharedActive,
                sharedReturnRequiresReconnect = false,
            ),
            listOf(UsbOutputEffect.BuildSharedPlayer),
        )
    }

    private fun sharedRouteUnavailable(state: UsbOutputState): UsbOutputReduction {
        if (state.desiredMode != DesiredUsbOutput.Shared || state.phase != UsbOutputPhase.SharedRouteWaiting) {
            return UsbOutputReduction(state)
        }
        return UsbOutputReduction(
            state.copy(
                phase = UsbOutputPhase.SharedReconnectRequired,
                sharedReturnRequiresReconnect = true,
            ),
            listOf(UsbOutputEffect.ShowReconnectRequired),
        )
    }
}
