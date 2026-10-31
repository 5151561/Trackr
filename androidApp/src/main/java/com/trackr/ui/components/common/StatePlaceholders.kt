package com.trackr.ui.components.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.trackr.core.designsystem.component.EmptyStateCard as CoreEmptyStateCard
import com.trackr.core.designsystem.component.EmptyStateView as CoreEmptyStateView
import com.trackr.core.designsystem.component.FullscreenErrorView as CoreFullscreenErrorView
import com.trackr.core.designsystem.component.FullscreenLoadingView as CoreFullscreenLoadingView

/**
 * 全屏居中加载视图
 */
@Composable
fun FullscreenLoadingView(
    modifier: Modifier = Modifier,
    message: String? = null
) {
    CoreFullscreenLoadingView(
        modifier = modifier,
        message = message
    )
}

/**
 * 全屏居中错误提示视图（支持重试操作）
 */
@Composable
fun FullscreenErrorView(
    errorMessage: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Warning,
    retryText: String = "重试",
    onRetry: (() -> Unit)? = null
) {
    CoreFullscreenErrorView(
        errorMessage = errorMessage,
        modifier = modifier,
        icon = icon,
        retryText = retryText,
        onRetry = onRetry
    )
}

/**
 * 卡片样式的空数据占位组件
 */
@Composable
fun EmptyStateCard(
    message: String,
    modifier: Modifier = Modifier,
    subMessage: String? = null,
    icon: ImageVector? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    CoreEmptyStateCard(
        message = message,
        modifier = modifier,
        subMessage = subMessage,
        icon = icon,
        actionText = actionText,
        onAction = onAction
    )
}

/**
 * 页面居中样式的空数据占位视图
 */
@Composable
fun EmptyStateView(
    message: String,
    modifier: Modifier = Modifier,
    subMessage: String? = null,
    icon: ImageVector? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    CoreEmptyStateView(
        message = message,
        modifier = modifier,
        subMessage = subMessage,
        icon = icon,
        actionText = actionText,
        onAction = onAction
    )
}
