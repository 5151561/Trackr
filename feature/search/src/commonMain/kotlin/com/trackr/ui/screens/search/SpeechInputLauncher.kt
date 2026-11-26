package com.trackr.ui.screens.search

import androidx.compose.runtime.Composable
import com.trackr.core.domain.platform.SpeechInput

/**
 * 长辈版搜索的语音输入（§8.1，第 34 步）。要挂在页面上（Android 要靠 Activity 结果拿识别文字），所以按页面 remember。
 *
 * Android：系统语音输入 `RecognizerIntent`，不要录音权限（录音的是系统的识别界面）。
 * 这台手机没有语音识别服务时返回 null：页面不显示话筒。
 * iOS：还没做（第 36 步 `SFSpeechRecognizer`），返回 null。
 */
@Composable
expect fun rememberSpeechInput(): SpeechInput?
