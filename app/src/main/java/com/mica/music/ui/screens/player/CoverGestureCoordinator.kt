package com.mica.music.ui.screens.player

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.Song
import com.mica.music.ui.motion.MicaMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val DragCommitFraction = 0.35f
private const val StandardSwipeMaxFraction = 0.15f

internal data class CarouselPose(
    val anchorIndex: Int,
    val virtualCenterIndex: Float,
)

data class CoverLaneBinding(
    val laneOffset: Int,
    val queueIndex: Int,
    val song: Song?,
)

@Stable
data class CoverGestureState(
    val centerAnchorIndex: Int,
    val virtualCenterIndex: Float,
    val dragOffsetFraction: Float,
    val standardSwipeOffsetFraction: Float,
    val isDragging: Boolean,
    val laneBindings: List<CoverLaneBinding>,
    val handlers: CoverGestureHandlers,
)

@Stable
class CoverGestureHandlers internal constructor(
    private val scope: CoroutineScope,
    private val gesturesEnabled: () -> Boolean,
    private val screenWidthPx: () -> Float,
    private val coverFlowStageActive: () -> Boolean,
    private val coverFlowMode: () -> PlayerCoverFlowMode,
    private val standardMode: () -> Boolean,
    private val motionEnabled: () -> Boolean,
    private val currentIndex: () -> Int,
    private val queueLastIndex: () -> Int,
    private val onPlayQueueIndex: (Int) -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val readPose: () -> CarouselPose,
    private val writeVirtualCenter: (Float) -> Unit,
    private val standardSwipeFraction: () -> Float,
    private val setStandardSwipeFraction: (Float) -> Unit,
    private val setDragging: (Boolean) -> Unit,
    private val cancelSettle: () -> Unit,
    private val launchSettle: (Job) -> Unit,
) {
    fun onDragStart() {
        if (!gesturesEnabled()) return
        cancelSettle()
        setDragging(true)
    }

    fun onHorizontalDrag(deltaPx: Float) {
        if (!gesturesEnabled()) return
        val width = screenWidthPx()
        if (width <= 0f) return
        val delta = deltaPx / (width * CoverFlowMath.laneStepFraction(coverFlowMode()))
        if (coverFlowStageActive()) {
            writeVirtualCenter(readPose().virtualCenterIndex - delta)
        } else if (standardMode()) {
            setStandardSwipeFraction(
                (standardSwipeFraction() + deltaPx / width).coerceIn(
                    -StandardSwipeMaxFraction,
                    StandardSwipeMaxFraction,
                ),
            )
        }
    }

    fun onDragEnd() {
        setDragging(false)
        if (!gesturesEnabled()) return
        val width = screenWidthPx()
        if (width <= 0f) return
        if (coverFlowStageActive()) {
            handleCoverFlowDragEnd()
        } else if (standardMode()) {
            handleStandardSwipeEnd()
        }
    }

    private fun laneFraction(): Float {
        val pose = readPose()
        return pose.virtualCenterIndex - pose.anchorIndex
    }

    private fun handleCoverFlowDragEnd() {
        val fraction = laneFraction()
        when {
            fraction > DragCommitFraction -> {
                val index = currentIndex()
                val target = (index + 1).coerceAtMost(queueLastIndex())
                if (target != index) onPlayQueueIndex(target) else onNext()
            }
            fraction < -DragCommitFraction -> {
                val index = currentIndex()
                val target = (index - 1).coerceAtLeast(0)
                if (target != index) onPlayQueueIndex(target) else onPrevious()
            }
            else -> {
                val job = scope.launch {
                    val pose = readPose()
                    val target = pose.anchorIndex.toFloat()
                    val start = pose.virtualCenterIndex
                    if (abs(start - target) < 0.0001f) return@launch
                    animate(
                        initialValue = start,
                        targetValue = target,
                        animationSpec = MicaMotion.tweenFloat(
                            motionEnabled(),
                            MicaMotion.DurationMediumMs,
                        ),
                    ) { value, _ ->
                        writeVirtualCenter(value)
                    }
                    writeVirtualCenter(target)
                }
                launchSettle(job)
            }
        }
    }

    private fun handleStandardSwipeEnd() {
        val swipe = standardSwipeFraction()
        when {
            swipe > DragCommitFraction * 0.5f -> {
                setStandardSwipeFraction(0f)
                onPrevious()
            }
            swipe < -DragCommitFraction * 0.5f -> {
                setStandardSwipeFraction(0f)
                onNext()
            }
            else -> setStandardSwipeFraction(0f)
        }
    }
}

@Composable
fun rememberCoverGestureState(
    queue: List<Song>,
    currentIndex: Int,
    coverFlowStageActive: Boolean,
    coverFlowMode: PlayerCoverFlowMode,
    gesturesEnabled: Boolean,
    standardMode: Boolean,
    screenWidthPx: Float,
    motionEnabled: Boolean,
    onPlayQueueIndex: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): CoverGestureState {
    val scope = rememberCoroutineScope()
    val currentIndexState = rememberUpdatedState(currentIndex)
    val coverFlowModeState = rememberUpdatedState(coverFlowMode)
    var pose by remember {
        mutableStateOf(CarouselPose(currentIndex, currentIndex.toFloat()))
    }
    var laneBindings by remember {
        mutableStateOf(buildLaneBindings(queue, currentIndex))
    }
    var standardSwipeFraction by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(coverFlowStageActive, currentIndex, queue.size) {
        if (!coverFlowStageActive) {
            pose = CarouselPose(currentIndex, currentIndex.toFloat())
            laneBindings = buildLaneBindings(queue, currentIndex)
        }
    }

    fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
    }

    fun commitPose(anchorIndex: Int, virtualCenterIndex: Float) {
        Snapshot.withMutableSnapshot {
            pose = CarouselPose(anchorIndex, virtualCenterIndex)
        }
    }

    LaunchedEffect(currentIndex, coverFlowStageActive, motionEnabled) {
        val target = currentIndex.toFloat()
        if (pose.anchorIndex == currentIndex && abs(pose.virtualCenterIndex - target) < 0.0001f) {
            return@LaunchedEffect
        }

        cancelSettle()

        val fromAnchor = pose.anchorIndex
        val signedDelta = currentIndex - fromAnchor

        val shouldAnimate =
            coverFlowStageActive &&
                motionEnabled &&
                signedDelta != 0 &&
                abs(signedDelta) == 1

        if (!shouldAnimate) {
            commitPose(currentIndex, target)
            if (signedDelta != 0) {
                laneBindings = buildLaneBindings(queue, currentIndex)
            }
            standardSwipeFraction = 0f
            return@LaunchedEffect
        }

        laneBindings = buildLaneBindings(queue, fromAnchor)

        standardSwipeFraction = 0f
        val duration = if (coverFlowModeState.value == PlayerCoverFlowMode.RETRO_3D) {
            MicaMotion.DurationLongMs
        } else {
            MicaMotion.DurationMediumMs
        }

        val startVisual = pose.virtualCenterIndex
        val endVisual = fromAnchor + signedDelta.toFloat()
        if (abs(startVisual - endVisual) > 0.0001f) {
            animate(
                initialValue = startVisual,
                targetValue = endVisual,
                animationSpec = MicaMotion.tweenFloat(motionEnabled, duration),
            ) { value, _ ->
                pose = pose.copy(virtualCenterIndex = value)
            }
        }
        withFrameNanos { }
        commitPose(anchorIndex = currentIndex, virtualCenterIndex = target)
        laneBindings = buildLaneBindings(queue, currentIndex)
    }

    val laneFraction = pose.virtualCenterIndex - pose.anchorIndex

    val handlers = remember(
        scope,
        gesturesEnabled,
        coverFlowStageActive,
        standardMode,
        screenWidthPx,
        motionEnabled,
        queue.size,
        onPlayQueueIndex,
        onPrevious,
        onNext,
    ) {
        CoverGestureHandlers(
            scope = scope,
            gesturesEnabled = { gesturesEnabled },
            screenWidthPx = { screenWidthPx },
            coverFlowStageActive = { coverFlowStageActive },
            coverFlowMode = { coverFlowModeState.value },
            standardMode = { standardMode },
            motionEnabled = { motionEnabled },
            currentIndex = { currentIndexState.value },
            queueLastIndex = { queue.lastIndex },
            onPlayQueueIndex = onPlayQueueIndex,
            onPrevious = onPrevious,
            onNext = onNext,
            readPose = { pose },
            writeVirtualCenter = { virtual ->
                pose = pose.copy(virtualCenterIndex = virtual)
            },
            standardSwipeFraction = { standardSwipeFraction },
            setStandardSwipeFraction = { standardSwipeFraction = it },
            setDragging = { isDragging = it },
            cancelSettle = ::cancelSettle,
            launchSettle = { job -> settleJob = job },
        )
    }

    return CoverGestureState(
        centerAnchorIndex = pose.anchorIndex,
        virtualCenterIndex = pose.virtualCenterIndex,
        dragOffsetFraction = laneFraction,
        standardSwipeOffsetFraction = standardSwipeFraction,
        isDragging = isDragging,
        laneBindings = laneBindings,
        handlers = handlers,
    )
}

internal fun buildLaneBindings(queue: List<Song>, centerAnchorIndex: Int): List<CoverLaneBinding> {
    val windowRadius = CoverFlowMath.LaneWindowRadius
    return (-windowRadius..windowRadius).map { laneOffset ->
        val queueIndex = centerAnchorIndex + laneOffset
        CoverLaneBinding(
            laneOffset = laneOffset,
            queueIndex = queueIndex,
            song = queue.getOrNull(queueIndex),
        )
    }
}

/**
 * Lane 池换绑：仅当 lane 不可见（alpha≈0 或超出视距）时，才把 URI 同步到 anchor+laneOffset。
 * 可见 lane 保持原绑定，避免切歌时销毁/重建 AsyncImage（见 COVER_FLOW_LANE_POOL.md §4.2）。
 */
internal fun reconcileLaneBindings(
    bindings: List<CoverLaneBinding>,
    anchorIndex: Int,
    virtualCenterIndex: Float,
    queue: List<Song>,
    coverFlowMode: PlayerCoverFlowMode,
    foldProgress: Float,
): List<CoverLaneBinding> {
    val maxDistance = CoverFlowMath.MaxViewDistance
    return bindings.map { binding ->
        val boundIndex = binding.queueIndex
        val offset = boundIndex - virtualCenterIndex
        val distance = abs(offset)
        val slotAlpha = CoverFlowMath.slotAlpha(distance, foldProgress, coverFlowMode)
        val targetIndex = anchorIndex + binding.laneOffset
        val hidden = slotAlpha < 0.01f || distance > maxDistance
        if (!hidden) {
            binding
        } else if (boundIndex != targetIndex || binding.song != queue.getOrNull(targetIndex)) {
            binding.withQueueIndex(targetIndex, queue)
        } else {
            binding
        }
    }
}

private fun CoverLaneBinding.withQueueIndex(queueIndex: Int, queue: List<Song>): CoverLaneBinding =
    copy(queueIndex = queueIndex, song = queue.getOrNull(queueIndex))

