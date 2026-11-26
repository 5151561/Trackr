package com.trackr.ui.components.transitcode

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_qr_code
import org.jetbrains.compose.resources.vectorResource

/**
 * 「乘车码」悬浮按钮的展开 / 缩小（v3：往下滑缩成只有图标，往上滑展开）。
 *
 * 把 [nestedScrollConnection] 挂在列表外层（`Modifier.nestedScroll`），按列表实际滚动的方向切换。
 * 手指上推（内容往下翻）缩小；手指下拉（往回翻、或在顶部继续下拉）展开。
 */
@Stable
class TransitCodeFabState(initiallyExpanded: Boolean = true) {

    var expanded: Boolean by mutableStateOf(initiallyExpanded)
        private set

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            onScrolled(consumedY = consumed.y, availableY = available.y)
            return Offset.Zero
        }
    }

    /** 列表滚了一段：[consumedY] < 0 是内容往下翻，> 0 是往回翻；[availableY] > 0 是在顶部还在往下拉 */
    fun onScrolled(consumedY: Float, availableY: Float = 0f) {
        if (consumedY < -SCROLL_THRESHOLD_PX) {
            expanded = false
        } else if (consumedY > SCROLL_THRESHOLD_PX || availableY > SCROLL_THRESHOLD_PX) {
            expanded = true
        }
    }

    companion object {
        /** 小于这个距离（px）的抖动不切换 */
        const val SCROLL_THRESHOLD_PX = 8f

        val Saver: Saver<TransitCodeFabState, Boolean> = Saver(
            save = { it.expanded },
            restore = { TransitCodeFabState(initiallyExpanded = it) }
        )
    }
}

@Composable
fun rememberTransitCodeFabState(): TransitCodeFabState =
    rememberSaveable(saver = TransitCodeFabState.Saver) { TransitCodeFabState() }

/** 测试和 UI 自动化找按钮用 */
const val TRANSIT_CODE_FAB_TAG = "transit_code_fab"

/**
 * 「乘车码」悬浮按钮（v3：公交页右下角固定一个）。点一下就打开，不藏在长按里。
 * 缩小时只剩图标，读屏仍读「乘车码」。打开哪个平台由调用方决定（`TransitCodeLauncher`）。
 */
@Composable
fun TransitCodeFab(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = { Icon(imageVector = vectorResource(Res.drawable.ic_qr_code), contentDescription = null) },
        text = { Text(text = TRANSIT_CODE_LABEL) },
        modifier = modifier
            .testTag(TRANSIT_CODE_FAB_TAG)
            .semantics { contentDescription = TRANSIT_CODE_LABEL }
    )
}

private const val TRANSIT_CODE_LABEL = "乘车码"
