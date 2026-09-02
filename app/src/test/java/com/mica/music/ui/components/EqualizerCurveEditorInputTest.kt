package com.mica.music.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.mica.music.audio.eq.EqBandConstants
import com.mica.music.audio.eq.EqualizerBand
import com.mica.music.ui.theme.MicaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val EditorTag = "eq-curve"
private val EditorGraphHeight = 200.dp

/**
 * 冻结 [EqualizerCurveEditor] 的输入契约：频段列映射、量程钳制、单次拖动锁定起始频段、
 * 嵌套在 `verticalScroll` 中仍能拿到竖向拖动、旁路时不响应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-mdpi")
class EqualizerCurveEditorInputTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val changes = mutableListOf<Pair<Int, Short>>()

    /** 频段列中心的 x；绘图区从 [DbScaleWidth] 开始，十段等宽。 */
    private fun TouchInjectionScope.bandCenterX(bandIndex: Int): Float {
        val plotLeft = DbScaleWidth.toPx()
        val columnWidth = (width - plotLeft) / EqBandConstants.BAND_COUNT
        return plotLeft + columnWidth * (bandIndex + 0.5f)
    }

    /** 0 dB 线；绘图区上下内缩量对称，所以恒在图形高度的正中。 */
    private fun TouchInjectionScope.zeroLineY(): Float = EditorGraphHeight.toPx() / 2f

    @Composable
    private fun Editor(enabled: Boolean = true) {
        EqualizerCurveEditor(
            bands = EqBandConstants.CENTER_HZ.map { hz ->
                EqualizerBand(centerHz = hz, levelMillibels = 0)
            },
            minMillibels = EqBandConstants.MIN_MILLIBELS,
            maxMillibels = EqBandConstants.MAX_MILLIBELS,
            enabled = enabled,
            selectedBandIndex = null,
            onBandTouched = { bandIndex, level -> changes += bandIndex to level },
            modifier = Modifier.testTag(EditorTag),
            graphHeight = EditorGraphHeight,
        )
    }

    private fun setEditor(enabled: Boolean = true) {
        composeRule.setContent {
            MicaTheme {
                Box(modifier = Modifier.fillMaxWidth()) { Editor(enabled = enabled) }
            }
        }
    }

    @Test
    fun tapMapsToBandColumnAndZeroLine() {
        setEditor()

        composeRule.onNodeWithTag(EditorTag).performTouchInput {
            click(Offset(bandCenterX(3), zeroLineY()))
        }

        composeRule.runOnIdle {
            assertEquals("应命中第 4 段，实际 $changes", 3, changes.single().first)
            assertEquals("绘图区正中应为 0 dB，实际 $changes", 0.toShort(), changes.single().second)
        }
    }

    @Test
    fun tapAtEdgesClampsToRange() {
        setEditor()

        composeRule.onNodeWithTag(EditorTag).performTouchInput {
            click(Offset(bandCenterX(0), 0f))
        }
        composeRule.onNodeWithTag(EditorTag).performTouchInput {
            click(Offset(bandCenterX(9), EditorGraphHeight.toPx() - 1f))
        }

        composeRule.runOnIdle {
            assertEquals(
                "顶边应钳到最大增益，实际 $changes",
                EqBandConstants.MAX_MILLIBELS,
                changes.first().second,
            )
            assertEquals(
                "底边应钳到最小增益，实际 $changes",
                EqBandConstants.MIN_MILLIBELS,
                changes.last().second,
            )
        }
    }

    @Test
    fun dragStaysOnStartBandDespiteHorizontalMovement() {
        setEditor()

        composeRule.onNodeWithTag(EditorTag).performTouchInput {
            swipe(
                start = Offset(bandCenterX(6), zeroLineY() + 60f),
                end = Offset(bandCenterX(1), zeroLineY() - 60f),
                durationMillis = 300,
            )
        }

        composeRule.runOnIdle {
            assertTrue("应产生多次更新，实际 $changes", changes.size > 1)
            assertTrue("整段拖动都应锁定在起始频段 6，实际 $changes", changes.all { it.first == 6 })
            assertTrue("向上拖动应抬升增益，实际 $changes", changes.last().second > 0)
        }
    }

    @Test
    fun verticalDragIsNotStolenByParentScroll() {
        lateinit var scrollState: ScrollState
        composeRule.setContent {
            MicaTheme {
                scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .verticalScroll(scrollState),
                ) {
                    Editor()
                    Spacer(Modifier.height(1_200.dp))
                }
            }
        }

        composeRule.onNodeWithTag(EditorTag).performTouchInput {
            swipe(
                start = Offset(bandCenterX(4), zeroLineY() + 60f),
                end = Offset(bandCenterX(4), zeroLineY() - 60f),
                durationMillis = 300,
            )
        }

        composeRule.runOnIdle {
            assertTrue("竖向拖动应落到编辑器，实际 $changes", changes.any { it.first == 4 })
            assertTrue("向上拖动应抬升增益，实际 $changes", changes.last().second > 0)
            assertEquals("外层不应跟着滚动", 0, scrollState.value)
        }
    }

    @Test
    fun disabledEditorIgnoresInput() {
        setEditor(enabled = false)

        composeRule.onNodeWithTag(EditorTag).performTouchInput {
            click(Offset(bandCenterX(5), zeroLineY()))
        }
        composeRule.onNodeWithTag(EditorTag).performTouchInput {
            swipe(
                start = Offset(bandCenterX(5), zeroLineY() + 60f),
                end = Offset(bandCenterX(5), zeroLineY() - 60f),
                durationMillis = 300,
            )
        }

        composeRule.runOnIdle {
            assertTrue("旁路时不应产生任何更新，实际 $changes", changes.isEmpty())
        }
    }
}
