package com.trackr.ui.screens.stopdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.core.designsystem.theme.TrackrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 「上了哪辆？」弹层（§2.6 第 ④ 条，第 21 步） */
@RunWith(RobolectricTestRunner::class)
class ChooseBoardedBusTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val choice = BoardChoiceUi(
        subtitle = "两辆车都在 科技园站",
        options = listOf(
            BoardOptionUi(busId = "bus-325", lineName = "325", endStop = "科技园", plate = "粤B·58921"),
            BoardOptionUi(busId = "bus-m194", lineName = "M194", endStop = "西丽", plate = "粤B·30117")
        )
    )

    private val chosen = mutableListOf<String?>()

    private fun setContent() {
        composeTestRule.setContent {
            TrackrTheme {
                ChooseBoardedBusContent(choice = choice, onChoose = { chosen += it })
            }
        }
    }

    @Test
    fun showsTitleSubtitleAndEveryBus() {
        setContent()
        composeTestRule.onNodeWithText("上了哪辆？").assertIsDisplayed()
        composeTestRule.onNodeWithText("两辆车都在 科技园站").assertIsDisplayed()
        composeTestRule.onNodeWithText("325").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithText("粤B·58921").assertIsDisplayed()
        composeTestRule.onNodeWithText("M194").assertIsDisplayed()
        composeTestRule.onNodeWithText("粤B·30117").assertIsDisplayed()
        composeTestRule.onNodeWithText("还没上").assertIsDisplayed()
    }

    @Test
    fun tappingBus_choosesItsBusId() {
        setContent()
        composeTestRule.onNodeWithText("→ 西丽").performClick()
        assertEquals(listOf<String?>("bus-m194"), chosen)
    }

    @Test
    fun tappingNotYet_choosesNull() {
        setContent()
        composeTestRule.onNodeWithText("还没上").performClick()
        assertEquals(listOf<String?>(null), chosen)
    }
}
