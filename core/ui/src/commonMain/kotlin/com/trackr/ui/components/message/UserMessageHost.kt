package com.trackr.ui.components.message

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.trackr.core.designsystem.theme.LocalUiMode
import com.trackr.core.model.UiMode
import com.trackr.ui.components.elder.ElderNoticeDialog

/**
 * 页面上要显示的一条一次性提示（计划 §2.1 UDF：放在 UiState 里，显示完回报再删）。
 *
 * @param key 这条提示的身份：变了才算新提示（各页 ViewModel 的消息 id，或提示文字本身）
 * @param actionLabel 提示条上的按钮字（如「换分组」）；长辈版不显示按钮
 * @param duration 标准版提示条停多久；null 按 M3 默认（没按钮 Short，有按钮 Indefinite）
 */
@Immutable
data class HostMessage(
    val key: Any,
    val text: String,
    val actionLabel: String? = null,
    val duration: SnackbarDuration? = null
)

/**
 * 一次性提示的宿主（§8.5 叶子组件，第 34 步）。按界面模式选样子：
 * - 标准版：M3 Snackbar，到时候自己消失，和原来各页的提示条一样。放在 Scaffold 的 `snackbarHost` 槽里。
 * - 长辈版：常驻提示，要点「知道了」才关，不会自己消失（设计稿「不用会自己消失的提示条」）。
 *
 * 一次只显示 [message] 这一条；显示完（标准版消失、被划掉或点了按钮，长辈版点「知道了」）回调 [onResult]，
 * `actionPerformed` 为是否点了提示条上的按钮。页面收到后删掉这条，宿主再显示下一条。
 *
 * @param persistent 是否用常驻提示；默认按 [LocalUiMode]
 */
@Composable
fun UserMessageHost(
    message: HostMessage?,
    onResult: (key: Any, actionPerformed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    persistent: Boolean = LocalUiMode.current == UiMode.Elder,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val latestOnResult by rememberUpdatedState(onResult)
    if (persistent) {
        if (message != null) {
            ElderNoticeDialog(text = message.text, onDismiss = { latestOnResult(message.key, false) })
        }
        return
    }
    LaunchedEffect(message?.key) {
        if (message == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = message.actionLabel,
            duration = message.duration
                ?: if (message.actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite
        )
        latestOnResult(message.key, result == SnackbarResult.ActionPerformed)
    }
    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}
