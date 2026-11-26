package com.trackr.feature.line.elder

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 长辈版页面的尺寸检查（§8.7）。要配合 `@GraphicsMode(NATIVE)`：默认图形模式下 Robolectric 不真量字宽。
 */
internal object ElderLayoutChecks {

    /** 当前屏上所有可点元素宽高都 ≥ 64dp */
    fun assertClickablesAtLeast64dp(rule: ComposeContentTestRule) {
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("屏上没有可点元素", nodes.isNotEmpty())
        nodes.forEach { node ->
            val width = with(rule.density) { node.size.width.toDp() }
            val height = with(rule.density) { node.size.height.toDp() }
            assertTrue("可点元素 ${node.config} 只有 $width x $height", width >= 64.dp && height >= 64.dp)
        }
    }

    /** 当前屏上的文字：没被截断（宽、高、省略号），也没伸出屏幕右边 */
    fun assertNoTextClipped(rule: ComposeContentTestRule, scale: Float) {
        val textNodes = rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(textNodes.isNotEmpty())
        val rootWidth = rule.onRoot().fetchSemanticsNode().size.width.toFloat()
        textNodes.forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val result = results.singleOrNull() ?: return@forEach
            val text = result.layoutInput.text
            // 不用 hasVisualOverflow：Column 里的 Text 会先按内容宽量一次，它对没裁的字也报 true（截图里字是完整的）。
            // 改为逐行看：每一行的右边不超出这段字实际占的宽度，高度没被截
            assertFalse("「$text」在 $scale 倍字体下高度被裁", result.didOverflowHeight)
            for (line in 0 until result.lineCount) {
                assertTrue(
                    "「$text」第 ${line + 1} 行在 $scale 倍字体下被裁",
                    result.getLineRight(line) - result.getLineLeft(line) <= node.size.width + 1f
                )
            }
            assertFalse("「$text」在 $scale 倍字体下被省略号截断", result.isLineEllipsized(result.lineCount - 1))
            assertTrue("「$text」在 $scale 倍字体下伸出屏幕", node.boundsInRoot.right <= rootWidth + 0.5f)
        }
    }
}

/** 截图：只生成，不比对（和 androidApp 的 ScreenshotGeneratorTest 一样，工程没有截图比对库） */
internal object ElderScreenshots {
    fun capture(rule: ComposeContentTestRule, page: String, scale: Float, darkTheme: Boolean) {
        val dir = File("build/screenshots/elder").apply { mkdirs() }
        val name = "${page}_${if (darkTheme) "dark" else "light"}_x$scale.png"
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
