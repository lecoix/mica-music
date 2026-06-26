package com.mica.music.ui.screens.player

import com.mica.music.data.Song
import com.mica.music.data.TrackSkipDirection

internal const val PhotoStackPullAwayDurationMs = 620
internal const val PhotoStackBurnDurationMs = 760
internal const val PhotoStackRapidSkipWindowMs = 300L
internal const val PhotoStackBurnProbability = 0.12f

internal data class PhotoStackSteadyStack(
    val front: Song?,
    val middle: Song?,
    val back: Song?,
)

internal data class PhotoStackTransitionPlan(
    val direction: TrackSkipDirection,
    val stackFront: Song? = null,
    val stackMiddle: Song? = null,
    val stackBack: Song? = null,
    val incomingFront: Song? = null,
    val leavingFront: Song? = null,
    val fadingBack: Song? = null,
    val emergingBack: Song? = null,
)

internal enum class PhotoStackTransitionSlot {
    SteadyBack,
    SteadyMiddle,
    SteadyFront,
    NextEmergingBack,
    NextStackMiddle,
    NextStackFront,
    NextLeavingFront,
    PreviousFadingBack,
    PreviousStackBack,
    PreviousStackMiddle,
    PreviousIncomingFront,
}

internal data class PhotoStackTransitionCard(
    val song: Song,
    val slot: PhotoStackTransitionSlot,
    val showProgress: Boolean,
)

internal fun photoStackSteadyStack(
    queue: List<Song>,
    currentIndex: Int,
    currentSong: Song,
): PhotoStackSteadyStack {
    val front = queue.getOrNull(currentIndex)?.takeIf { it.id == currentSong.id } ?: currentSong
    return PhotoStackSteadyStack(
        front = front,
        middle = queue.getOrNull(currentIndex + 1),
        back = queue.getOrNull(currentIndex + 2),
    )
}

internal fun photoStackTransitionCards(plan: PhotoStackTransitionPlan): List<PhotoStackTransitionCard> =
    when (plan.direction) {
        TrackSkipDirection.TO_NEXT -> buildList {
            plan.emergingBack?.let {
                add(
                    PhotoStackTransitionCard(
                        song = it,
                        slot = PhotoStackTransitionSlot.NextEmergingBack,
                        showProgress = false,
                    ),
                )
            }
            plan.stackMiddle?.let {
                add(
                    PhotoStackTransitionCard(
                        song = it,
                        slot = PhotoStackTransitionSlot.NextStackMiddle,
                        showProgress = false,
                    ),
                )
            }
            plan.stackFront?.let {
                add(
                    PhotoStackTransitionCard(
                        song = it,
                        slot = PhotoStackTransitionSlot.NextStackFront,
                        showProgress = false,
                    ),
                )
            }
            plan.leavingFront?.let {
                add(
                    PhotoStackTransitionCard(
                        song = it,
                        slot = PhotoStackTransitionSlot.NextLeavingFront,
                        showProgress = true,
                    ),
                )
            }
        }
        TrackSkipDirection.TO_PREVIOUS -> buildList {
            plan.fadingBack?.let {
                add(
                    PhotoStackTransitionCard(
                        song = it,
                        slot = PhotoStackTransitionSlot.PreviousFadingBack,
                        showProgress = false,
                    ),
                )
            }
            plan.stackBack?.let {
                add(
                    PhotoStackTransitionCard(
                        song = it,
                        slot = PhotoStackTransitionSlot.PreviousStackBack,
                        showProgress = false,
                    ),
                )
            }
            plan.stackMiddle?.let {
                add(
                    PhotoStackTransitionCard(
                        song = it,
                        slot = PhotoStackTransitionSlot.PreviousStackMiddle,
                        showProgress = false,
                    ),
                )
            }
            plan.incomingFront?.let {
                add(
                    PhotoStackTransitionCard(
                        song = it,
                        slot = PhotoStackTransitionSlot.PreviousIncomingFront,
                        showProgress = true,
                    ),
                )
            }
        }
    }

internal fun photoStackSteadyCards(stack: PhotoStackSteadyStack): List<PhotoStackTransitionCard> = buildList {
    stack.back?.let {
        add(
            PhotoStackTransitionCard(
                song = it,
                slot = PhotoStackTransitionSlot.SteadyBack,
                showProgress = false,
            ),
        )
    }
    stack.middle?.let {
        add(
            PhotoStackTransitionCard(
                song = it,
                slot = PhotoStackTransitionSlot.SteadyMiddle,
                showProgress = false,
            ),
        )
    }
    stack.front?.let {
        add(
            PhotoStackTransitionCard(
                song = it,
                slot = PhotoStackTransitionSlot.SteadyFront,
                showProgress = true,
            ),
        )
    }
}

internal fun photoStackTransitionPlan(
    queue: List<Song>,
    currentIndex: Int,
    currentSong: Song,
    settledFrontSong: Song?,
    direction: TrackSkipDirection,
): PhotoStackTransitionPlan {
    val steady = photoStackSteadyStack(
        queue = queue,
        currentIndex = currentIndex,
        currentSong = currentSong,
    )
    return when (direction) {
        TrackSkipDirection.TO_NEXT -> PhotoStackTransitionPlan(
            direction = direction,
            stackFront = steady.front,
            stackMiddle = steady.middle,
            leavingFront = settledFrontSong,
            emergingBack = steady.back,
        )
        TrackSkipDirection.TO_PREVIOUS -> PhotoStackTransitionPlan(
            direction = direction,
            stackMiddle = queue.getOrNull(currentIndex + 1) ?: settledFrontSong,
            stackBack = queue.getOrNull(currentIndex + 2),
            incomingFront = steady.front,
            fadingBack = queue.getOrNull(currentIndex + 3),
        )
    }
}

fun shouldUsePhotoStackBurn(
    enabled: Boolean,
    motionEnabled: Boolean,
    stableScene: Boolean,
    elapsedSincePreviousMs: Long?,
    randomValue: Float,
): Boolean {
    if (!enabled || !motionEnabled || !stableScene) return false
    if (elapsedSincePreviousMs != null && elapsedSincePreviousMs < PhotoStackRapidSkipWindowMs) {
        return false
    }
    return randomValue.coerceIn(0f, 1f) < PhotoStackBurnProbability
}
