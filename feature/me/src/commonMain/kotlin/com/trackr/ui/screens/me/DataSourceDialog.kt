package com.trackr.ui.screens.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.trackr.core.model.CustomSourceConfig

/** 「数据源」那一行右侧的状态文字：没接入写「未接入」，接入了显示服务的主机名 */
internal fun customSourceLabel(config: CustomSourceConfig): String {
    if (!config.isConfigured) return "未接入"
    return config.normalizedBaseUrl
        .substringAfter("://")
        .substringBefore('/')
        .ifBlank { "已接入" }
}

/**
 * 「我的 → 数据源」：填用户自己部署的数据源服务地址与可选令牌。
 *
 * Trackr 不内置、不提供任何第三方实时公交数据源，服务要实现 Trackr 数据源协议（仓库 docs/provider-api.md）。
 */
@Composable
internal fun CustomSourceDialog(
    current: CustomSourceConfig,
    onSave: (CustomSourceConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var baseUrl by rememberSaveable { mutableStateOf(current.baseUrl) }
    var token by rememberSaveable { mutableStateOf(current.token) }
    val candidate = CustomSourceConfig(baseUrl = baseUrl, token = token)
    val urlError = baseUrl.isNotBlank() && !candidate.isConfigured

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数据源") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Trackr 不内置任何实时公交数据源。请填写你自己部署的、实现了 Trackr 数据源协议的服务地址；" +
                        "数据来源及其合规性由你自行负责。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("服务地址") },
                    placeholder = { Text("https://example.com/trackr") },
                    singleLine = true,
                    isError = urlError,
                    supportingText = if (urlError) {
                        { Text("需为 https:// 开头的地址") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("访问令牌（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(candidate) },
                enabled = baseUrl.isBlank() || candidate.isConfigured
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
