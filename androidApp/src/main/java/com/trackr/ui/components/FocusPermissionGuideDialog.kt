package com.trackr.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 系统实时活动（状态栏胶囊 / 流体云 / 焦点通知）权限未授权时的引导对话框
 */
@Composable
fun FocusPermissionGuideDialog(
    onDismissRequest: () -> Unit,
    onContinueWithoutSettings: () -> Unit,
    onGoToSettings: () -> Unit,
    title: String = "开启焦点通知（超级岛）",
    deviceHint: String = "检测到您正在使用小米澎湃OS（Xiaomi HyperOS）。",
    permissionName: String = "允许焦点通知"
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        icon = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = deviceHint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "开启「$permissionName」后，即可在屏幕挖孔两侧显示实时公交小胶囊，并在下拉时展开实时到站倒计时与站点进度。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "未开启时仍可正常使用普通通知栏进度追踪。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onGoToSettings,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("前往开启")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onContinueWithoutSettings,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("暂不开启")
            }
        }
    )
}
