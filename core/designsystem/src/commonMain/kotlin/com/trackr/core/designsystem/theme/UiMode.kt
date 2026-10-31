package com.trackr.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面模式（重构计划 §8）。[Elder] 的高对比、大字号 Token 在第 30 步加；在那之前按 [Standard] 显示。
 * 第 7 步建 `:core:model` 时，如果设置层要用这个类型，再决定是否搬过去（本模块改依赖 `:core:model`）。
 */
enum class UiMode { Standard, Elder }

val LocalUiMode = staticCompositionLocalOf { UiMode.Standard }
