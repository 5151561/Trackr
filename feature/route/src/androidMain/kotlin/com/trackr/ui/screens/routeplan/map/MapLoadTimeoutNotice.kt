package com.trackr.ui.screens.routeplan.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_retry
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_map_diag
import com.trackr.feature.route.resources.route_map_not_loaded
import com.trackr.feature.route.resources.route_map_skip
import org.jetbrains.compose.resources.stringResource

/**
 * 底图加载失败 / 超时提示：不销毁地图，只做浮层说明 + 重试 / 诊断入口。
 * 第 24 步从旧 `RoutePlanScreen.kt` 原样搬来。
 */
@Composable
internal fun MapLoadTimeoutNotice(
    reason: String,
    onRetry: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("card_map_timeout"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(RouteRes.string.route_map_not_loaded),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("btn_map_timeout_retry")
                ) {
                    Text(stringResource(Res.string.action_retry))
                }
                OutlinedButton(
                    onClick = onShowDiagnostics,
                    modifier = Modifier.testTag("btn_map_diagnostics")
                ) {
                    Text(stringResource(RouteRes.string.route_map_diag))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(RouteRes.string.route_map_skip))
                }
            }
        }
    }
}
