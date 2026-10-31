package com.trackr.ui.components.rail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteRailDrawUtilsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testConstants_unifyValues() {
        assertEquals(4.dp, RouteRailArrowGap)
        assertEquals(3.dp, RouteRailArrowArm)
        assertEquals(3.5.dp, RouteRailArrowSpread)
        assertEquals(3.dp, RouteRailStrokeWidth)
        assertEquals(2.dp, RouteRailArrowStrokeWidth)
    }

    @Test
    fun testDrawHorizontalTrackSegment_executesWithoutCrash() {
        composeTestRule.setContent {
            Canvas(modifier = Modifier.size(200.dp, 40.dp)) {
                val arrowPath = Path()
                val arrowStroke = Stroke(
                    width = RouteRailArrowStrokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
                val arrowGap = RouteRailArrowGap.toPx()
                val arrowArm = RouteRailArrowArm.toPx()
                val arrowSpread = RouteRailArrowSpread.toPx()
                val strokeWidth = RouteRailStrokeWidth.toPx()

                // 1. With arrow
                drawHorizontalTrackSegment(
                    startX = 0f,
                    endX = 100f,
                    y = 20f,
                    color = Color.Green,
                    strokeWidth = strokeWidth,
                    hasArrow = true,
                    arrowPath = arrowPath,
                    arrowStroke = arrowStroke,
                    arrowGap = arrowGap,
                    arrowArm = arrowArm,
                    arrowSpread = arrowSpread
                )

                // 2. Without arrow
                drawHorizontalTrackSegment(
                    startX = 100f,
                    endX = 200f,
                    y = 20f,
                    color = Color.Gray,
                    strokeWidth = strokeWidth,
                    hasArrow = false,
                    arrowPath = arrowPath,
                    arrowStroke = arrowStroke,
                    arrowGap = arrowGap,
                    arrowArm = arrowArm,
                    arrowSpread = arrowSpread
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun testDrawVerticalTrackSegment_executesWithoutCrash() {
        composeTestRule.setContent {
            Canvas(modifier = Modifier.size(40.dp, 200.dp)) {
                val arrowPath = Path()
                val arrowStroke = Stroke(
                    width = RouteRailArrowStrokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
                val arrowGap = RouteRailArrowGap.toPx()
                val arrowArm = RouteRailArrowArm.toPx()
                val arrowSpread = RouteRailArrowSpread.toPx()
                val strokeWidth = RouteRailStrokeWidth.toPx()

                // 1. With arrow
                drawVerticalTrackSegment(
                    x = 20f,
                    startY = 0f,
                    endY = 100f,
                    color = Color.Green,
                    strokeWidth = strokeWidth,
                    hasArrow = true,
                    arrowPath = arrowPath,
                    arrowStroke = arrowStroke,
                    arrowGap = arrowGap,
                    arrowArm = arrowArm,
                    arrowSpread = arrowSpread
                )

                // 2. Without arrow
                drawVerticalTrackSegment(
                    x = 20f,
                    startY = 100f,
                    endY = 200f,
                    color = Color.Gray,
                    strokeWidth = strokeWidth,
                    hasArrow = false,
                    arrowPath = arrowPath,
                    arrowStroke = arrowStroke,
                    arrowGap = arrowGap,
                    arrowArm = arrowArm,
                    arrowSpread = arrowSpread
                )
            }
        }
        composeTestRule.waitForIdle()
    }
}
