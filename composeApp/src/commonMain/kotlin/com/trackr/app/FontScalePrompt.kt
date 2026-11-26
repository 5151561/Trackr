package com.trackr.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/** 系统字体放大到这个倍数及以上时提示一次「试试长辈版」（重构计划 §8.2） */
const val FONT_SCALE_PROMPT_THRESHOLD: Float = 1.3f

/**
 * 「字太小看不清？」提示（设计稿 redesign_v3_elder.html「开启入口」）：系统字体 ≥ [FONT_SCALE_PROMPT_THRESHOLD] 倍、
 * 且以前没点过时弹一次。不跟着系统字体自动切换，只提示；之后在「我的」里随时开关。
 *
 * 不论点「打开长辈版」「先不用」还是点外面关掉，都记为点过（[dismissed] 由调用方存进设置），以后不再弹。
 */
@Composable
internal fun FontScalePrompt(
    dismissed: Boolean,
    onEnableElder: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (dismissed || LocalDensity.current.fontScale < FONT_SCALE_PROMPT_THRESHOLD) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字太小看不清？") },
        text = { Text("试试长辈版：字更大，按钮更大，还能读给你听。之后在「我的」里随时开关。") },
        confirmButton = {
            TextButton(onClick = onEnableElder) { Text("打开长辈版") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("先不用") }
        }
    )
}
