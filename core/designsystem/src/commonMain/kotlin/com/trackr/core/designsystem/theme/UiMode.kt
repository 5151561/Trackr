package com.trackr.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.trackr.core.model.UiMode

/** 当前界面模式，由 [TrackrTheme] 在主题根提供一次。组件一般不读它，只读 Token（§8.3）。 */
val LocalUiMode = staticCompositionLocalOf { UiMode.Standard }
