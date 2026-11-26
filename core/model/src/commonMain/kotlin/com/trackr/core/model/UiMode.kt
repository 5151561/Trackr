package com.trackr.core.model

/**
 * 界面模式（重构计划 §8）：标准版 / 长辈版。存在设置里（`SettingsRepository.uiMode`，DataStore 键 `ui_mode`），
 * 没存过按 [Standard]。主题按它换 Token（`TrackrTheme(uiMode)`）；第 31 步起导航结构也按它分。
 */
enum class UiMode { Standard, Elder }
