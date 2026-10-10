package com.mica.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The B2 REFERENCE_CAUSE scenario, but using the B3 motion controller, NOT
 * Foundation animateItem. Same padding 434, outer top 105, line heights,
 * first visible index 7:57, 30px growth, and twenty 2px scroll samples.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsOwnedDepartureProbeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun originalPlus463PxSequenceDoesNotDropOldRow() {
        val grow = mutableStateOf(false)
        val locations = mutableMapOf<Int, LayoutCoordinates>()
        lateinit var scroll: (Float) -> Unit
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state = rememberLazyListState(7, 57)
            val scope = rememberCoroutineScope()
            val owned = remember { OwnedLyricsPositionMotion(scope) }
            DisposableEffect(owned) { onDispose { owned.dispose() } }
            LaunchedEffect(owned, state) {
                snapshotFlow {
                    state.layoutInfo.visibleItemsInfo.map {
                        OwnedLyricGeometry(it.key.toString(), it.offset, it.size)
                    }
                }.collect {
                    owned.accept(it, animate = true, stiffness = 170f,
                        scrolling = state.isScrollInProgress)
                }
            }
            scroll = { delta -> scope.launch { state.scrollBy(delta) }; Unit }
            Box(Modifier.padding(top = 105.dp)) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxWidth().height(749.dp)
                        .nestedScroll(owned.nestedScrollConnection),
                    contentPadding = PaddingValues(top = 434.dp, bottom = 50.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(17.dp),
                ) {
                    items((0 until 24).toList(), key = { it.toString() }) { index ->
                        val height = when (index) {
                            0, 6, 8, 11, 13 -> 77
                            1, 2, 3, 4 -> 30
                            5 -> if (grow.value) 77 else 47
                            else -> 47
                        }
                        DisposableEffect(index) { onDispose { locations.remove(index) } }
                        Box(
                            Modifier.fillMaxWidth().height(height.dp)
                                .graphicsLayer {
                                    translationY = owned.translationFor(index.toString())
                                }
                                .onGloballyPositioned { locations[index] = it }
                                .background(if (index == 0) Color.Red else Color.Gray)
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        var initial = Float.NaN
        rule.runOnIdle {
            initial = locations[0]?.takeIf { it.isAttached }?.positionInRoot()?.y ?: Float.NaN
            grow.value = true
        }
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()

        val samples = mutableListOf<Float>()
        repeat(100) { frame ->
            rule.runOnIdle {
                if (frame < 20) scroll(2f)
                locations[0]?.takeIf { it.isAttached }?.positionInRoot()?.y?.let(samples::add)
            }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        val maxY = samples.maxOrNull() ?: initial
        println("B3_OWNED_DEPARTURE initial=$initial max=$maxY drop=${maxY - initial} count=${samples.size}")
        assertTrue("No old row was measured", initial.isFinite() && samples.isNotEmpty())
        assertTrue("B3 sent old lyric down: initial=$initial max=$maxY", maxY - initial < 30f)
    }

    @Test fun interludeInsertionRisesAboveAndKeepsNextLineStationary() {
        val insert = mutableStateOf(false)
        val locations = mutableMapOf<String, LayoutCoordinates>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val owned = remember { OwnedLyricsPositionMotion(scope) }
            DisposableEffect(owned) { onDispose { owned.dispose() } }
            LaunchedEffect(owned, state) {
                snapshotFlow {
                    state.layoutInfo.visibleItemsInfo.map {
                        OwnedLyricGeometry(it.key.toString(), it.offset, it.size)
                    }
                }.collect {
                    owned.accept(it, animate = true, stiffness = 170f,
                        scrolling = state.isScrollInProgress)
                }
            }
            LaunchedEffect(insert.value) {
                if (insert.value) state.scrollBy(27f)
            }
            val rows = if (insert.value) {
                listOf("upper", "interlude", "lower", "tail-a", "tail-b")
            } else {
                listOf("upper", "lower", "tail-a", "tail-b")
            }
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxWidth().height(600.dp)
                    .nestedScroll(owned.nestedScrollConnection),
                contentPadding = PaddingValues(top = 200.dp, bottom = 550.dp),
            ) {
                items(rows, key = { it }) { key ->
                    Box(
                        Modifier.fillMaxWidth().height(if (key == "interlude") 27.dp else 60.dp)
                            .graphicsLayer { translationY = owned.translationFor(key) }
                            .onGloballyPositioned { locations[key] = it }
                            .background(Color.Gray)
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        var upperBefore = 0f
        var lowerBefore = 0f
        rule.runOnIdle {
            upperBefore = locations.getValue("upper").positionInRoot().y
            lowerBefore = locations.getValue("lower").positionInRoot().y
            insert.value = true
        }
        val lowerSamples = mutableListOf<Float>()
        repeat(30) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            rule.runOnIdle {
                locations["lower"]?.takeIf { it.isAttached }?.positionInRoot()?.y
                    ?.let(lowerSamples::add)
            }
        }
        var upperAfter = 0f
        rule.runOnIdle {
            upperAfter = locations.getValue("upper").positionInRoot().y
        }
        val drift = lowerSamples.maxOf { kotlin.math.abs(it - lowerBefore) }
        println("B3_INTERLUDE upperMove=${upperAfter - upperBefore} lowerMaxDrift=$drift")
        assertTrue("Upper lyric must rise after interlude insertion", upperAfter < upperBefore - 20f)
        assertTrue("Next lyric jumped instead of staying anchored: $drift", drift < 5f)
    }

    @Test fun rapidSeekAndRepeatedLineChangesCancelPreviousMotionAndAnchorLastTarget() {
        val focus = mutableStateOf(7)
        val grow = mutableStateOf(false)
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        lateinit var owned: OwnedLyricsPositionMotion
        rule.mainClock.autoAdvance = false
        rule.setContent {
            state = rememberLazyListState(7, 57)
            val scope = rememberCoroutineScope()
            owned = remember { OwnedLyricsPositionMotion(scope) }
            DisposableEffect(owned) { onDispose { owned.dispose() } }
            LaunchedEffect(owned, state) {
                snapshotFlow {
                    state.layoutInfo.visibleItemsInfo.map {
                        OwnedLyricGeometry(it.key.toString(), it.offset, it.size)
                    }
                }.collect {
                    owned.accept(it, animate = true, stiffness = 170f,
                        scrolling = state.isScrollInProgress)
                }
            }
            LaunchedEffect(focus.value) {
                owned.reset()
                state.scrollToItem(focus.value)
            }
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxWidth().height(749.dp)
                    .nestedScroll(owned.nestedScrollConnection),
                contentPadding = PaddingValues(top = 434.dp, bottom = 550.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(17.dp),
            ) {
                items((0 until 32).toList(), key = { it.toString() }) { index ->
                    val height = if (index == 5 && grow.value) 77 else 47
                    Box(
                        Modifier.fillMaxWidth().height(height.dp)
                            .graphicsLayer { translationY = owned.translationFor(index.toString()) }
                            .background(Color.Gray)
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        rule.runOnIdle { grow.value = true }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        listOf(10, 3, 16, 2, 19).forEach { target ->
            rule.runOnIdle { focus.value = target }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("Final seek target must be visible", state.layoutInfo.visibleItemsInfo.any { it.key == "19" })
            assertTrue("Rapid seek should adopt target item as first visible", state.firstVisibleItemIndex == 19)
            assertTrue("No old translation may attach to the new key",
                kotlin.math.abs(owned.translationFor("19")) < 0.5f)
            assertTrue("Old row translation must be retired",
                kotlin.math.abs(owned.translationFor("5")) < 0.5f)
        }
    }
}
